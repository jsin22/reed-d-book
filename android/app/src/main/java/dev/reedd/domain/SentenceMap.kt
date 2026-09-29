package dev.reedd.domain

import dev.reedd.data.align.TextNormalizer
import dev.reedd.data.db.SyncChunkEntity

/**
 * Where each aligned sentence sits in one chapter's rendered text, so a tap's
 * character position resolves to exactly one sentence -- a lookup, not a search.
 *
 * Built with the same in-order cursor walk [dev.reedd.data.align.ChunkAligner]
 * used to align the chapter in the first place, over the whole chapter rather
 * than the one paragraph a tap landed in. Reading order is what tells two
 * identical lines ("He nodded." twice) apart, and it only can when the walk sees
 * every sentence before the tapped one. Each needle is the sentence's stored
 * `textHighlight`, which is the exact page text the aligner matched.
 *
 * @param pageText the chapter's text as the WebView renders it: every text node
 *   under `<body>` in document order, the same thing jsoup's `wholeText()` gave
 *   the aligner (see `PageText`).
 * @param chunks this chapter's sentences in playback order, with their indexes
 *   in the whole book.
 */
class SentenceMap(pageText: String, chunks: List<IndexedValue<SyncChunkEntity>>) {

    private val starts: IntArray
    private val ends: IntArray
    private val indexes: IntArray

    init {
        val haystack = TextNormalizer.normalize(pageText)
        val s = ArrayList<Int>()
        val e = ArrayList<Int>()
        val ix = ArrayList<Int>()
        var cursor = 0
        for ((index, chunk) in chunks) {
            val needle = chunk.textHighlight?.let { TextNormalizer.normalizeToString(it).trim() }
            if (needle.isNullOrEmpty()) continue
            // Forward only: a sentence not found after the previous one is left
            // unmapped rather than matched somewhere earlier in the chapter.
            val at = haystack.text.indexOf(needle, cursor)
            if (at < 0) continue
            val original = haystack.originalRange(at, at + needle.length)
            s += original.first
            e += original.last + 1
            ix += index
            cursor = at + needle.length
        }
        starts = s.toIntArray()
        ends = e.toIntArray()
        indexes = ix.toIntArray()
    }

    val size: Int get() = indexes.size

    /** The book-wide index of the sentence covering [offset], or null if none does. */
    fun sentenceAt(offset: Int): Int? {
        // Last sentence starting at or before the offset; starts ascend because
        // the walk only moves forward.
        var low = 0
        var high = starts.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= offset) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (found >= 0 && offset < ends[found]) indexes[found] else null
    }
}
