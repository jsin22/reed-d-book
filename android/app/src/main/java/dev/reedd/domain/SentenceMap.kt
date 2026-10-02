package dev.reedd.domain

import dev.reedd.data.align.NormalizedText
import dev.reedd.data.align.TextNormalizer
import dev.reedd.data.db.SyncChunkEntity
import kotlin.math.abs

/**
 * Where each aligned sentence sits in one chapter's rendered text, so a tap's
 * character position resolves to exactly one sentence -- a lookup, not a search.
 *
 * Each sentence is placed on its own, at the occurrence of its stored
 * `textHighlight` (the exact page text the aligner matched) nearest to where the
 * aligner recorded it: `progression` is that match's start as a fraction of the
 * chapter's text. That is the position the read-along highlight shows, it tells
 * repeated lines ("He nodded." twice) apart, and one stray sentence cannot shift
 * the rest -- an earlier single forward pass lost a whole Gutenberg header page
 * to one title fragment that matched ahead of where it belonged.
 *
 * @param pageText the chapter's text as the WebView renders it: every text node
 *   under `<body>` in document order (see `PageText`). This differs from the
 *   aligner's jsoup text only slightly (jsoup adds a newline per `<br>`), so a
 *   recorded fraction of one lands within a few characters in the other.
 * @param chunks this chapter's sentences in playback order, with their indexes
 *   in the whole book.
 */
class SentenceMap(pageText: String, chunks: List<IndexedValue<SyncChunkEntity>>) {

    private class Placed(val start: Int, val end: Int, val index: Int)

    private val placed: List<Placed>

    init {
        val haystack = TextNormalizer.normalize(pageText)
        val out = ArrayList<Placed>()
        var cursor = 0
        for ((index, chunk) in chunks) {
            val needle = chunk.textHighlight?.let { TextNormalizer.normalizeToString(it).trim() }
            if (needle.isNullOrEmpty()) continue
            val expected = chunk.progression?.let { (it * pageText.length).toInt() }
            // Without a recorded position, fall back to reading order.
            val range = if (expected != null) {
                nearest(haystack, needle, expected)
            } else {
                val at = haystack.text.indexOf(needle, cursor)
                if (at < 0) null else haystack.originalRange(at, at + needle.length).also { cursor = at + needle.length }
            }
            range ?: continue
            out += Placed(range.first, range.last + 1, index)
        }
        placed = out
    }

    val size: Int get() = placed.size

    /**
     * The book-wide index of the sentence covering [offset], or null if none does.
     * Should two cover it, the innermost (latest start) wins, then the later
     * sentence: the only realistic overlap is the converter's own injected title
     * line matching the book's real title, and the real one is the later.
     */
    fun sentenceAt(offset: Int): Int? =
        placed.filter { offset >= it.start && offset < it.end }
            .maxWithOrNull(compareBy<Placed> { it.start }.thenBy { it.index })
            ?.index

    private fun nearest(haystack: NormalizedText, needle: String, expected: Int): IntRange? {
        var best: IntRange? = null
        var bestDistance = Int.MAX_VALUE
        var at = haystack.text.indexOf(needle)
        while (at >= 0) {
            val range = haystack.originalRange(at, at + needle.length)
            val distance = abs(range.first - expected)
            if (distance < bestDistance) {
                best = range
                bestDistance = distance
            } else if (range.first > expected) {
                break // only moving further away from here on
            }
            at = haystack.text.indexOf(needle, at + 1)
        }
        return best
    }
}
