package dev.reedd.ui.reader

import dev.reedd.data.db.SyncChunkEntity
import dev.reedd.domain.resolveLink
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
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
}
