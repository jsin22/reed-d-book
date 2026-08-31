package dev.reedd.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The 1 -> 2 migration, against a database actually created at version 1.
 *
 * The old schema is not written out here: it is read from
 * `app/schemas/.../1.json`, the file Room itself exported, so this test cannot
 * drift from the real version 1. Opening the result with Room is the strongest
 * assertion available — Room validates the live schema against its expectation of
 * version 2 and throws if the migration produced anything different, so a
 * forgotten column fails here rather than on someone's phone.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Must be the path Room itself would use: `databaseBuilder` resolves a name
     * under the app's `databases/` directory, so a file created anywhere else
     * would simply be ignored and Room would build a fresh version 2 database --
     * a test that passes while testing nothing.
     */
    private val dbFile: File = context.getDatabasePath(DB_NAME).also { it.parentFile?.mkdirs() }

    @After
    fun tearDown() {
        listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }
    }

    /** Builds an empty version 1 database using Room's own exported DDL. */
    private fun createVersion1(): android.database.sqlite.SQLiteDatabase {
        val schema = JSONObject(readSchema(version = 1)).getJSONObject("database")
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)

        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        // Room's own bookkeeping: without the identity hash it refuses to open an
        // existing file, and without user_version it would not know to migrate.
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(schema.getString("identityHash")),
        )
        db.version = 1
        return db
    }

    private fun readSchema(version: Int): String =
        requireNotNull(
            javaClass.classLoader?.getResourceAsStream("dev.reedd.data.db.ReeddDatabase/$version.json")
        ) { "exported schema $version.json is not on the test classpath" }
            .use { it.readBytes().decodeToString() }

    private fun openMigrated(): ReeddDatabase =
        Room.databaseBuilder(context, ReeddDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

    private companion object {
        const val DB_NAME = "migration-test.db"
    }

    @Test
    fun `a version 1 library survives the migration with its books and timings`() = runTest {
        createVersion1().use { old ->
            old.execSQL(
                """
                INSERT INTO books (id, epubPath, originalFilename, title, sizeBytes, addedAt,
                                   jobProgress, jobChaptersDone, jobMissing, uploadedBytes,
                                   downloadState, downloadedBytes, downloadTotalBytes,
                                   audiobookPath, syncPath, readingLocator, audioDurationMs)
                VALUES ('b1', '/data/b1/book.epub', 'Book.epub', 'A Real Book', 4096, 100,
                        100, 3, 0, 4096, 'DONE', 4096, 4096,
                        '/data/b1/book.m4b', '/data/b1/book.json', '{"href":"c1.xhtml"}', 66325)
                """.trimIndent()
            )
            old.execSQL(
                """
                INSERT INTO sync_chunks (bookId, ordinal, text, startMs, endMs, chapter)
                VALUES ('b1', 0, 'The first sentence.', 0, 3725, 1),
                       ('b1', 1, 'The second sentence.', 3725, 6100, 1)
                """.trimIndent()
            )
            old.execSQL(
                """
                INSERT INTO sync_chapters (bookId, chapterIndex, title, source, startMs, endMs)
                VALUES ('b1', 1, 'Chapter 1', 'c1.xhtml', 0, 6100)
                """.trimIndent()
            )
        }

        val db = openMigrated()
        try {
            val book = db.books().get("b1")!!
            // Nothing a conversion paid for may be lost.
            assertEquals("A Real Book", book.title)
            assertEquals("/data/b1/book.m4b", book.audiobookPath)
            assertEquals("/data/b1/book.json", book.syncPath)
            assertEquals(DownloadState.DONE, book.downloadState)
            assertEquals("""{"href":"c1.xhtml"}""", book.readingLocator)
            assertEquals(66_325L, book.audioDurationMs)

            // New playback columns take their defaults.
            assertEquals(0L, book.playbackPositionMs)
            assertEquals(0L, book.syncOffsetMs)
            assertEquals(0, book.alignedChunks)

            // New sort/filter columns (MIGRATION_3_4) take their defaults too --
            // a book converted before the lookup existed has nothing truthful to
            // backfill either with, same reasoning as `engine` in MIGRATION_2_3.
            assertNull(book.category)
            assertEquals(emptyList<String>(), book.genres)

            // The mapping survives, unaligned.
            val chunks = db.sync().chunks("b1")
            assertEquals(2, chunks.size)
            assertEquals("The first sentence.", chunks[0].text)
            assertEquals(3_725L, chunks[0].endMs)
            assertNull(chunks[0].textHighlight)
            assertNull(chunks[0].resourceHref)
            assertFalse(chunks[0].isAligned)

            assertEquals("c1.xhtml", db.sync().chapters("b1").single().source)
        } finally {
            db.close()
        }
    }

    @Test
    fun `MIGRATION_4_5 keeps one row when two books already share a jobId`() = runTest {
        // Reproduces a real device's state: ServerLibraryAdopter.adopt()
        // raced with itself (two overlapping reconcile() calls) and left two
        // local rows pointing at the same server job before jobId was
        // unique. The migration has to resolve this itself, not just refuse
        // to create the index and crash every future launch.
        createVersion1().use { old ->
            old.execSQL(
                """
                INSERT INTO books (id, epubPath, originalFilename, title, sizeBytes, addedAt,
                                   jobId, jobProgress, jobChaptersDone, jobMissing, uploadedBytes,
                                   downloadState, downloadedBytes, downloadTotalBytes)
                VALUES ('older-duplicate', '/e1', 'Book.epub', 'The Tell-Tale Heart', 1, 100,
                        'job-1', 100, 1, 0, 1, 'NONE', 0, 0),
                       ('newer-duplicate', '/e2', 'Book.epub', 'The Tell-Tale Heart', 1, 200,
                        'job-1', 100, 1, 0, 1, 'NONE', 0, 0),
                       ('unrelated', '/e3', 'Book.epub', 'Some Other Book', 1, 300,
                        NULL, 0, 0, 0, 0, 'NONE', 0, 0)
                """.trimIndent()
            )
        }

        val db = openMigrated()
        try {
            val survivors = db.books().all()
            // Exactly one of the two duplicates is kept -- the migration
            // picks by insertion order (MAX(rowid)), which for this data is
            // 'newer-duplicate', but the important property is that there is
            // only ever one, not which specific id survives.
            assertEquals(2, survivors.size)
            assertEquals(1, survivors.count { it.jobId == "job-1" })
            assertTrue(survivors.any { it.id == "unrelated" })

            // And the constraint that caused the duplicate in the first
            // place is now actually enforced going forward.
            var rejected = false
            try {
                db.books().insert(book("fresh-duplicate", jobId = "job-1"))
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                rejected = true
            }
            assertTrue("a second insert with the same jobId must now fail", rejected)
        } finally {
            db.close()
        }
    }

    @Test
    fun `an existing mapping is counted so it can be re-aligned lazily`() = runTest {
        createVersion1().use { old ->
            old.execSQL(
                """
                INSERT INTO books (id, epubPath, originalFilename, title, sizeBytes, addedAt,
                                   jobProgress, jobChaptersDone, jobMissing, uploadedBytes,
                                   downloadState, downloadedBytes, downloadTotalBytes,
                                   audiobookPath, syncPath)
                VALUES ('b1', '/e', 'Book.epub', 'Book', 1, 1, 100, 1, 0, 1, 'DONE', 1, 1,
                        '/data/b1/book.m4b', '/data/b1/book.json')
                """.trimIndent()
            )
            old.execSQL(
                """
                INSERT INTO sync_chunks (bookId, ordinal, text, startMs, endMs, chapter)
                VALUES ('b1', 0, 'a', 0, 1, 1), ('b1', 1, 'b', 1, 2, 1), ('b1', 2, 'c', 2, 3, 1)
                """.trimIndent()
            )
        }

        val db = openMigrated()
        try {
            val book = db.books().get("b1")!!
            // totalChunks is backfilled from the rows that are already there, which
            // is what lets needsAlignment distinguish "nothing to align" from
            // "downloaded before the aligner existed".
            assertEquals(3, book.totalChunks)
            assertEquals(0, book.alignedChunks)
            assertTrue(book.isPlayable)
            assertTrue(book.needsAlignment)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a book with no mapping is not flagged for alignment`() = runTest {
        createVersion1().use { old ->
            old.execSQL(
                """
                INSERT INTO books (id, epubPath, originalFilename, title, sizeBytes, addedAt,
                                   jobProgress, jobChaptersDone, jobMissing, uploadedBytes,
                                   downloadState, downloadedBytes, downloadTotalBytes)
                VALUES ('b1', '/e', 'Book.epub', 'Book', 1, 1, 0, 0, 0, 0, 'NONE', 0, 0)
                """.trimIndent()
            )
        }

        val db = openMigrated()
        try {
            val book = db.books().get("b1")!!
            assertEquals(0, book.totalChunks)
            assertFalse(book.needsAlignment)
        } finally {
            db.close()
        }
    }

    @Test
    fun `MIGRATION_5_6 adds an empty notes table a pre-existing library can write to`() = runTest {
        createVersion1().use { old ->
            old.execSQL(
                """
                INSERT INTO books (id, epubPath, originalFilename, title, sizeBytes, addedAt,
                                   jobProgress, jobChaptersDone, jobMissing, uploadedBytes,
                                   downloadState, downloadedBytes, downloadTotalBytes)
                VALUES ('b1', '/e', 'Book.epub', 'Book', 1, 1, 0, 0, 0, 0, 'NONE', 0, 0)
                """.trimIndent()
            )
        }

        val db = openMigrated()
        try {
            // Nothing to backfill -- an existing library has no notes to invent.
            assertTrue(db.notes().observe("b1").first().isEmpty())

            val noteId = db.notes().insert(
                NoteEntity(
                    bookId = "b1",
                    noteText = "worth remembering",
                    quotedText = "a passage",
                    locatorJson = """{"href":"c1.xhtml"}""",
                    resourceHref = "c1.xhtml",
                    spineIndex = 0,
                    progression = 0.2,
                    createdAt = 1_000,
                )
            )
            assertEquals("worth remembering", db.notes().observe("b1").first().single { it.id == noteId }.noteText)

            // And it cascades with the book, same as sync_chunks/sync_chapters.
            db.books().delete("b1")
            assertTrue(db.notes().observe("b1").first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `the migrated database accepts new writes`() = runTest {
        createVersion1().use { old ->
            old.execSQL(
                """
                INSERT INTO books (id, epubPath, originalFilename, title, sizeBytes, addedAt,
                                   jobProgress, jobChaptersDone, jobMissing, uploadedBytes,
                                   downloadState, downloadedBytes, downloadTotalBytes)
                VALUES ('b1', '/e', 'Book.epub', 'Book', 1, 1, 0, 0, 0, 0, 'NONE', 0, 0)
                """.trimIndent()
            )
            old.execSQL(
                "INSERT INTO sync_chunks (bookId, ordinal, text, startMs, endMs, chapter) VALUES ('b1', 0, 'a', 0, 1, 1)"
            )
        }

        val db = openMigrated()
        try {
            db.books().updatePlaybackPosition("b1", 12_345)
            db.books().updateSyncOffset("b1", -50)
            val chunk = db.sync().chunks("b1").single()
            db.sync().setLocator(chunk.rowId, "c1.xhtml", "a", "before", "after", progression = 0.25)

            val book = db.books().get("b1")!!
            assertEquals(12_345L, book.playbackPositionMs)
            assertEquals(-50L, book.syncOffsetMs)
            val aligned = db.sync().chunks("b1").single()
            assertEquals("c1.xhtml", aligned.resourceHref)
            assertEquals(0.25, aligned.progression!!, 0.0001)
            assertTrue(aligned.isAligned)
            assertEquals(1, db.sync().alignedCount("b1"))
        } finally {
            db.close()
        }
    }
}
