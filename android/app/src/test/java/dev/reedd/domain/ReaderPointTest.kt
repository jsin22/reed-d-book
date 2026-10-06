package dev.reedd.domain

import dev.reedd.data.db.SyncChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReaderPointTest {

    private val order = mapOf("c1.xhtml" to 1, "c2.xhtml" to 2)
    private fun orderOf(href: String) = order[bareResourceName(href)] ?: -1

    @Test
    fun `a page point is the text just before the end of the page, ending at the point`() {
        val text = "First sentence. Second sentence. Third, half on the next page"
        val end = text.indexOf("Third")
        val point = ReaderPoints.pageEnd("OEBPS/c1.xhtml", text, end)!!
        assertEquals("First sentence. Second sentence. ", point.anchorText)
        assertEquals(point.anchorText.length, point.anchorOffset)
        assertEquals(end.toDouble() / text.length, point.fraction, 1e-9)
    }

    @Test
    fun `a page point keeps only a bounded window`() {
        val text = "x".repeat(5_000)
        val point = ReaderPoints.pageEnd("c1.xhtml", text, 4_000)!!
        assertEquals(ReaderPoints.WINDOW, point.anchorText.length)
        assertNull(ReaderPoints.pageEnd("c1.xhtml", "", 0))
    }

    @Test
    fun `a sentence point ends at the end of the sentence being read`() {
        val chunk = SyncChunkEntity(
            bookId = "b", ordinal = 3, text = "Hyde.", startMs = 0, endMs = 1, chapter = 1,
            resourceHref = "OEBPS/c2.xhtml", textHighlight = "It named a man called Hyde.",
            textBefore = "the will of his friend. ", progression = 0.4,
        )
        val point = ReaderPoints.sentence(chunk)!!
        assertEquals("the will of his friend. It named a man called Hyde.", point.anchorText)
        assertEquals(point.anchorText.length, point.anchorOffset)
        assertNull(ReaderPoints.sentence(chunk.copy(resourceHref = null)))
        assertNull(ReaderPoints.sentence(null))
    }

    @Test
    fun `the furthest point wins, by chapter and then within one`() {
        val page = ReaderPoint("OEBPS/c1.xhtml", "a", 1, 0.9)
        val audioLaterChapter = ReaderPoint("OEBPS/c2.xhtml", "b", 1, 0.1)
        val audioSameChapterEarlier = ReaderPoint("OEBPS/c1.xhtml", "c", 1, 0.5)
        assertSame(audioLaterChapter, ReaderPoints.furthest(page, audioLaterChapter, ::orderOf))
        assertSame(page, ReaderPoints.furthest(page, audioSameChapterEarlier, ::orderOf))
        assertSame(page, ReaderPoints.furthest(page, null, ::orderOf))
        assertSame(page, ReaderPoints.furthest(null, page, ::orderOf))
    }
}
