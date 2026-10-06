package dev.reedd.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 1 -> 2: read-along.
 *
 * Phase 4 needs two things version 1 had nowhere to put: where each sentence sits
 * on the page (so it can be highlighted), and where playback had got to.
 *
 * Written out rather than falling back to a destructive recreate. A library holds
 * imported epubs and multi-hundred-megabyte audiobooks that cost a conversion each
 * to replace; silently dropping them on a schema bump would be the worst possible
 * failure. Every added column is nullable or carries a default, so existing rows
 * stay valid: a book already downloaded keeps its audio and its timings, reports
 * zero aligned chunks, and gets re-aligned lazily
 * (see [BookEntity.needsAlignment]).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Locator fields. Nullable: a chunk the aligner cannot place stays unaligned.
        db.execSQL("ALTER TABLE sync_chunks ADD COLUMN resourceHref TEXT")
        db.execSQL("ALTER TABLE sync_chunks ADD COLUMN textHighlight TEXT")
        db.execSQL("ALTER TABLE sync_chunks ADD COLUMN textBefore TEXT")
        db.execSQL("ALTER TABLE sync_chunks ADD COLUMN textAfter TEXT")
        db.execSQL("ALTER TABLE sync_chunks ADD COLUMN progression REAL")

        // Playback state. NOT NULL with defaults, so existing books start at the
        // beginning with no offset.
        db.execSQL("ALTER TABLE books ADD COLUMN playbackPositionMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE books ADD COLUMN syncOffsetMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE books ADD COLUMN alignedChunks INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE books ADD COLUMN totalChunks INTEGER NOT NULL DEFAULT 0")

        // A book that already has a mapping should report its size, so
        // needsAlignment can tell "nothing to align" from "not aligned yet".
        db.execSQL(
            """
            UPDATE books SET totalChunks =
                (SELECT COUNT(*) FROM sync_chunks WHERE sync_chunks.bookId = books.id)
            """.trimIndent()
        )
    }
}

/**
 * 2 -> 3: engine selection.
 *
 * The server has supported multiple TTS backends (`GET /api/engines`) since
 * before this column existed; the app just never asked. Nullable, no default
 * needed beyond SQLite's implicit NULL: an existing row's job was submitted
 * before this app version could choose an engine, so there is nothing truthful
 * to backfill it with -- the server's own default is what it actually ran with,
 * and [BookEntity.engine] being null already means "use the default" everywhere
 * it is read.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN engine TEXT")
    }
}

/**
 * 3 -> 4: sort/filter by category and genre (see `SORT_GROUP_LIBRARY.md`).
 *
 * Both nullable/defaulted, same reasoning as [MIGRATION_2_3]: an existing job's
 * category/genres were never looked up under an older app version, and there is
 * nothing truthful to backfill them with. `genres` defaults to `'[]'`, not
 * empty string, to match [Converters.stringToGenres]'s JSON decoding -- an
 * empty string there would fail to parse and silently fall back to the same
 * empty list anyway, but writing the real encoding avoids relying on that.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN category TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN genres TEXT NOT NULL DEFAULT '[]'")
    }
}

/**
 * 4 -> 5: `jobId` becomes unique -- see [BookEntity]'s own docstring for why.
 * Confirmed happening on a real device: two overlapping `reconcile()` calls
 * (the Library screen's launch-time one racing the Admin screen's "Re-check
 * all book metadata") each read the same "known jobIds" snapshot before
 * either had inserted, so both adopted the same finished job and left two
 * rows behind pointing at the same `jobId`, shown on the library as the same
 * book twice.
 *
 * Self-healing, not just forward-looking: creating a UNIQUE index over data
 * that already violates it fails outright, so any duplicate already sitting
 * on a device has to be resolved first, right here, not left for the user to
 * clean up by hand. Keeps the row with the larger `rowid` (SQLite's implicit
 * insertion-order id) per duplicated `jobId` -- the most recently created
 * one, which is what a stray second `adoptOne()` call would have produced --
 * and drops the rest. Whichever is kept already has everything that matters
 * (title/author/category/genres, job status) freshly re-synced from the
 * server the moment either copy was adopted, so there is no real data loss
 * beyond an orphaned epub file for the deleted row, which nothing will ever
 * reference again.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM books WHERE jobId IS NOT NULL AND rowid NOT IN (
                SELECT MAX(rowid) FROM books WHERE jobId IS NOT NULL GROUP BY jobId
            )
            """.trimIndent()
        )
        db.execSQL("DROP INDEX IF EXISTS index_books_jobId")
        db.execSQL("CREATE UNIQUE INDEX index_books_jobId ON books(jobId)")
    }
}

/**
 * 5 -> 6: notes.
 *
 * A new child table, same shape as [MIGRATION_1_2]'s `sync_chunks`/`sync_chapters`
 * addition -- nothing existing changes, so there is nothing to backfill.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId TEXT NOT NULL,
                noteText TEXT NOT NULL,
                quotedText TEXT NOT NULL,
                locatorJson TEXT NOT NULL,
                resourceHref TEXT NOT NULL,
                spineIndex INTEGER NOT NULL,
                progression REAL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_notes_bookId_spineIndex_progression " +
                "ON notes(bookId, spineIndex, progression)"
        )
    }
}

/**
 * 6 -> 7: auto-download a job this device itself submitted, once it finishes.
 *
 * A plain boolean, defaulted false: an existing row was either already
 * downloaded or is sitting on a conversion nobody explicitly asked to be
 * fetched automatically (the flag is only ever set going forward, by
 * `LibraryViewModel.importAndUpload`), so there is nothing to backfill.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN autoDownload INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * 7 -> 8: [ChunkAligner.ALIGNMENT_VERSION][dev.reedd.data.align.ChunkAligner].
 *
 * A plain counter, defaulted 0: every row written before this column existed
 * reads as older than any real version the aligner will ever declare, so
 * [BookEntity.needsAlignment] picks every one of them up for a fresh pass the
 * next time its book opens -- exactly the self-healing this column exists
 * for, with no separate backfill query needed here.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN alignmentVersion INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * 8 -> 9: bookmarks.
 *
 * A second, lighter-weight child table alongside notes -- a plain marked
 * position instead of a quoted passage with a written note -- same shape
 * as [MIGRATION_5_6]'s `notes` addition, so again nothing existing changes
 * and there is nothing to backfill.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId TEXT NOT NULL,
                type TEXT NOT NULL,
                locatorJson TEXT NOT NULL,
                resourceHref TEXT NOT NULL,
                spineIndex INTEGER NOT NULL,
                progression REAL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_bookmarks_bookId_spineIndex_progression " +
                "ON bookmarks(bookId, spineIndex, progression)"
        )
    }
}

/**
 * 9 -> 10: bookmarks and notes merge into one thing.
 *
 * A bookmark turned out to be nothing more than a note with no text and a
 * color -- two features doing almost the same job, shown in two separate
 * places. `notes` gains [NoteEntity.type] (defaulting to `DEFAULT`, the same
 * "plain, uncolored" meaning it already had); every existing row in
 * `bookmarks` becomes a `notes` row with that color and empty note/quoted
 * text (a bookmark never had either), and the now-redundant table is
 * dropped. Existing notes keep reading as plain/uncolored -- there is
 * nothing truthful to backfill a color onto a note nobody ever picked one
 * for.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN type TEXT NOT NULL DEFAULT 'DEFAULT'")
        db.execSQL(
            """
            INSERT INTO notes (bookId, noteText, quotedText, locatorJson, resourceHref, spineIndex, progression, createdAt, type)
            SELECT bookId, '', '', locatorJson, resourceHref, spineIndex, progression, createdAt, type FROM bookmarks
            """.trimIndent()
        )
        db.execSQL("DROP TABLE bookmarks")
    }
}

/**
 * 10 -> 11: upload mode (CPU_LIVE_READING_PLAN, Phase 3).
 *
 * `OFFLINE` for every existing row is the correct backfill, not just a
 * placeholder: 'Live'/'Live + Offline' did not exist as an upload choice
 * before this, so every book already in the library really was uploaded
 * the plain 'offline' way.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN uploadMode TEXT NOT NULL DEFAULT 'OFFLINE'")
    }
}

/**
 * 11 -> 12: live reading's own resume position (CPU_LIVE_READING_PLAN
 * Phase 4) -- a resource href + sentence index, not a millisecond
 * position, since a live session's audio is never persisted. NULL/0 for
 * every existing book is correct as-is: nothing before this had ever
 * been read live, so there is no real position to backfill.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN liveResourceHref TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN liveSentenceIndex INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN preferLiveReading INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Drops `notes`' own foreign key to `books` -- see [NoteEntity]'s own doc
 * for why (an ordinary "Delete from device" cascade-destroyed the reader's
 * own notes, even though that action is supposed to be safe/reversible).
 * SQLite has no `ALTER TABLE ... DROP CONSTRAINT`, so this is the standard
 * rebuild-the-table dance: a fresh `notes` table with the identical columns
 * but no `FOREIGN KEY` clause, the old rows copied over verbatim (nothing
 * about existing notes changes, only what future book-row deletions can do
 * to them), then the old table and its own index are replaced.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes RENAME TO notes_old")
        db.execSQL(
            """
            CREATE TABLE notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId TEXT NOT NULL,
                noteText TEXT NOT NULL,
                quotedText TEXT NOT NULL,
                locatorJson TEXT NOT NULL,
                resourceHref TEXT NOT NULL,
                spineIndex INTEGER NOT NULL,
                progression REAL,
                createdAt INTEGER NOT NULL,
                type TEXT NOT NULL DEFAULT 'DEFAULT'
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO notes (id, bookId, noteText, quotedText, locatorJson, resourceHref, spineIndex, progression, createdAt, type) " +
                "SELECT id, bookId, noteText, quotedText, locatorJson, resourceHref, spineIndex, progression, createdAt, type FROM notes_old"
        )
        db.execSQL("DROP TABLE notes_old")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_bookId_spineIndex_progression ON notes(bookId, spineIndex, progression)")
    }
}

/**
 * Splits the live-reading voice out of `voice` -- see [BookEntity.voice]'s
 * own doc: the two used to share one column, so picking a voice in the
 * reader's live-mode settings silently changed what a future offline
 * "Send again" would submit too. An existing book's `liveVoice` starts
 * null (falls back to its own `voice`, then a hardcoded default, per
 * `ReadAlongViewModel.buildLiveChunkSource`) -- nothing to actually
 * backfill, since nobody has picked a *separate* live voice yet.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN liveVoice TEXT")
    }
}

/**
 * `previousJobId`: the job a deliberate "convert again with a new voice"
 * replaced, kept only until the new one finishes and its old server-side
 * counterpart -- job directory, audiobook file included -- can be deleted.
 * See [BookEntity.previousJobId]'s own doc. Nothing to backfill for an
 * existing book: this only ever gets set going forward, by a reconvert
 * that has not happened yet.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN previousJobId TEXT")
    }
}

/** [BookEntity.finishedOverride]: null for every existing book, i.e. the
 *  automatic Finished rule, exactly as before. */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN finishedOverride INTEGER")
    }
}

/** [BookEntity.statusOverride] replaces the finished-only flag with any reading
 *  status. A book marked finished keeps that; a "not finished" pin (false) had
 *  no equivalent and goes back to automatic. */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN statusOverride TEXT")
        db.execSQL("UPDATE books SET statusOverride = 'FINISHED' WHERE finishedOverride = 1")
        db.execSQL("UPDATE books SET finishedOverride = NULL")
    }
}
