package dev.reedd.ui.reader

import dev.reedd.data.db.SyncChunkEntity
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Turns an aligned sentence into something Readium can highlight.
 *
 * The shape matters, because it is what Readium's JavaScript consumes. Given a
 * `text.highlight`, it builds a text-quote anchor scoped to the resource and
 * resolves it to a DOM range, using `before`/`after` as the prefix and suffix that
 * disambiguate a repeated sentence. `progression` is included as well so `go()` has
 * a numeric position to fall back on.
 *
 * The href is matched against the publication's own reading order rather than
 * trusted: the aligner recorded a zip entry name, and the publication addresses its
 * resources by its own URLs.
 */
object ReadAlongLocators {

    const val DECORATION_GROUP = "readalong"
    private const val DECORATION_ID = "readalong-current"

    /**
     * A second group of invisible but *activable* decorations, one per sentence on
     * the current page, so tapping a sentence can start playback there.
     *
     * This is how the tap is turned into a sentence index. Readium reports which
     * decoration was activated, and the index is encoded in its id, so there is no
     * guessing from tap coordinates and no reverse text matching.
     */
    const val TAP_GROUP = "readalong-taps"
    private const val TAP_ID_PREFIX = "readalong-tap-"

    fun tapDecorationId(chunkIndex: Int): String = "$TAP_ID_PREFIX$chunkIndex"

    fun chunkIndexFromTapId(id: String): Int? =
        id.removePrefix(TAP_ID_PREFIX).takeIf { it != id }?.toIntOrNull()

    fun locator(publication: Publication, chunk: SyncChunkEntity): Locator? {
        val href = chunk.resourceHref ?: return null
        val highlight = chunk.textHighlight ?: return null
        val link = resolveLink(publication, href) ?: return null

        return Locator(
            href = link,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(progression = chunk.progression),
            text = Locator.Text(
                before = chunk.textBefore,
                highlight = highlight,
                after = chunk.textAfter,
            ),
        )
    }

    /**
     * A single decoration, reused by id.
     *
     * Only one sentence is ever highlighted, so replacing the same id is what makes
     * the previous highlight disappear -- there is no separate "clear" step.
     */
    fun decoration(locator: Locator, tint: Int): Decoration =
        Decoration(
            id = DECORATION_ID,
            locator = locator,
            style = Decoration.Style.Highlight(tint = tint, isActive = false),
        )

    /**
     * Tappable decorations for every aligned sentence in one resource.
     *
     * Transparent, because the visible highlight is the other group's job; these
     * exist only to catch a tap. Scoped to a single resource to keep the batch
     * small -- a novel's worth of decorations in one call would be wasteful when
     * only one chapter can be on screen.
     */
    fun tapDecorations(
        publication: Publication,
        chunks: List<SyncChunkEntity>,
        resourceHref: String,
    ): List<Decoration> = chunks
        .withIndex()
        .filter { (_, chunk) -> chunk.isAligned && chunk.resourceHref == resourceHref }
        .mapNotNull { (index, chunk) ->
            locator(publication, chunk)?.let { locator ->
                Decoration(
                    id = tapDecorationId(index),
                    locator = locator,
                    style = Decoration.Style.Highlight(tint = TRANSPARENT, isActive = true),
                )
            }
        }

    private const val TRANSPARENT = 0

    private fun resolveLink(publication: Publication, href: String): Url? {
        val name = href.substringAfterLast('/')
        val match = publication.readingOrder.firstOrNull { link ->
            link.url().toString().substringAfterLast('/') == name
        } ?: publication.resources.firstOrNull { link ->
            link.url().toString().substringAfterLast('/') == name
        }
        return match?.url()
    }
}
