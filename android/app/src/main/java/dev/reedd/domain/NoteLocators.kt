package dev.reedd.domain

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Builds a [Locator] for a note -- for a tapped word ([tapLocator]) or a
 * drag-extended selection ([extendedLocator]). Neither Readium's `Publication`
 * nor its DOM has any other reason to hand one of these back on its own:
 * read-along matching works off raw strings via [ChunkIndex.indexOfTap]/
 * [ChunkIndex.indexOfSelection] and never needs a Locator either. Shaped
 * exactly like [dev.reedd.ui.reader.ReadAlongLocators.locator] -- a
 * text-quote anchor scoped to the resource, with `before`/`after` context to
 * disambiguate a repeated occurrence.
 */
object NoteLocators {

    /** Characters of context either side of the word, enough to disambiguate
     *  a common word without ballooning every note's stored locator. Not
     *  private: also spliced into SelectionTextResolver's JS, so a
     *  drag-extended selection's before/after context uses the same budget. */
    internal const val CONTEXT_CHARS = 40

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
     * Builds a [Locator] for a drag-extended selection -- unlike [tapLocator],
     * `before`/`after` arrive already computed (by [dev.reedd.ui.reader.
     * SelectionTextResolver]'s JS, at the selection's own two endpoints)
     * rather than needing to be sliced out of a single block's text here.
     */
    fun extendedLocator(
        publication: Publication,
        resourceHref: String,
        text: String,
        before: String,
        after: String,
        progression: Double?,
    ): Locator? {
        val link = resolveLink(publication, resourceHref) ?: return null
        return Locator(
            href = link,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(progression = progression),
            text = Locator.Text(before = before, highlight = text, after = after),
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
