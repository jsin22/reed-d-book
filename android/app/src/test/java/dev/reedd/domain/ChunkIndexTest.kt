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
