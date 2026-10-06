package dev.reedd.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorNamesTest {

    @Test
    fun `surname is the last word, skipping suffixes`() {
        assertEquals("Stevenson", authorSurname("Robert Louis Stevenson"))
        assertEquals("King", authorSurname("Martin Luther King Jr."))
        assertEquals("King", authorSurname("Martin Luther King, Jr."))
        assertEquals("Fitzgerald", authorSurname("F. Scott Fitzgerald"))
    }

    @Test
    fun `a surname-first name and several authors are handled`() {
        assertEquals("Stevenson", authorSurname("Stevenson, Robert Louis"))
        assertEquals("Gaiman", authorSurname("Neil Gaiman and Terry Pratchett"))
        assertEquals("Gaiman", authorSurname("Neil Gaiman & Terry Pratchett"))
    }

    @Test
    fun `no author has no surname or sort key`() {
        assertNull(authorSurname(null))
        assertNull(authorSurname("   "))
        assertNull(authorSortKey(null))
    }

    @Test
    fun `sort key folds accents and case`() {
        assertEquals(authorSortKey("Patrick Obrian"), authorSortKey("PATRICK ÓBRIAN"))
    }
}
