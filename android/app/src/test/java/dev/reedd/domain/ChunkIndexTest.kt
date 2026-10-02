package dev.reedd.domain

import dev.reedd.Fixtures
import dev.reedd.data.db.SyncChunkEntity
import dev.reedd.data.sync.SyncFileParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkIndexTest {

    private fun chunk(ordinal: Int, start: Long, end: Long) =
        SyncChunkEntity(bookId = "b1", ordinal = ordinal, text = "s$ordinal", startMs = start, endMs = end, chapter = 1)

    /** Contiguous, as the sync file guarantees. */
    private val index = ChunkIndex(
        listOf(
            chunk(0, 0, 3_725),
            chunk(1, 3_725, 6_100),
            chunk(2, 6_100, 10_375),
            chunk(3, 10_375, 19_300),
        )
    )

    @Test
    fun `a position inside a sentence finds it`() {
        assertEquals(0, index.indexAt(0))
        assertEquals(0, index.indexAt(1_000))
        assertEquals(1, index.indexAt(5_000))
        assertEquals(3, index.indexAt(15_000))
    }

    @Test
    fun `a boundary belongs to the sentence that starts there`() {
        // chunks[n].end == chunks[n+1].start, so the alternative would leave the
        // just-finished sentence highlighted.
        assertEquals(0, index.indexAt(3_724))
        assertEquals(1, index.indexAt(3_725))
        assertEquals(2, index.indexAt(6_100))
    }

    @Test
    fun `before the first sentence nothing is selected`() {
        val late = ChunkIndex(listOf(chunk(0, 5_000, 6_000)))
        assertEquals(-1, late.indexAt(0))
        assertEquals(-1, late.indexAt(4_999))
        assertEquals(0, late.indexAt(5_000))
        assertNull(late.chunkAt(0))
    }

    @Test
    fun `past the end the last sentence stays selected`() {
        // Rather than the highlight disappearing as a book finishes.
        assertEquals(3, index.indexAt(19_300))
        assertEquals(3, index.indexAt(999_999))
    }

    @Test
    fun `an empty mapping never resolves`() {
        assertEquals(-1, ChunkIndex.EMPTY.indexAt(0))
        assertNull(ChunkIndex.EMPTY.chunkAt(1_000))
        assertTrue(ChunkIndex.EMPTY.isEmpty)
        assertEquals(0L, ChunkIndex.EMPTY.durationMs)
    }

    @Test
    fun `lookup is correct after seeking backwards`() {
        // The reason this is a binary search rather than a forward walk from the
        // last chunk: a scrub can move the position anywhere, in either direction.
        assertEquals(3, index.indexAt(15_000))
        assertEquals(0, index.indexAt(500))
        assertEquals(2, index.indexAt(7_000))
        assertEquals(1, index.indexAt(4_000))
    }

    @Test
    fun `the timing offset shifts the lookup`() {
        // 200 ms of AAC priming would make highlighting run early; a positive
        // offset looks further ahead in the mapping to compensate.
        val shifted = index.withOffset(200)
        assertEquals(0, index.indexAt(3_600))
        assertEquals(1, shifted.indexAt(3_600))   // 3600 + 200 = 3800, into chunk 1
        assertEquals(200L, shifted.offsetMs)
    }

    @Test
    fun `withOffset returns the same instance when nothing changes`() {
        assertTrue(index.withOffset(0) === index)
        assertTrue(index.withOffset(50) !== index)
    }

    @Test
    fun `seeking to a sentence undoes the offset`() {
        // The offset corrects the lookup; applying it to a seek as well would move
        // playback by it every time the user tapped a sentence.
        assertEquals(3_725L, index.seekPositionFor(1))
        assertEquals(3_525L, index.withOffset(200).seekPositionFor(1))
        // Never negative, whatever the offset.
        assertEquals(0L, index.withOffset(10_000).seekPositionFor(0))
        assertNull(index.seekPositionFor(99))
    }

    @Test
    fun `next sentence advances and stops at the end`() {
        assertEquals(1, index.nextIndex(1_000))
        assertEquals(3, index.nextIndex(7_000))
        assertNull("there is nothing after the last sentence", index.nextIndex(19_000))
    }

    @Test
    fun `previous restarts the current sentence when playback is well into it`() {
        // What "back" does on every media control: restart, then skip back.
        assertEquals(2, index.previousIndex(8_000))          // 1.9s into chunk 2
        assertEquals(1, index.previousIndex(6_500))          // 0.4s in, so go back
        assertEquals(0, index.previousIndex(3_800))
        assertNull(index.previousIndex(-1))
    }

    @Test
    fun `previous never runs off the start`() {
        assertNull("already in the first sentence, near its start", index.previousIndex(100))
        assertEquals(0, index.previousIndex(3_000))          // deep into chunk 0: restart it
    }

    @Test
    fun `duration is the end of the last sentence`() {
        assertEquals(19_300L, index.durationMs)
        assertEquals(4, index.size)
    }

    // -- "read from here", resolving a text selection to a sentence ------------

    private fun aligned(ordinal: Int, text: String, href: String = "EPUB/c1.xhtml") =
        SyncChunkEntity(
            bookId = "b1", ordinal = ordinal, text = text,
            startMs = ordinal * 1000L, endMs = (ordinal + 1) * 1000L, chapter = 1,
            resourceHref = href, textHighlight = text, progression = 0.1 * ordinal,
        )

    @Test
    fun `a degenerate one-character chunk never wins a content match by coincidence`() {
        // Regression, confirmed on a real device via a temporary diagnostic
        // snackbar: "Read from here" resolved to a chunk whose own text was a
        // lone "," -- an audiblez sentence-splitting edge case -- instead of the
        // real tapped sentence, because a single character trivially satisfies
        // "the selection contains this chunk's text" for almost any real
        // sentence (nearly every paragraph has a comma somewhere), regardless of
        // where that chunk actually sits in the book.
        val degenerate = ChunkIndex(
            listOf(
                aligned(0, ","),
                aligned(1, "It was a bright, cold day in April."),
                aligned(2, "The clocks were striking thirteen."),
            )
        )
        // Long enough to defeat strategy 1 (no single chunk's own text contains
        // the whole thing) and force strategy 2, where the bug lived.
        val selection = "It was a bright, cold day in April. The clocks were striking thirteen."
        assertEquals(1, degenerate.indexOfSelection("EPUB/c1.xhtml", selection))
    }

    private val selectable = ChunkIndex(
        listOf(
            aligned(0, "The quick brown fox jumps over the lazy dog."),
            aligned(1, "Digital file formats are the foundation of modern computing."),
            aligned(2, "He nodded."),
            aligned(3, "Every document is stored in a specific format.", href = "EPUB/c2.xhtml"),
        )
    )

    @Test
    fun `a few words inside a sentence resolve to that sentence`() {
        assertEquals(1, selectable.indexOfSelection("EPUB/c1.xhtml", "foundation of modern"))
        assertEquals(0, selectable.indexOfSelection("EPUB/c1.xhtml", "lazy dog"))
    }

    @Test
    fun `a selection spanning sentences starts at the first one`() {
        val selection = "He nodded. Something after it that is not mapped."
        assertEquals(2, selectable.indexOfSelection("EPUB/c1.xhtml", selection))
    }

    @Test
    fun `whitespace and punctuation differences from the WebView are tolerated`() {
        // A selection comes back from a WebView with its own spacing, and may carry
        // typographic punctuation where the stored text has ASCII or vice versa.
        assertEquals(1, selectable.indexOfSelection("EPUB/c1.xhtml", "  FOUNDATION   of\nmodern  "))
    }

    @Test
    fun `the resource scopes the search, so the same words in another chapter are not picked`() {
        // "format" appears in both chapters; the href decides which.
        assertEquals(3, selectable.indexOfSelection("EPUB/c2.xhtml", "stored in a specific format"))
        assertNull(selectable.indexOfSelection("EPUB/c1.xhtml", "stored in a specific format"))
    }

    @Test
    fun `a full path or a bare filename both match`() {
        assertEquals(0, selectable.indexOfSelection("c1.xhtml", "lazy dog"))
        assertEquals(0, selectable.indexOfSelection("/EPUB/c1.xhtml", "lazy dog"))
    }

    @Test
    fun `a percent-encoded href from Readium matches a stored href with literal spaces`() {
        // Confirmed live: a Calibre-split epub whose chapter files are named
        // after the book's own title ("The Cuckoo's Egg - v.1.0_split_002.html")
        // stores that href with literal spaces -- EpubTextExtractor reads it
        // straight out of the zip's own entry name, which is never
        // percent-encoded -- but Readium's own Locator.href.toString() is a
        // proper URL and does percent-encode the space as %20. A tap's
        // resourceHref and the aligner's stored one named the same file but
        // never compared equal, hiding "Read from here" on every word.
        val spaced = ChunkIndex(listOf(aligned(0, "A sentence in a spaced file.", href = "EPUB/My Chapter.xhtml")))
        assertEquals(0, spaced.indexOfSelection("EPUB/My%20Chapter.xhtml", "A sentence in a spaced file."))
        assertEquals(0, spaced.indexAtPageOffset("EPUB/My%20Chapter.xhtml", "A sentence in a spaced file.", 5))
    }

    @Test
    fun `an unmatched selection is null rather than a wrong guess`() {
        assertNull(selectable.indexOfSelection("EPUB/c1.xhtml", "this text is nowhere in the book"))
        assertNull(selectable.indexOfSelection("EPUB/c1.xhtml", "   "))
        assertNull(selectable.indexOfSelection(null, ""))
    }

    @Test
    fun `unaligned sentences are never selection targets`() {
        // They have no place on the page, so "read from here" cannot mean them.
        val unaligned = ChunkIndex(
            listOf(
                SyncChunkEntity(
                    bookId = "b1", ordinal = 0, text = "Spoken but never located.",
                    startMs = 0, endMs = 1_000, chapter = 1,
                    // No resourceHref/textHighlight: the aligner could not place it.
                )
            )
        )
        assertNull(unaligned.indexOfSelection(null, "Spoken but never located."))
    }

    @Test
    fun `a selection can be turned straight into a seek position`() {
        val target = selectable.indexOfSelection("EPUB/c1.xhtml", "He nodded")!!
        assertEquals(2_000L, selectable.seekPositionFor(target))
    }

    // -- single tap: an offset into the chapter's rendered text -----------------

    /**
     * Sentences placed in [text] the way the aligner places them: in order, each
     * found after the previous one, with `progression` its start as a fraction of
     * the text.
     */
    private fun alignedIn(text: String, vararg sentences: String, href: String = "EPUB/c1.xhtml"): List<SyncChunkEntity> {
        var cursor = 0
        return sentences.mapIndexed { ordinal, sentence ->
            val at = text.indexOf(sentence, cursor)
            require(at >= 0) { "'$sentence' not in text after $cursor" }
            cursor = at + sentence.length
            aligned(ordinal, sentence, href).copy(progression = at.toDouble() / text.length)
        }
    }

    /** A chapter as the WebView renders it: a heading nobody speaks, then prose
     *  with a line that repeats. */
    private val chapterText =
        "Chapter One\n\n  He nodded. Digital file formats are the foundation of modern computing.\n  He nodded.\n"

    private val tappable = ChunkIndex(
        alignedIn(chapterText, "He nodded.", "Digital file formats are the foundation of modern computing.", "He nodded.")
    )

    private fun ChunkIndex.tap(text: String, offset: Int, href: String = "EPUB/c1.xhtml") =
        indexAtPageOffset(href, text, offset)

    @Test
    fun `a tap resolves to the sentence covering it`() {
        assertEquals(0, tappable.tap(chapterText, chapterText.indexOf("nodded")))
        assertEquals(1, tappable.tap(chapterText, chapterText.indexOf("formats")))
    }

    @Test
    fun `tapping a repeated line picks the occurrence tapped`() {
        assertEquals(2, tappable.tap(chapterText, chapterText.lastIndexOf("He nodded.")))
    }

    @Test
    fun `a far-apart duplicate resolves exactly`() {
        val lines = listOf("What do you mean?") + (1..99).map { "Filler sentence number $it." } + "What do you mean?"
        val text = lines.joinToString(" ")
        val index = ChunkIndex(alignedIn(text, *lines.toTypedArray()))

        assertEquals(0, index.tap(text, text.indexOf("What do you mean?")))
        assertEquals(100, index.tap(text, text.lastIndexOf("What do you mean?")))
    }

    @Test
    fun `one sentence matched out of place does not lose the ones around it`() {
        // Regression, from a real Gutenberg header page: the converter's injected
        // title line, split by a "Mr." bug, left a stray "Jekyll and Mr." matched
        // into the page ahead of the real title. A single forward pass then never
        // found the real title line behind it, and a tap on any later "Mr."
        // resolved to the stray piece.
        val text = "The strange case of Dr. Jekyll and Mr. Hyde. Title: The strange case of Dr. Jekyll and Mr. Hyde."
        val stray = aligned(1, "Jekyll and Mr.").copy(progression = text.indexOf("Jekyll").toDouble() / text.length)
        val real = alignedIn(text, "The strange case of Dr.", "Jekyll and Mr.", "Hyde.", "Title: The strange case of Dr.", "Jekyll and Mr.")
            .mapIndexed { i, chunk -> chunk.copy(ordinal = i + 2) }
        val index = ChunkIndex(listOf(stray) + real)

        assertEquals(1, index.tap(text, 0))                                   // "The strange case of Dr."
        assertEquals(2, index.tap(text, text.indexOf("Jekyll")))             // the real one wins the overlap
        assertEquals(4, index.tap(text, text.indexOf("Title")))
        assertEquals(5, index.tap(text, text.lastIndexOf("Jekyll")))
    }

    @Test
    fun `text nobody speaks resolves to nothing rather than a nearby sentence`() {
        assertNull(tappable.tap(chapterText, chapterText.indexOf("Chapter")))
    }

    @Test
    fun `rendered text differing in whitespace and quote style still resolves`() {
        val stored = "\"Talia,\" she said. Emmy escorted them out."
        val index = ChunkIndex(alignedIn(stored, "\"Talia,\" she said.", "Emmy escorted them out."))
        val rendered = "“Talia,”   she\nsaid. Emmy escorted them out."
        assertEquals(0, index.tap(rendered, rendered.indexOf("Talia")))
        assertEquals(1, index.tap(rendered, rendered.indexOf("escorted")))
    }

    @Test
    fun `the resource scopes a tap`() {
        val index = ChunkIndex(
            listOf(
                aligned(0, "Shared sentence.", href = "EPUB/c1.xhtml").copy(progression = 0.0),
                aligned(1, "Shared sentence.", href = "EPUB/c2.xhtml").copy(progression = 0.0),
            )
        )
        assertEquals(1, index.tap("Shared sentence.", 3, href = "EPUB/c2.xhtml"))
        assertNull(index.tap("Shared sentence.", 3, href = "EPUB/c3.xhtml"))
    }

    @Test
    fun `no page text or no offset resolves to nothing`() {
        assertNull(tappable.tap("", 0))
        assertNull(tappable.tap(chapterText, -1))
    }

    @Test
    fun `the real sample book resolves at every millisecond boundary`() {
        val parsed = SyncFileParser.parse(Fixtures.read("sync_sample_short.json"), "b1")
        val real = ChunkIndex(parsed.chunks)

        // Every sentence must be reachable by its own start and its own midpoint --
        // a contiguous mapping should have no position that lands on the wrong one.
        parsed.chunks.forEachIndexed { expected, chunk ->
            assertEquals("start of chunk $expected", expected, real.indexAt(chunk.startMs))
            assertEquals("middle of chunk $expected", expected, real.indexAt((chunk.startMs + chunk.endMs) / 2))
            assertEquals("just before the end of chunk $expected", expected, real.indexAt(chunk.endMs - 1))
        }
        assertEquals(66_325L, real.durationMs)
    }
}
