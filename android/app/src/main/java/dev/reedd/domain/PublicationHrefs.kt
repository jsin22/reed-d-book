package dev.reedd.domain

import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

/**
 * Matches a stored/reported href against a live [Publication]'s own resource
 * URLs, by filename rather than full path.
 *
 * A stored href (from the aligner's zip entry name, or a tap/selection's
 * `resourceHref`) is not necessarily addressed the same way the publication
 * addresses its own resources -- reading order first, since that is where a
 * real chapter lives, falling back to the resource list for anything else
 * (e.g. `nav.xhtml`).
 */
fun resolveLink(publication: Publication, href: String): Url? {
    val name = href.substringAfterLast('/')
    val match = publication.readingOrder.firstOrNull { link ->
        link.url().toString().substringAfterLast('/') == name
    } ?: publication.resources.firstOrNull { link ->
        link.url().toString().substringAfterLast('/') == name
    }
    return match?.url()
}
