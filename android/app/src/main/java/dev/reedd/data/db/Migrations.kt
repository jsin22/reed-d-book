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
