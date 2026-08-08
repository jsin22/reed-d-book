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
