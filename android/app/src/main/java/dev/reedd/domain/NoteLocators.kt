package dev.reedd.domain

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Builds a [Locator] for a note taken on a tapped word.
 *
 * A selection already comes with a Locator for free ([org.readium.r2.
 * navigator.Selection.locator], resolved by Readium itself), but a tap only
 * ever gives [dev.reedd.ui.reader.TapTextResolver] a word plus the block of
 * text it sits in -- nothing has needed a real Locator for a tap before now
 * (read-along matching works off the raw strings via [ChunkIndex.indexOfTap]
 * and never builds one). Shaped exactly like [dev.reedd.ui.reader.
 * ReadAlongLocators.locator] -- a text-quote anchor scoped to the resource,
 * with `before`/`after` context to disambiguate a repeated word.
 */
object NoteLocators {

    /** Characters of context either side of the word, enough to disambiguate
     *  a common word without ballooning every note's stored locator. */
    private const val CONTEXT_CHARS = 40

    fun tapLocator(
        publication: Publication,
        resourceHref: String,
        blockText: String,
        offset: Int,
        word: String,
        progression: Double?,
    ): Locator? {
        val link = resolveLink(publication, resourceHref) ?: return null
        val (before, after) = surroundingContext(blockText, offset, word)

        return Locator(
            href = link,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(progression = progression),
            text = Locator.Text(before = before, highlight = word, after = after),
        )
    }

    /**
     * The `before`/`after` text either side of [word] at [offset] in
     * [blockText], clamped to [CONTEXT_CHARS] and to the block's own bounds
     * -- split out from [tapLocator] so the boundary math (word at the very
     * start/end of the block) is testable without a [Publication].
     */
    internal fun surroundingContext(blockText: String, offset: Int, word: String): Pair<String, String> {
        val before = blockText.substring((offset - CONTEXT_CHARS).coerceAtLeast(0), offset.coerceIn(0, blockText.length))
        val afterStart = (offset + word.length).coerceIn(0, blockText.length)
        val after = blockText.substring(afterStart, (afterStart + CONTEXT_CHARS).coerceAtMost(blockText.length))
        return before to after
    }
}
