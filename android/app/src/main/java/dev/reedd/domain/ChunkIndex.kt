package dev.reedd.domain

import dev.reedd.data.align.TextNormalizer
import dev.reedd.data.db.SyncChunkEntity

/**
 * The read-along mapping, in memory, searchable by playback position.
 *
 * Loaded once when a book is opened rather than queried per tick. The position is
 * checked several times a second while audio plays; a database round trip at that
 * rate would be indefensible when the whole mapping is a few megabytes even for a
 * novel (tens of thousands of sentences).
 *
 * Lookups are a binary search, so they cost nothing and — unlike walking forward
 * from the last known chunk — they are correct after an arbitrary seek, a scrub
 * backwards, or a jump to a chapter.
 *
 * @param offsetMs added to the player's position before searching. The `.m4b`
 *   carries a few tens of milliseconds of AAC priming that the timestamps do not
 *   describe (`audiblez/SYNC.md`), so this is where that is corrected.
 */
class ChunkIndex(
    chunks: List<SyncChunkEntity>,
    val offsetMs: Long = 0,
) {
    /** Playback order, which the query guarantees. */
    val chunks: List<SyncChunkEntity> = chunks

    private val starts: LongArray = LongArray(chunks.size) { chunks[it].startMs }

    val size: Int get() = starts.size
    val isEmpty: Boolean get() = starts.isEmpty()

    /** Total mapped length, i.e. the end of the last sentence. */
    val durationMs: Long get() = chunks.lastOrNull()?.endMs ?: 0

    /**
     * Index of the sentence covering [positionMs], or -1 if it is before the first.
     *
     * A position exactly on a boundary belongs to the sentence that *starts* there:
     * the sync file guarantees `chunks[n].end == chunks[n+1].start`, so the
     * alternative would highlight the sentence that has just finished.
     *
     * Past the end of the audio the last sentence stays selected rather than the
     * highlight vanishing, which is what a listener expects at the end of a book.
     */
    fun indexAt(positionMs: Long): Int {
        if (starts.isEmpty()) return -1
        val target = positionMs + offsetMs
        if (target < starts[0]) return -1

        var low = 0
        var high = starts.size - 1
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= target) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    fun chunkAt(positionMs: Long): SyncChunkEntity? = indexAt(positionMs).takeIf { it >= 0 }?.let { chunks[it] }

    fun chunkAtIndex(index: Int): SyncChunkEntity? = chunks.getOrNull(index)

    /**
     * Where to seek to play a sentence from its start.
     *
     * The offset is subtracted back out: it exists to correct the *lookup*, and
     * applying it to a seek as well would shift playback by it.
     */
    fun seekPositionFor(index: Int): Long? =
        chunks.getOrNull(index)?.let { (it.startMs - offsetMs).coerceAtLeast(0) }

    /** The sentence after the one playing, for a "next sentence" control. */
    fun nextIndex(positionMs: Long): Int? =
        (indexAt(positionMs) + 1).takeIf { it in chunks.indices }

    /**
     * The previous sentence — or the start of the current one if playback is
     * already well into it, which is what "back" means on a media control.
     */
    fun previousIndex(positionMs: Long, restartThresholdMs: Long = 1_500): Int? {
        val current = indexAt(positionMs)
        if (current < 0) return null
        val chunk = chunks[current]
        val into = positionMs + offsetMs - chunk.startMs
        return if (into > restartThresholdMs) current else (current - 1).takeIf { it >= 0 }
    }

    /**
     * The sentence a piece of selected text belongs to, for "read from here".
     *
     * Text rather than coordinates, because that is what a text selection gives:
     * Readium reports the selected string and the resource it came from.
     *
     * Both directions are tried, because a selection is rarely exactly one sentence:
     *
     *  * a few words *inside* a sentence — the chunk contains the selection;
     *  * a whole paragraph spanning several sentences — the selection contains the
     *    chunk, and the earliest such sentence is the one to start from.
     *
     * Comparison is normalised (whitespace collapsed, quotes and dashes folded, case
     * folded) because the selection comes back from a WebView and will not match the
     * stored text byte for byte.
     *
     * @param resourceHref restricts the search to one resource when known, so the
     *   same sentence appearing in two chapters cannot be confused.
     * @return the index into [chunks], or null if nothing matched.
     */
    fun indexOfSelection(resourceHref: String?, selectedText: String): Int? {
        val needle = TextNormalizer.normalizeToString(selectedText).trim()
        if (needle.isEmpty()) return null

        val name = resourceHref?.substringAfterLast('/')
        val candidates = chunks.withIndex().filter { (_, chunk) ->
            chunk.isAligned && (name == null || chunk.resourceHref?.substringAfterLast('/') == name)
        }
        if (candidates.isEmpty()) return null

        // A sentence containing the selection: the common case, a few words tapped
        // or dragged over inside one sentence.
        candidates.firstOrNull { (_, chunk) ->
            TextNormalizer.normalizeToString(chunk.textHighlight ?: chunk.text).trim().contains(needle)
        }?.let { return it.index }

        // Otherwise the selection spans sentences; start at the first one inside it.
        candidates.firstOrNull { (_, chunk) ->
            val text = TextNormalizer.normalizeToString(chunk.textHighlight ?: chunk.text).trim()
            text.isNotEmpty() && needle.contains(text)
        }?.let { return it.index }

        // Last resort: the longest leading fragment of the selection that any
        // sentence contains. Catches a selection that starts mid-sentence and runs
        // into the next one.
        for (length in needle.length downTo MIN_PARTIAL_MATCH) {
            val prefix = needle.substring(0, length)
            candidates.firstOrNull { (_, chunk) ->
                TextNormalizer.normalizeToString(chunk.textHighlight ?: chunk.text).contains(prefix)
            }?.let { return it.index }
        }
        return null
    }

    /**
     * The sentence at a character offset inside a block of page text — a single tap.
     *
     * More precise than [indexOfSelection], and preferred when the tap position is
     * known: it finds where each sentence *sits* in the block and picks the one whose
     * span actually covers the tap, so tapping the second of two identical sentences
     * in a paragraph selects the second one.
     *
     * @param blockText the text content of the tapped block element.
     * @param offset the tap's character offset within [blockText].
     */
    fun indexOfTap(resourceHref: String?, blockText: String, offset: Int): Int? {
        if (blockText.isEmpty()) return null
        val name = resourceHref?.substringAfterLast('/')
        val candidates = chunks.withIndex().filter { (_, chunk) ->
            chunk.isAligned && (name == null || chunk.resourceHref?.substringAfterLast('/') == name)
        }

        // Walk sentences and block text together, advancing a cursor -- the same
        // approach the aligner uses, and for the same reason. Matching each sentence
        // independently cannot tell two *identical* sentences apart ("He nodded."
        // twice in one paragraph); only their order can, so the nth occurrence is
        // assigned to the nth sentence.
        var cursor = 0
        for ((index, chunk) in candidates) {
            val highlight = chunk.textHighlight?.takeIf { it.isNotEmpty() } ?: continue
            // Not in this block at all: a sentence from another paragraph. Skipped
            // without moving the cursor.
            val at = blockText.indexOf(highlight, cursor).takeIf { it >= 0 } ?: continue
            val end = at + highlight.length
            if (offset in at..end) return index
            cursor = end
        }

        // The block's text did not match verbatim -- different whitespace from the
        // WebView, most likely. Fall back to a window around the tap, kept
        // deliberately tight so it stays positional: a wide window would just find
        // the first sentence in the paragraph rather than the one tapped.
        val from = (offset - WINDOW / 2).coerceIn(0, blockText.length)
        val to = (offset + WINDOW / 2).coerceIn(from, blockText.length)
        return indexOfSelection(resourceHref, blockText.substring(from, to))
    }

    /** A copy with a different timing offset; the mapping itself is unchanged. */
    fun withOffset(offsetMs: Long): ChunkIndex =
        if (offsetMs == this.offsetMs) this else ChunkIndex(chunks, offsetMs)

    companion object {
        val EMPTY = ChunkIndex(emptyList())

        /**
         * Below this many characters a partial match is more likely to be a
         * coincidence than the sentence the reader meant.
         */
        private const val MIN_PARTIAL_MATCH = 8

        /**
         * Characters taken around a tap when exact matching fails: ±40. Tight on
         * purpose — widen it and the fallback stops being about *where* the tap was.
         */
        private const val WINDOW = 80
    }
}
