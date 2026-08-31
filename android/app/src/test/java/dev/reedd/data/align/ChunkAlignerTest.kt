package dev.reedd.data.align

import dev.reedd.Fixtures
import dev.reedd.data.db.SyncChapterEntity
import dev.reedd.data.db.SyncChunkEntity
import dev.reedd.data.sync.SyncFileParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The aligner, run against the real `sample-short.epub` and the real sync file this
 * repo's audiblez fork produced for it — not a hand-written approximation of
 * either.
 */
class ChunkAlignerTest {

    private val aligner = ChunkAligner()

    private val parsed by lazy {
        SyncFileParser.parse(Fixtures.read("sync_sample_short.json"), bookId = "b1")
    }

    private val resources: List<ResourceText> by lazy {
        val epub = Files.createTempFile("sample", ".epub").toFile().apply {
            writeBytes(Fixtures.readBytes("sample-short.epub"))
            deleteOnExit()
        }
        EpubTextExtractor.extract(epub)
    }

    private fun alignReal() = aligner.align(parsed.chunks, parsed.chapters, resources)

    // -- the real book -------------------------------------------------------

    @Test
    fun `the sample epub yields readable resources`() {
        val hrefs = resources.map { it.href }
        assertTrue("expected the chapter resource, got $hrefs", hrefs.any { it.endsWith("understanding_digital_formats.xhtml") })
        assertTrue(resources.first { it.href.endsWith("understanding_digital_formats.xhtml") }.text.contains("Digital file formats"))
    }

    @Test
    fun `every spoken sentence except audiblez' injected title line is located`() {
        val result = alignReal()

        val unaligned = result.chunks.filterNot { it.isAligned }
        // Chunk 0 is "<title> - <author>.", which audiblez prepends and no epub
        // contains. Everything else must be findable on the page.
        assertEquals("unexpectedly unaligned: ${unaligned.map { it.text }}", 1, unaligned.size)
        assertEquals(0, unaligned.single().ordinal)
        assertEquals(11, result.aligned)
        assertEquals(12, result.total)
    }

    @Test
    fun `a heading whose chunk has an added period still matches`() {
        // The epub's <h1> is "Understanding Digital Formats"; audiblez speaks
        // "Understanding Digital Formats." with a period it appended.
        val chunk = alignReal().chunks.single { it.text.contains("Understanding Digital Formats") && it.ordinal == 1 }

        assertTrue(chunk.isAligned)
        // The stored highlight is the page's text, without the invented period.
        assertEquals("Understanding Digital Formats", chunk.textHighlight)
    }

    @Test
    fun `the stored highlight is a literal substring of the page text`() {
        // This is the property the whole approach rests on: Readium searches the
        // rendered DOM for this exact string.
        val result = alignReal()
        val byHref = resources.associate { it.href to it.text }

        result.chunks.filter { it.isAligned }.forEach { chunk ->
            val page = byHref.getValue(chunk.resourceHref!!)
            assertTrue(
                "not found verbatim on the page: ${chunk.textHighlight}",
                page.contains(chunk.textHighlight!!),
            )
            chunk.textBefore?.let { assertTrue("before context not on the page", page.contains(it)) }
            chunk.textAfter?.let { assertTrue("after context not on the page", page.contains(it)) }
        }
    }

    @Test
    fun `a highlight never begins or ends with whitespace`() {
        // Chunk text is verbatim from the splitter and often starts with newlines.
        alignReal().chunks.filter { it.isAligned }.forEach { chunk ->
            val highlight = chunk.textHighlight!!
            assertEquals(highlight.trim(), highlight)
        }
    }

    @Test
    fun `sentences are located in reading order`() {
        // A cursor that jumped backwards would mean a sentence matched an earlier
        // occurrence than the one being spoken.
        val located = alignReal().chunks
            .filter { it.isAligned && it.chapter == 1 }
            .map { chunk ->
                val page = resources.first { it.href == chunk.resourceHref }.text
                page.indexOf(chunk.textHighlight!!)
            }
        assertTrue("offsets not increasing: $located", located.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `alignment does not disturb the timings`() {
        val result = alignReal()
        assertEquals(parsed.chunks.map { it.startMs }, result.chunks.map { it.startMs })
        assertEquals(parsed.chunks.map { it.endMs }, result.chunks.map { it.endMs })
        assertEquals(parsed.chunks.map { it.ordinal }, result.chunks.map { it.ordinal })
    }

    // -- behaviour, on constructed input -------------------------------------

    private fun chunk(ordinal: Int, text: String, chapter: Int = 1, rowId: Long = ordinal.toLong() + 1) =
        SyncChunkEntity(rowId = rowId, bookId = "b1", ordinal = ordinal, text = text, startMs = ordinal * 1000L, endMs = (ordinal + 1) * 1000L, chapter = chapter)

    private fun chapter(index: Int, source: String) =
        SyncChapterEntity(bookId = "b1", chapterIndex = index, title = "C$index", source = source, startMs = 0, endMs = 1000)

    @Test
    fun `a repeated sentence resolves to successive occurrences, not the first twice`() {
        // The reason context is stored at all.
        val page = ResourceText("EPUB/c1.xhtml", "Alpha here. He nodded. Beta here. He nodded. Gamma here.")
        val chunks = listOf(
            chunk(0, "He nodded."),
            chunk(1, "Beta here."),
            chunk(2, "He nodded."),
        )

        val result = aligner.align(chunks, listOf(chapter(1, "c1.xhtml")), listOf(page))

        assertTrue(result.chunks.all { it.isAligned })
        val first = result.chunks[0]
        val second = result.chunks[2]
        // Same text, different context, so the two decorations land in different places.
        assertEquals("He nodded.", first.textHighlight)
        assertEquals("He nodded.", second.textHighlight)
        assertTrue(first.textBefore!!.endsWith("Alpha here. "))
        assertTrue(second.textBefore!!.endsWith("Beta here. "))
    }

    @Test
    fun `text the chunks skip is stepped over rather than breaking the run`() {
        // audiblez only reads title/p/h1-h4/li, so a table or blockquote in between
        // is text on the page that is never spoken.
        val page = ResourceText("EPUB/c1.xhtml", "First sentence. SKIPPED TABLE CONTENT. Second sentence.")
        val chunks = listOf(chunk(0, "First sentence."), chunk(1, "Second sentence."))

        val result = aligner.align(chunks, listOf(chapter(1, "c1.xhtml")), listOf(page))

        assertTrue(result.chunks.all { it.isAligned })
        assertEquals(2, result.aligned)
    }

    @Test
    fun `whitespace and typographic punctuation differences do not prevent a match`() {
        val page = ResourceText(
            "EPUB/c1.xhtml",
            "\n      He said “hello” — then left.\n      Next one.\n",
        )
        // audiblez' copy: collapsed spacing and ASCII punctuation.
        val chunks = listOf(chunk(0, "\nHe said \"hello\" - then left."))

        val result = aligner.align(chunks, listOf(chapter(1, "c1.xhtml")), listOf(page))

        val aligned = result.chunks.single()
        assertTrue(aligned.isAligned)
        // Stored as the page has it, curly quotes and em dash intact.
        assertEquals("He said “hello” — then left.", aligned.textHighlight)
    }

    @Test
    fun `a chapter whose resource is missing leaves its chunks unaligned but intact`() {
        val chunks = listOf(chunk(0, "Anything."))

        val result = aligner.align(chunks, listOf(chapter(1, "gone.xhtml")), emptyList())

        assertFalse(result.chunks.single().isAligned)
        assertEquals(0, result.aligned)
        assertEquals(1, result.total)
        // The timings must survive, so audio still plays without highlighting.
        assertEquals("Anything.", result.chunks.single().text)
    }

    @Test
    fun `a sentence that is not on the page is left unaligned`() {
        val page = ResourceText("EPUB/c1.xhtml", "Only this text exists here.")
        val chunks = listOf(chunk(0, "Only this text exists here."), chunk(1, "This was never in the book."))

        val result = aligner.align(chunks, listOf(chapter(1, "c1.xhtml")), listOf(page))

        assertTrue(result.chunks[0].isAligned)
        assertFalse(result.chunks[1].isAligned)
        assertNull(result.chunks[1].textHighlight)
        assertEquals(1, result.aligned)
    }

    @Test
    fun `chapters are matched to resources by filename despite a path prefix`() {
        val page = ResourceText("EPUB/text/c1.xhtml", "A sentence.")
        val result = aligner.align(listOf(chunk(0, "A sentence.")), listOf(chapter(1, "c1.xhtml")), listOf(page))
        assertEquals("EPUB/text/c1.xhtml", result.chunks.single().resourceHref)
    }

    @Test
    fun `each chapter's chunks are aligned against that chapter's own resource`() {
        val c1 = ResourceText("EPUB/c1.xhtml", "Chapter one text.")
        val c2 = ResourceText("EPUB/c2.xhtml", "Chapter two text.")
        val chunks = listOf(chunk(0, "Chapter one text.", chapter = 1), chunk(1, "Chapter two text.", chapter = 2))

        val result = aligner.align(chunks, listOf(chapter(1, "c1.xhtml"), chapter(2, "c2.xhtml")), listOf(c1, c2))

        assertEquals("EPUB/c1.xhtml", result.chunks[0].resourceHref)
        assertEquals("EPUB/c2.xhtml", result.chunks[1].resourceHref)
    }

    @Test
    fun `context is present but bounded`() {
        val page = ResourceText("EPUB/c1.xhtml", "x".repeat(500) + " Target sentence. " + "y".repeat(500))
        val result = ChunkAligner(contextChars = 10)
            .align(listOf(chunk(0, "Target sentence.")), listOf(chapter(1, "c1.xhtml")), listOf(page))

        val aligned = result.chunks.single()
        assertNotNull(aligned.textBefore)
        assertEquals(10, aligned.textBefore!!.length)
        assertEquals(10, aligned.textAfter!!.length)
    }

    @Test
    fun `a sentence at the very start has no before context`() {
        val page = ResourceText("EPUB/c1.xhtml", "Right at the start. Then more.")
        val result = aligner.align(listOf(chunk(0, "Right at the start.")), listOf(chapter(1, "c1.xhtml")), listOf(page))
        assertNull(result.chunks.single().textBefore)
    }

    @Test
    fun `an empty chunk is not matched to an arbitrary position`() {
        val page = ResourceText("EPUB/c1.xhtml", "Some text.")
        val result = aligner.align(listOf(chunk(0, "   \n  ")), listOf(chapter(1, "c1.xhtml")), listOf(page))
        assertFalse(result.chunks.single().isAligned)
    }

    @Test
    fun `the ratio reports how much of the book can be highlighted`() {
        val page = ResourceText("EPUB/c1.xhtml", "One. Two.")
        val chunks = listOf(chunk(0, "One."), chunk(1, "Two."), chunk(2, "Three."), chunk(3, "Four."))

        val result = aligner.align(chunks, listOf(chapter(1, "c1.xhtml")), listOf(page))

        assertEquals(2, result.aligned)
        assertEquals(4, result.total)
        assertEquals(0.5f, result.ratio, 0.001f)
    }

    @Test
    fun `a chapter with far more ellipses than characters of slack does not crash`() {
        // Regression, reproduced on a real device with a real book (Hidden
        // Pictures, chapter 10 -- 198 real ellipsis characters in one
        // resource). TextNormalizer folds '…' to "..." (3 output chars for 1
        // input char); the buffer that used to back this was only ever 4
        // characters larger than the input, so any resource with more than
        // two ellipses overran it. The overflow write was completely
        // unguarded and threw ArrayIndexOutOfBoundsException, uncaught all
        // the way up through DownloadWorker -- which left a download stuck
        // retrying itself forever instead of ever reaching a finished state.
        val text = "She trailed off… ".repeat(250) + "The final sentence."
        val page = ResourceText("EPUB/c1.xhtml", text)
        val target = chunk(0, "The final sentence.")

        val result = aligner.align(listOf(target), listOf(chapter(1, "c1.xhtml")), listOf(page)) // must not throw

        assertTrue(result.chunks.single().isAligned)
        assertEquals("The final sentence.", result.chunks.single().textHighlight)
    }
}
