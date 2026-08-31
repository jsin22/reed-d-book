package dev.reedd.data.align

/**
 * A normalised projection of a string that can be mapped back to the original.
 *
 * Matching has to be forgiving — audiblez' sentence text differs from the epub's
 * in whitespace, punctuation style and a trailing period — but the text handed to
 * Readium has to be the **original** substring, because its JavaScript searches
 * the rendered DOM for it. So normalisation keeps an index for every normalised
 * character saying where it came from.
 */
class NormalizedText(
    val text: String,
    /** For each index in [text], the index in the original string it came from. */
    private val origin: IntArray,
    private val originalLength: Int,
) {
    val length: Int get() = text.length

    /** Original range for a match at `[start, end)` in the normalised text. */
    fun originalRange(start: Int, end: Int): IntRange {
        require(start in 0..end && end <= text.length) { "bad range $start..$end" }
        if (start == end) return IntRange.EMPTY
        val from = origin[start]
        // The caller trims matches so the last character is never whitespace,
        // which is what makes "+1" the right end: a collapsed whitespace run is
        // the only case where one normalised char spans several original ones.
        val to = (origin[end - 1] + 1).coerceAtMost(originalLength)
        return from until to
    }
}

/**
 * Folds the differences that stop an epub sentence matching audiblez' copy of it.
 *
 * Whitespace is collapsed because the epub's is however the author indented their
 * XHTML; quotes and dashes are folded because typographic and ASCII forms are used
 * interchangeably; case is folded because it costs nothing and a whole sentence is
 * far too long to collide by accident.
 */
object TextNormalizer {

    fun normalize(input: String): NormalizedText {
        val out = StringBuilder(input.length)
        // Regression: sized `input.length + 4` originally, on the assumption
        // that only a handful of characters ever fold to more than one output
        // character. A real chapter (Hidden Pictures, chapter 10) had 198
        // ellipsis characters -- each '…' -> "..." is a single input char
        // producing three output chars -- which overran that buffer by a wide
        // margin. The overflow write in the whitespace branch below was
        // completely unguarded, so it threw ArrayIndexOutOfBoundsException
        // uncaught, all the way up through DownloadWorker, which left
        // downloadState stuck at RUNNING forever and caused the app to
        // re-download the same book in an infinite loop (ConversionWatcher's
        // awaitingDownload() kept finding a non-terminal state to resume).
        // `fold()` never produces more than 3 output characters for one input
        // character, so `input.length * 3` is a real, provable upper bound on
        // how large `out`/`origin` can ever grow -- not a bigger guess, a
        // ceiling that cannot be exceeded no matter what the text contains.
        val origin = IntArray(input.length * 3)
        var lastWasSpace = true // leading whitespace is dropped

        for (i in input.indices) {
            val c = input[i]
            if (c == SOFT_HYPHEN) continue // invisible; audiblez never speaks it

            if (c.isWhitespace() || c == NBSP) {
                if (!lastWasSpace) {
                    origin[out.length] = i
                    out.append(' ')
                    lastWasSpace = true
                }
                continue
            }
            lastWasSpace = false
            val folded = fold(c)
            for (f in folded) {
                origin[out.length] = i
                out.append(f)
            }
        }
        // A trailing collapsed space would never be part of a match.
        while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)

        return NormalizedText(out.toString(), origin, input.length)
    }

    /** Normalises without needing the mapping back, for the needle side. */
    fun normalizeToString(input: String): String = normalize(input).text

    private fun fold(c: Char): String = when (c) {
        '‘', '’', '‛', '′' -> "'"
        '“', '”', '‟', '″' -> "\""
        '–', '—', '‒', '―', '−' -> "-"
        '…' -> "..."
        ' ' -> " "
        else -> c.lowercaseChar().toString()
    }

    private const val SOFT_HYPHEN = '­'
    private const val NBSP = ' '
}
