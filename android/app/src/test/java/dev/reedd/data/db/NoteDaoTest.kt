package dev.reedd.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteDaoTest {

    private lateinit var db: ReeddDatabase
    private lateinit var notes: NoteDao
    private lateinit var books: BookDao

    @Before
    fun setUp() {
        db = inMemoryDb()
        notes = db.notes()
        books = db.books()
    }

    @After
    fun tearDown() = db.close()

    private fun note(
        bookId: String,
        noteText: String = "a note",
        quotedText: String = "a word",
        spineIndex: Int = 0,
        progression: Double? = 0.0,
        type: BookmarkType = BookmarkType.DEFAULT,
    ) = NoteEntity(
        bookId = bookId,
        noteText = noteText,
        quotedText = quotedText,
        locatorJson = """{"href":"c1.xhtml"}""",
        resourceHref = "c1.xhtml",
        spineIndex = spineIndex,
        progression = progression,
        createdAt = 1_000,
        type = type,
    )

    @Test
    fun `notes come back ordered by where they were taken in the book, not when`() = runTest {
        books.insert(book("b1"))
        // Inserted out of book order, and with the later-chapter note created
        // first, so the query -- not insertion order -- has to be doing the
        // sorting.
        notes.insert(note("b1", quotedText = "third", spineIndex = 2, progression = 0.1))
        notes.insert(note("b1", quotedText = "first", spineIndex = 0, progression = 0.5))
        notes.insert(note("b1", quotedText = "second", spineIndex = 0, progression = 0.9))

        val ordered = notes.observe("b1").first { it.size == 3 }
        assertEquals(listOf("first", "second", "third"), ordered.map { it.quotedText })
    }

    @Test
    fun `one book's notes are independent of another's`() = runTest {
        books.insert(book("b1"))
        books.insert(book("b2"))
        notes.insert(note("b1"))
        notes.insert(note("b2"))
        notes.insert(note("b2"))

        assertEquals(1, notes.observe("b1").first().size)
        assertEquals(2, notes.observe("b2").first().size)
    }

    @Test
    fun `deleting a book leaves its notes intact`() = runTest {
        // notes.bookId has no foreign key any more (see NoteEntity's own
        // doc, MIGRATION_13_14) -- an ordinary "Delete from device" used to
        // cascade-destroy every note the reader had written for that book,
        // which is exactly the loss this test now guards against.
        books.insert(book("b1"))
        notes.insert(note("b1"))
        assertEquals(1, notes.observe("b1").first().size)

        books.delete("b1")

        assertEquals(1, notes.observe("b1").first().size)
    }

    @Test
    fun `a note can be deleted on its own`() = runTest {
        books.insert(book("b1"))
        val id = notes.insert(note("b1"))
        notes.insert(note("b1", quotedText = "still here"))

        notes.delete(id)

        assertEquals(listOf("still here"), notes.observe("b1").first().map { it.quotedText })
    }

    @Test
    fun `a note can carry a color and no text, the same shape a bookmark used to be`() = runTest {
        books.insert(book("b1"))
        notes.insert(note("b1", noteText = "", quotedText = "", type = BookmarkType.NEEDS_REVIEW))

        val saved = notes.observe("b1").first().single()
        assertEquals(BookmarkType.NEEDS_REVIEW, saved.type)
        assertEquals("", saved.noteText)
    }

    @Test
    fun `a plain note with no color chosen defaults to DEFAULT`() = runTest {
        books.insert(book("b1"))
        notes.insert(note("b1"))

        assertEquals(BookmarkType.DEFAULT, notes.observe("b1").first().single().type)
    }

    @Test
    fun `update changes only the text and color, not what the note quotes or points at`() = runTest {
        books.insert(book("b1"))
        val id = notes.insert(note("b1", noteText = "first draft", type = BookmarkType.DEFAULT))

        notes.update(id, noteText = "revised", type = BookmarkType.NEEDS_REVIEW)

        val updated = notes.observe("b1").first().single()
        assertEquals("revised", updated.noteText)
        assertEquals(BookmarkType.NEEDS_REVIEW, updated.type)
        // Everything else about when/where this note was taken is fixed.
        assertEquals("a word", updated.quotedText)
        assertEquals("c1.xhtml", updated.resourceHref)
        assertEquals(1_000L, updated.createdAt)
    }
}
