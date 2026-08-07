package dev.reedd.data.db

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncDaoTest {

    private lateinit var db: ReeddDatabase
    private lateinit var sync: SyncDao
    private lateinit var books: BookDao

    @Before
    fun setUp() {
        db = inMemoryDb()
        sync = db.sync()
        books = db.books()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(bookId: String = "b1") {
        books.insert(book(bookId))
        sync.replace(
            bookId,
            chunks = listOf(
                chunk(bookId, 0, 0, 3_725, "A Brief Guide to Digital Formats - Sample Generator."),
                chunk(bookId, 1, 3_725, 6_100, "\n\nUnderstanding Digital Formats."),
                chunk(bookId, 2, 6_100, 10_375, "\nDigital file formats are the foundation."),
            ),
            chapters = listOf(
                SyncChapterEntity(bookId = bookId, chapterIndex = 1, title = "Chapter 1", source = "c1.xhtml", startMs = 0, endMs = 61_500),
                SyncChapterEntity(bookId = bookId, chapterIndex = 2, title = "Chapter 2", source = "nav.xhtml", startMs = 61_500, endMs = 66_325),
            ),
        )
    }

    @Test
    fun `chunks come back in playback order with text verbatim`() = runTest {
        seed()

        val chunks = sync.chunks("b1")

        assertEquals(listOf(0, 1, 2), chunks.map { it.ordinal })
        // Leading newlines are part of the mapping: they are what lets the text
        // be matched against the epub.
        assertEquals("\n\nUnderstanding Digital Formats.", chunks[1].text)
        assertEquals(3, sync.chunkCount("b1"))
    }

    @Test
    fun `chapters come back in order`() = runTest {
        seed()
        assertEquals(listOf(1, 2), sync.chapters("b1").map { it.chapterIndex })
        assertEquals(66_325L, sync.chapters("b1").last().endMs)
    }

    @Test
    fun `a playback position resolves to the chunk covering it`() = runTest {
        seed()

        assertEquals(0, sync.chunkAt("b1", 0)?.ordinal)
        assertEquals(0, sync.chunkAt("b1", 3_724)?.ordinal)
        // Exactly on a boundary belongs to the chunk that starts there: the file
        // guarantees chunks[n].end == chunks[n+1].start.
        assertEquals(1, sync.chunkAt("b1", 3_725)?.ordinal)
        assertEquals(2, sync.chunkAt("b1", 9_999)?.ordinal)
        // Past the end, the last chunk stays highlighted rather than nothing.
        assertEquals(2, sync.chunkAt("b1", 999_999)?.ordinal)
    }

    @Test
    fun `a position before the first chunk resolves to nothing`() = runTest {
        books.insert(book("b1"))
        sync.replace("b1", listOf(chunk("b1", 0, 5_000, 6_000)), emptyList())
        assertNull(sync.chunkAt("b1", 0))
    }

    @Test
    fun `replace swaps the whole mapping rather than appending`() = runTest {
        seed()

        sync.replace(
            "b1",
            chunks = listOf(chunk("b1", 0, 0, 1_000, "only one now")),
            chapters = listOf(SyncChapterEntity(bookId = "b1", chapterIndex = 1, title = "One", source = null, startMs = 0, endMs = 1_000)),
        )

        // A re-download must not leave half of the old file behind.
        assertEquals(1, sync.chunkCount("b1"))
        assertEquals("only one now", sync.chunks("b1").single().text)
        assertEquals(1, sync.chapters("b1").size)
    }

    @Test
    fun `one book's mapping is independent of another's`() = runTest {
        seed("b1")
        seed("b2")

        sync.clearChunks("b1")

        assertEquals(0, sync.chunkCount("b1"))
        assertEquals(3, sync.chunkCount("b2"))
    }

    @Test
    fun `deleting a book cascades to its mapping`() = runTest {
        seed()
        assertEquals(3, sync.chunkCount("b1"))

        books.delete("b1")

        // Room switches SQLite's foreign keys on, so the child rows go with it.
        assertEquals(0, sync.chunkCount("b1"))
        assertTrue(sync.chapters("b1").isEmpty())
    }
}
