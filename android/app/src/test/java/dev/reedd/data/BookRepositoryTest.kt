package dev.reedd.data

import dev.reedd.data.db.NoteEntity
import dev.reedd.data.db.book
import dev.reedd.data.db.chunk
import dev.reedd.data.db.inMemoryDb
import dev.reedd.data.remote.ApiProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [BookRepository.deleteLocalContent] -- the library card's trash icon.
 *
 * Real Room instances rather than mocks, same as [dev.reedd.data.db.BookDaoTest]
 * et al: this function's whole job is coordinating several tables and several
 * files correctly, which a mock would just assert was *called*, not that it
 * actually left the right rows and files behind.
 */
@RunWith(RobolectricTestRunner::class)
class BookRepositoryTest {

    private lateinit var db: dev.reedd.data.db.ReeddDatabase
    private lateinit var repository: BookRepository
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        db = inMemoryDb()
        val api = ApiProvider(baseUrl = { null }, token = { null })
        repository = BookRepository(db.books(), db.sync(), api)
        tmpDir = File.createTempFile("book-repo-test", "").also { it.delete(); it.mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        tmpDir.deleteRecursively()
    }

    private fun file(name: String, content: String = "x") =
        File(tmpDir, name).apply { writeText(content) }

    @Test
    fun `deleteLocalContent removes every downloaded file and resets local state, but keeps the row and notes`() = runTest {
        val epub = file("book.epub")
        val cover = file("cover.jpg")
        val audiobook = file("book.m4b")
        val sync = file("book.json")

        db.books().insert(
            book("b1", jobId = "job-1", downloadState = dev.reedd.data.db.DownloadState.DONE)
                .copy(
                    epubPath = epub.path,
                    coverPath = cover.path,
                    audiobookPath = audiobook.path,
                    syncPath = sync.path,
                    readingLocator = """{"href":"c3.xhtml"}""",
                    playbackPositionMs = 45_000,
                    syncOffsetMs = 60,
                    alignedChunks = 10,
                    totalChunks = 12,
                    alignmentVersion = 2,
                    downloadedBytes = 1_000,
                    downloadTotalBytes = 1_000,
                )
        )
        db.sync().insertChunks(listOf(chunk("b1", 0, 0, 100), chunk("b1", 1, 100, 200)))
        db.notes().insert(
            NoteEntity(
                bookId = "b1",
                noteText = "worth remembering",
                quotedText = "a passage",
                locatorJson = """{"href":"c1.xhtml"}""",
                resourceHref = "c1.xhtml",
                spineIndex = 0,
                progression = 0.1,
                createdAt = 1_000,
            )
        )

        repository.deleteLocalContent("b1")

        // Every file this device had for the book is gone.
        assertFalse(epub.exists())
        assertFalse(cover.exists())
        assertFalse(audiobook.exists())
        assertFalse(sync.exists())

        val book = repository.get("b1")!!
        // The row -- and the job it can be re-fetched from -- survive.
        assertEquals("job-1", book.jobId)
        assertNull(book.audiobookPath)
        assertNull(book.syncPath)
        assertNull(book.coverPath)
        assertEquals(dev.reedd.data.db.DownloadState.NONE, book.downloadState)
        assertEquals(0L, book.downloadedBytes)
        assertEquals(0L, book.downloadTotalBytes)
        assertNull(book.readingLocator)
        assertEquals(0L, book.playbackPositionMs)
        assertEquals(0L, book.syncOffsetMs)
        assertEquals(0, book.alignedChunks)
        assertEquals(0, book.totalChunks)
        assertEquals(0, book.alignmentVersion)

        assertTrue("sync_chunks must be cleared too, not just the book row", db.sync().chunks("b1").isEmpty())

        // Notes have no server copy at all -- deleting them here would be
        // unrecoverable, so this is the one thing left untouched.
        assertEquals(1, db.notes().observe("b1").first().size)
    }
}
