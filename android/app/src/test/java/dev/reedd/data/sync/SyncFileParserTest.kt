package dev.reedd.data.sync

import dev.reedd.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsed against the real sync file produced by this repo's own audiblez fork for
 * `sample-short.epub`, not a hand-written approximation.
 */
class SyncFileParserTest {

    private val parsed by lazy {
        SyncFileParser.parse(Fixtures.read("sync_sample_short.json"), bookId = "b1")
    }

    @Test
    fun `the real sample file parses into chunks and chapters`() {
        assertEquals(12, parsed.chunks.size)
        assertEquals(2, parsed.chapters.size)
        assertEquals(66_325L, parsed.durationMs)
        assertEquals("sample-short.m4b", parsed.audioFile)
        assertFalse(parsed.isNewerFormat)
    }

    @Test
    fun `seconds become milliseconds`() {
        // The file says 3.725 s; the player will report 3725 ms.
        assertEquals(0L, parsed.chunks[0].startMs)
        assertEquals(3_725L, parsed.chunks[0].endMs)
        assertEquals(3_725L, parsed.chunks[1].startMs)
        assertEquals(6_100L, parsed.chunks[1].endMs)
    }

    @Test
    fun `the mapping stays contiguous after rounding`() {
        // audiblez guarantees chunks[n].end == chunks[n+1].start. Rounding each
        // boundary independently has to preserve that, or highlighting would show
        // a gap or flicker between two sentences.
        parsed.chunks.zipWithNext().forEach { (a, b) ->
            assertEquals("chunk ${a.ordinal} -> ${b.ordinal}", a.endMs, b.startMs)
        }
        assertEquals(parsed.durationMs, parsed.chunks.last().endMs)
    }

    @Test
    fun `chunks are numbered in playback order`() {
        assertEquals(parsed.chunks.indices.toList(), parsed.chunks.map { it.ordinal })
    }

    @Test
    fun `chunk text is kept verbatim, leading whitespace included`() {
        // It is what makes the text matchable against the epub; normalising here
        // would break that.
        assertTrue(parsed.chunks[1].text.startsWith("\n\n"))
        // The first chunk is audiblez' injected "<title> - <author>." line, which
        // appears in no epub. The app must not expect it to match anything.
        assertTrue(parsed.chunks[0].text.contains("A Brief Guide to Digital Formats"))
    }

    @Test
    fun `chapters carry their source resource and bounds`() {
        val first = parsed.chapters.first()
        assertEquals(1, first.chapterIndex)
        assertEquals("understanding_digital_formats.xhtml", first.source)
        assertEquals(0L, first.startMs)
        assertEquals(61_500L, first.endMs)
        assertEquals(66_325L, parsed.chapters.last().endMs)
    }

    @Test
    fun `every chunk belongs to a chapter that exists`() {
        val indices = parsed.chapters.map { it.chapterIndex }.toSet()
        assertTrue(parsed.chunks.all { it.chapter in indices })
    }

    @Test
    fun `the book id is stamped on every row`() {
        assertTrue(parsed.chunks.all { it.bookId == "b1" })
        assertTrue(parsed.chapters.all { it.bookId == "b1" })
    }

    @Test
    fun `a file with no chunks is rejected`() {
        // Nothing could ever be highlighted, so the book must not be called ready.
        try {
            SyncFileParser.parse("""{"version":1,"duration":10.0,"chunks":[]}""", "b1")
            throw AssertionError("expected SyncFileError")
        } catch (e: SyncFileError) {
            assertTrue(e.message!!.contains("no chunks"))
        }
    }

    @Test
    fun `malformed json is a typed failure`() {
        try {
            SyncFileParser.parse("not json at all", "b1")
            throw AssertionError("expected SyncFileError")
        } catch (e: SyncFileError) {
            assertTrue(e.message!!.contains("not valid JSON"))
        }
    }

    @Test
    fun `unknown fields are tolerated and a newer version is flagged, not refused`() {
        val newer = """
            {"version":2,"duration":1.5,"word_level":true,
             "chunks":[{"text":"hi","start":0.0,"end":1.5,"chapter":1,"words":[]}]}
        """.trimIndent()

        val result = SyncFileParser.parse(newer, "b1")

        assertTrue(result.isNewerFormat)
        assertEquals(1, result.chunks.size)
        assertEquals(1_500L, result.chunks.single().endMs)
    }

    @Test
    fun `a missing chapter number defaults to the first chapter`() {
        val result = SyncFileParser.parse(
            """{"version":1,"duration":1.0,"chunks":[{"text":"x","start":0.0,"end":1.0}]}""",
            "b1",
        )
        assertEquals(1, result.chunks.single().chapter)
    }

    @Test
    fun `sub-millisecond values round rather than truncate`() {
        val result = SyncFileParser.parse(
            """{"version":1,"duration":0.0016,"chunks":[{"text":"x","start":0.0015,"end":0.0016}]}""",
            "b1",
        )
        assertEquals(2L, result.chunks.single().startMs)
        assertEquals(2L, result.chunks.single().endMs)
    }
}
