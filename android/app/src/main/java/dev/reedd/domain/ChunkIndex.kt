package dev.reedd.domain

import dev.reedd.data.align.NormalizedText
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
        return matchByContent(candidates, needle)
    }

    /**
     * The three-strategy content match [indexOfSelection] and [indexOfTap]'s
     * fallback both need, shared so the latter is not a weaker reimplementation
     * of the former over a narrower candidate list.
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
     * The sentence at a character offset inside a block of page text — a single tap.
     *
     * More precise than [indexOfSelection], and preferred when the tap position is
     * known: it finds where each sentence *sits* in the block and picks the one whose
     * span actually covers the tap, so tapping the second of two identical sentences
     * in a paragraph selects the second one.
     *
     * @param blockText the text content of the tapped block element.
     * @param offset the tap's character offset within [blockText].
     * @param readingProgression where the reader is *looking*, as a 0.0-1.0
     *   fraction through the current resource -- Readium's own
     *   `Locator.Locations.progression` at tap time, **not** anything to do with
     *   audio playback. That distinction matters: "Read from here" exists
     *   specifically to jump listening to somewhere other than where it currently
     *   is, so anchoring to playback position (tried first, reverted -- see
     *   BUGS.md) made the primary match latch onto whatever was nearest
     *   *playback*, nowhere near the tap, and "completely broken, pages jump
     *   around" is exactly what that looks like. Reading position -- what is
     *   actually on the screen the tap landed on -- is always near the tap by
     *   construction. Used to prefer nearby candidates first: see the walk below
     *   for why that matters at all (a short, common line recurring many times
     *   in one chapter).
     */
    fun indexOfTap(resourceHref: String?, blockText: String, offset: Int, readingProgression: Double? = null): Int? {
        if (blockText.isEmpty()) return null
        val name = resourceHref?.substringAfterLast('/')
        val candidates = chunks.withIndex().filter { (_, chunk) ->
            chunk.isAligned && (name == null || chunk.resourceHref?.substringAfterLast('/') == name)
        }
        if (candidates.isEmpty()) return null

        // Readium's progression is a fraction of this resource; the candidate
        // list is already scoped to it, so the fraction converts straight into
        // an approximate position among *these* candidates -- no absolute chunk
        // index needed.
        val anchorIndex = readingProgression?.let { p ->
            candidates.getOrNull((p * candidates.size).toInt().coerceIn(0, candidates.size - 1))?.index
        }

        // Walk sentences and block text together, advancing a cursor -- the same
        // approach the aligner uses, and for the same reason. Matching each sentence
        // independently cannot tell two *identical* sentences apart ("He nodded."
        // twice in one paragraph); only their order can, so the nth occurrence is
        // assigned to the nth sentence *among the candidates given* -- see below for
        // why that candidate list is not always every chunk in the chapter.
        //
        // In *normalized* space, not raw: [chunk.textHighlight] was extracted from
        // the epub at conversion time with jsoup, [blockText] comes from the
        // WebView's rendered DOM at tap time with Blink -- two different parsers on
        // the same markup usually agree byte-for-byte but not always (a differently
        // decoded entity, a stray smart quote), and a literal indexOf broke on
        // exactly that before, falling through to the position-blind fallback below
        // for text that in fact matched perfectly well once folded.
        val normalizedBlock = TextNormalizer.normalize(blockText)

        // Try a window around the reader's approximate position first. This is the
        // actual fix for a real, confirmed case: this walk assigns a matching
        // occurrence to whichever candidate it reaches *first*, in chapter order --
        // with every chunk in the chapter in play, an identical short line pages
        // before the reader's own paragraph can be reached first and "steal" the
        // match meant for the one actually on screen, even though both the primary
        // match and its normalization are working exactly as intended. Restricting
        // to nearby candidates first removes every far-away duplicate from
        // contention before that can happen; the unrestricted retry below exists
        // for when nothing nearby matches at all (a stale anchor, or none given).
        if (anchorIndex != null) {
            val nearby = candidates.filter { (index, _) -> kotlin.math.abs(index - anchorIndex) <= ANCHOR_WINDOW }
            walkForTap(nearby, normalizedBlock, offset).first?.let { return it }
        }

        val (result, lastMatched) = walkForTap(candidates, normalizedBlock, offset)
        result?.let { return it }

        // Nothing covered the tap exactly, even normalized and unrestricted -- fall
        // back to a window around it, using the same three-strategy content match
        // indexOfSelection uses (not a weaker one-strategy check), but tried first
        // against only what is at-or-after wherever the walk above last landed: for
        // a phrase that repeats, the nearest occurrence is what the reader tapped,
        // and the *first* one anywhere in the chapter can be pages back.
        val from = (offset - WINDOW / 2).coerceIn(0, blockText.length)
        val to = (offset + WINDOW / 2).coerceIn(from, blockText.length)
        val needle = TextNormalizer.normalizeToString(blockText.substring(from, to)).trim()
        if (lastMatched >= 0) {
            matchByContent(candidates.filter { (index, _) -> index >= lastMatched }, needle)?.let { return it }
        }

        // Still nothing nearby -- last resort, unrestricted, same as before.
        return matchByContent(candidates, needle)
    }

    /**
     * One pass of the cursor walk described in [indexOfTap], over whichever
     * candidate list it is given.
     *
     * @return the matched chunk index (or null if none covered the tap), and the
     *   last candidate the walk matched regardless -- [indexOfTap]'s own fallback
     *   needs that even when this pass found no exact covering match.
     */
    private fun walkForTap(
        candidates: List<IndexedValue<SyncChunkEntity>>,
        normalizedBlock: NormalizedText,
        offset: Int,
    ): Pair<Int?, Int> {
        var cursor = 0
        var lastMatched = -1
        for ((index, chunk) in candidates) {
            val highlight = chunk.textHighlight?.takeIf { it.isNotEmpty() } ?: continue
            val needle = TextNormalizer.normalizeToString(highlight)
            if (needle.isEmpty()) continue
            // Not in this block at all: a sentence from another paragraph. Skipped
            // without moving the cursor.
            val at = normalizedBlock.text.indexOf(needle, cursor).takeIf { it >= 0 } ?: continue
            val end = at + needle.length
            val span = normalizedBlock.originalRange(at, end)
            if (offset in span.first..(span.last + 1)) return index to index
            cursor = end
            lastMatched = index
        }
        return null to lastMatched
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

        /**
         * Chunks either side of [indexOfTap]'s anchor tried before falling back to
         * the whole chapter. A real book (a 396-minute, 9,724-chunk novel) measured
         * short duplicated lines as close as 11 chunks apart and as far as several
         * hundred within a single chapter -- this has to be tight enough to exclude
         * a duplicate a genuine few pages away, while wide enough that a reader who
         * has paused and paged ahead of the audio still taps successfully. Not
         * derived from a real chunks-per-page measurement (pagination depends on
         * font size and screen, which this class has no way to know); a reasonable,
         * tunable guess.
         */
        private const val ANCHOR_WINDOW = 40
    }
}
