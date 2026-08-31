package dev.reedd.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner

/**
 * [Link]/[Url] wrap `android.net.Uri` internally, so this needs Robolectric
 * (unlike [ChunkIndexTest], which works off raw strings and needs nothing
 * Android-specific).
 */
@RunWith(RobolectricTestRunner::class)
class NoteOrderingTest {

    private fun link(href: String) = Link(Url(href)!!)

    private val readingOrder = listOf(link("cover.xhtml"), link("chapters/c1.xhtml"), link("chapters/c2.xhtml"))

    @Test
    fun `an exact filename match resolves to its position`() {
        assertEquals(1, spineIndexOf(readingOrder, "chapters/c1.xhtml"))
        assertEquals(2, spineIndexOf(readingOrder, "chapters/c2.xhtml"))
    }

    @Test
    fun `a full path or a bare filename both match`() {
        assertEquals(1, spineIndexOf(readingOrder, "OEBPS/chapters/c1.xhtml"))
        assertEquals(1, spineIndexOf(readingOrder, "c1.xhtml"))
    }

    @Test
    fun `an href with no match resolves to null`() {
        assertNull(spineIndexOf(readingOrder, "nowhere.xhtml"))
    }
}
