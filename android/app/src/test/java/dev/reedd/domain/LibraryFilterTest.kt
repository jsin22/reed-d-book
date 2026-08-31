package dev.reedd.domain

import dev.reedd.data.db.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {

    private fun book(id: String, category: String?, genres: List<String> = emptyList()) = BookEntity(
        id = id,
        epubPath = "/tmp/$id.epub",
        originalFilename = "$id.epub",
        title = id,
        category = category,
        genres = genres,
    )

    private val library = listOf(
        book("1", "Fiction", listOf("Horror")),
        book("2", "Fiction", listOf("Romance", "Fantasy")),
        book("3", "Non-fiction", listOf("Biography")),
        book("4", null, emptyList()),
    )

    @Test
    fun `no filter returns everything`() {
        assertEquals(library, library.filteredBy(LibraryFilter()))
    }

    @Test
    fun `category filters to an exact match, excluding unknown`() {
        assertEquals(listOf("1", "2"), library.filteredBy(LibraryFilter(category = "Fiction")).map { it.id })
    }

    @Test
    fun `genre filter matches a book with any of the selected genres`() {
        val selected = LibraryFilter(genres = setOf("Horror", "Biography"))
        assertEquals(listOf("1", "3"), library.filteredBy(selected).map { it.id })
    }

    @Test
    fun `a book with multiple genres matches on just one selected`() {
        val selected = LibraryFilter(genres = setOf("Fantasy"))
        assertEquals(listOf("2"), library.filteredBy(selected).map { it.id })
    }

    @Test
    fun `category and genre combine with AND`() {
        val selected = LibraryFilter(category = "Fiction", genres = setOf("Biography"))
        assertTrue(library.filteredBy(selected).isEmpty())
    }

    @Test
    fun `isActive reflects whether either facet is set`() {
        assertFalse(LibraryFilter().isActive)
        assertTrue(LibraryFilter(category = "Fiction").isActive)
        assertTrue(LibraryFilter(genres = setOf("Horror")).isActive)
    }

    @Test
    fun `available categories and genres are distinct and sorted`() {
        assertEquals(listOf("Fiction", "Non-fiction"), library.availableCategories())
        assertEquals(listOf("Biography", "Fantasy", "Horror", "Romance"), library.availableGenres())
    }
}
