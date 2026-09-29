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

        val name = bareResourceName(resourceHref)
        val candidates = chunks.withIndex().filter { (_, chunk) ->
            chunk.isAligned && (name == null || bareResourceName(chunk.resourceHref) == name)
        }
        return matchByContent(candidates, needle)
    }

    /**
     * The three-strategy content match [indexOfSelection] uses.
     *
     * @param needle already normalized and trimmed.
     */
    private fun matchByContent(candidates: List<IndexedValue<SyncChunkEntity>>, needle: String): Int? {
        if (candidates.isEmpty() || needle.isEmpty()) return null

        // A sentence containing the selection: the common case, a few words tapped
        // or dragged over inside one sentence. Confirmed bug, on a real book: a
        // chunk whose own text is degenerately short -- a lone "," from some
        // audiblez sentence-splitting edge case, seen for real -- trivially
        // "contains" almost any short needle, or is "contained by" almost any
        // longer one (the strategy below), and won this fallback purely by
        // coincidence, nowhere near the tap. MIN_PARTIAL_MATCH already encodes
        // "below this many characters a match is more likely a coincidence than
        // intent" for the shrinking-prefix strategy further down; the same
        // reasoning applies here on both sides of `contains`, so both get the
        // same floor.
        if (needle.length >= MIN_PARTIAL_MATCH) {
            candidates.firstOrNull { (_, chunk) ->
                TextNormalizer.normalizeToString(chunk.textHighlight ?: chunk.text).trim().contains(needle)
            }?.let { return it.index }
        }

        // Otherwise the selection spans sentences; start at the first one inside it.
        candidates.firstOrNull { (_, chunk) ->
            val text = TextNormalizer.normalizeToString(chunk.textHighlight ?: chunk.text).trim()
            text.length >= MIN_PARTIAL_MATCH && needle.contains(text)
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
     * The sentence at a character offset in a chapter's rendered text -- a single
     * tap, for "read from here".
     *
     * An exact lookup through a [SentenceMap] of the whole chapter, never a guess:
     * a tap that lands outside every mapped sentence resolves to null, so the menu
     * leaves "Read from here" out rather than playing from somewhere else.
     *
     * @param pageText every text node under the chapter's `<body>`, as the WebView
     *   renders it (see `PageText`).
     * @param offset the tapped word's start within [pageText].
     */
    fun indexAtPageOffset(resourceHref: String?, pageText: String, offset: Int): Int? {
        if (pageText.isEmpty() || offset < 0) return null
        val name = bareResourceName(resourceHref) ?: return null
        return sentenceMap(name, pageText).sentenceAt(offset)
    }

    /** The last map built, reused while the reader keeps tapping in one chapter. */
    private var cachedMap: Triple<String, String, SentenceMap>? = null

    private fun sentenceMap(name: String, pageText: String): SentenceMap {
        cachedMap?.let { (cachedName, cachedText, map) ->
            if (cachedName == name && cachedText == pageText) return map
        }
        val candidates = chunks.withIndex().filter { (_, chunk) ->
            chunk.isAligned && bareResourceName(chunk.resourceHref) == name
        }
        return SentenceMap(pageText, candidates).also { cachedMap = Triple(name, pageText, it) }
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
    }
}
