package dev.reedd.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner

/** Robolectric, not a plain JUnit runner: constructing a [Link] needs a
 *  [Url], and [Url]'s string constructor goes through `android.net.Uri`
 *  under the hood -- unmocked outside Robolectric. */
@RunWith(RobolectricTestRunner::class)
class PublicationHrefsTest {

    private fun link(title: String, children: List<Link> = emptyList()) =
        Link(href = Url("c1.xhtml")!!, title = title, children = children)

    @Test
    fun `a flat list is returned as-is, every entry at depth 0`() {
        val toc = listOf(link("One"), link("Two"))
        assertEquals(listOf(0 to toc[0], 0 to toc[1]), flattenToc(toc))
    }

    @Test
    fun `nested entries come right after their parent, one depth deeper`() {
        val chapter1 = link("Chapter 1")
        val chapter2 = link("Chapter 2")
        val volume = link("Volume One", children = listOf(chapter1, chapter2))
        val toc = listOf(volume)

        assertEquals(listOf(0 to volume, 1 to chapter1, 1 to chapter2), flattenToc(toc))
    }

    @Test
    fun `each level of nesting adds one more depth`() {
        val leaf = link("Section 1.1")
        val chapter = link("Chapter 1", children = listOf(leaf))
        val volume = link("Volume One", children = listOf(chapter))

        assertEquals(listOf(0 to volume, 1 to chapter, 2 to leaf), flattenToc(listOf(volume)))
    }

    @Test
    fun `an empty table of contents flattens to an empty list`() {
        assertEquals(emptyList<Pair<Int, Link>>(), flattenToc(emptyList()))
    }

    @Test
    fun `bareResourceName strips the directory and decodes percent-escapes`() {
        assertEquals("c1.xhtml", bareResourceName("EPUB/c1.xhtml"))
        assertEquals("My Chapter.xhtml", bareResourceName("EPUB/My%20Chapter.xhtml"))
        assertEquals(null, bareResourceName(null))
    }

    @Test
    fun `bareResourceName strips a fragment too`() {
        // Real, confirmed case: a real book's own nav document links every
        // chapter as "ChapterNN.xhtml#chN" while the reading order's own
        // resources have no fragment at all -- see currentChapterTitle's
        // own regression test below for the bug this caused.
        assertEquals("c1.xhtml", bareResourceName("EPUB/c1.xhtml#ch1"))
        assertEquals("c1.xhtml", bareResourceName("EPUB/c1.xhtml"))
    }

    private fun spineLink(href: String) = Link(href = Url(href)!!)
    private fun tocLink(href: String, title: String) = Link(href = Url(href)!!, title = title)

    @Test
    fun `currentChapterTitle resolves the resource's own entry when it has one`() {
        val readingOrder = listOf(spineLink("c1.xhtml"), spineLink("c2.xhtml"), spineLink("c3.xhtml"))
        val toc = listOf(tocLink("c1.xhtml", "Chapter 1"), tocLink("c2.xhtml", "Chapter 2"))

        assertEquals("Chapter 2", currentChapterTitle(readingOrder, toc, "c2.xhtml"))
    }

    @Test
    fun `currentChapterTitle falls back to the closest preceding entry for a resource with none of its own`() {
        // A chapter can span several resources -- c2 has no table-of-contents
        // entry of its own, but it is still "inside" Chapter 1.
        val readingOrder = listOf(spineLink("c1.xhtml"), spineLink("c2.xhtml"), spineLink("c3.xhtml"))
        val toc = listOf(tocLink("c1.xhtml", "Chapter 1"), tocLink("c3.xhtml", "Chapter 3"))

        assertEquals("Chapter 1", currentChapterTitle(readingOrder, toc, "c2.xhtml"))
    }

    @Test
    fun `currentChapterTitle resolves nested entries too`() {
        val readingOrder = listOf(spineLink("c1.xhtml"), spineLink("c2.xhtml"))
        val chapter1 = tocLink("c1.xhtml", "Chapter 1")
        val volume = Link(href = Url("c0.xhtml")!!, title = "Volume One", children = listOf(chapter1))

        assertEquals("Chapter 1", currentChapterTitle(readingOrder, listOf(volume), "c2.xhtml"))
    }

    @Test
    fun `currentChapterTitle is null before the table of contents' own first entry`() {
        val readingOrder = listOf(spineLink("c0.xhtml"), spineLink("c1.xhtml"), spineLink("c2.xhtml"))
        val toc = listOf(tocLink("c1.xhtml", "Chapter 1"))

        assertEquals(null, currentChapterTitle(readingOrder, toc, "c0.xhtml"))
    }

    @Test
    fun `currentChapterTitle is null when the resource itself is not in the reading order`() {
        val readingOrder = listOf(spineLink("c1.xhtml"))
        val toc = listOf(tocLink("c1.xhtml", "Chapter 1"))

        assertEquals(null, currentChapterTitle(readingOrder, toc, "does-not-exist.xhtml"))
    }

    @Test
    fun `currentChapterTitle is null for an empty table of contents`() {
        val readingOrder = listOf(spineLink("c1.xhtml"))

        assertEquals(null, currentChapterTitle(readingOrder, emptyList(), "c1.xhtml"))
    }

    @Test
    fun `currentChapterTitle resolves an entry whose own href carries a fragment`() {
        // The real, confirmed bug (Karin Slaughter, "We Are All Guilty
        // Here"): every real chapter's nav entry links to "#chN" inside its
        // own file, while the reading order's own resources are whole-file
        // hrefs with no fragment -- every chapter entry used to fail to
        // resolve at all, leaving only a fragment-less front-matter
        // "Contents" entry to win on every single page of the book,
        // regardless of which chapter was actually on screen.
        val readingOrder = listOf(spineLink("contents.xhtml"), spineLink("c1.xhtml"), spineLink("c2.xhtml"))
        val toc = listOf(
            tocLink("contents.xhtml", "Contents"),
            tocLink("c1.xhtml#ch1", "Chapter One"),
            tocLink("c2.xhtml#ch2", "Chapter Two"),
        )

        assertEquals("Chapter One", currentChapterTitle(readingOrder, toc, "c1.xhtml"))
        assertEquals("Chapter Two", currentChapterTitle(readingOrder, toc, "c2.xhtml"))
    }
}
