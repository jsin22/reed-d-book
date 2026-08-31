package dev.reedd.domain

import dev.reedd.data.db.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortTest {

    private fun book(
        id: String,
        title: String,
        author: String? = null,
        addedAt: Long = 0,
        lastOpenedAt: Long? = null,
    ) = BookEntity(
        id = id,
        epubPath = "/tmp/$id.epub",
        originalFilename = "$id.epub",
        title = title,
        author = author,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
    )

    private val library = listOf(
        book("1", "The Shining", "Stephen King", addedAt = 100, lastOpenedAt = 500),
        book("2", "Dune", "Frank Herbert", addedAt = 300, lastOpenedAt = null),
        book("3", "an anthology", author = null, addedAt = 200, lastOpenedAt = 100),
    )

    @Test
    fun `title ascending is case-insensitive`() {
        assertEquals(listOf("3", "2", "1"), library.librarySorted(LibrarySort.TITLE_ASC).map { it.id })
    }

    @Test
    fun `author ascending puts books with no author last`() {
        assertEquals(listOf("2", "1", "3"), library.librarySorted(LibrarySort.AUTHOR_ASC).map { it.id })
    }

    @Test
    fun `recently added is newest first`() {
        assertEquals(listOf("2", "3", "1"), library.librarySorted(LibrarySort.RECENTLY_ADDED).map { it.id })
    }

    @Test
    fun `recently opened puts never-opened books last`() {
        assertEquals(listOf("1", "3", "2"), library.librarySorted(LibrarySort.RECENTLY_OPENED).map { it.id })
    }
}
