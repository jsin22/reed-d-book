package dev.reedd.domain

import dev.reedd.data.db.SyncChapterEntity
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import java.net.URLDecoder

/**
 * The bare filename a resource is addressed by, decoded -- for comparing an
 * href from the aligner (a raw zip entry name: [dev.reedd.data.align.
 * EpubTextExtractor] reads it straight out of the zip, which is never
 * percent-encoded) against one from Readium (`Link.url()`/`Locator.href`,
 * a proper URL that *does* percent-encode characters like a space.
 *
 * Confirmed live as a real, not hypothetical, gap: a chapter file named
 * after the book's own title, spaces and all ("The Cuckoo's Egg -
 * v.1.0_split_002.html", an ordinary Calibre-split epub, not malformed) made
 * every comparison here fail -- the aligner's stored href kept its literal
 * spaces, Readium's own URL had `%20` in their place -- which hid both
 * read-along's sentence highlight ([resolveLink], used by [dev.reedd.ui.
 * reader.ReadAlongLocators.locator]) and "Read from here" on every single
 * word in the book ([dev.reedd.domain.ChunkIndex.indexOfTap]/[dev.reedd.
 * domain.ChunkIndex.indexOfSelection], which compare two stored hrefs
 * rather than resolving against a `Publication` but hit the exact same
 * mismatch). Decoding is a no-op on a plain filename with nothing to decode,
 * so this is safe to apply regardless of which side (if either) turns out
 * encoded. `URLDecoder`, not `android.net.Uri` -- this file has no Android
 * dependency (see [dev.reedd.domain.ChunkIndex]'s own doc on why: so the
 * matching logic can be tested against a real book without a device), and
 * `URLDecoder` decodes a literal `+` to a space, which a real filename could
 * in principle contain -- accepted as a narrow, unconfirmed edge case
 * against the alternative of pulling in an Android-only decoder here.
 *
 * Also strips a `#fragment`, confirmed live as a second real gap: a real
 * book's own nav document (Karin Slaughter, "We Are All Guilty Here")
 * links every real chapter as `ChapterNN.xhtml#chN` while [readingOrder]'s
 * own resources are, correctly, whole-file hrefs with no fragment at all --
 * every chapter entry in [currentChapterTitle]'s table of contents failed
 * to match *any* reading-order resource for exactly that reason, except
 * the one front-matter "Contents" entry that happened to carry no fragment
 * of its own, which is why every single page of that book reported its
 * chapter as "Contents" regardless of which chapter was actually on
 * screen. The fragment is dropped before splitting on `/`, not after: a
 * raw href's `#` is always the URI fragment delimiter, never percent-
 * encoded, so this has to happen before decoding could turn some other
 * encoded sequence into a literal `#` and be mistaken for one.
 */
fun bareResourceName(href: String?): String? =
    href?.substringBefore('#')
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        ?.substringAfterLast('/')

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
    val name = bareResourceName(href)
    val match = publication.readingOrder.firstOrNull { link ->
        bareResourceName(link.url().toString()) == name
    } ?: publication.resources.firstOrNull { link ->
        bareResourceName(link.url().toString()) == name
    }
    return match?.url()
}

/**
 * A table of contents tree, flattened -- a [Publication]'s own [Link.children]
 * nests real chapters inside their own volume/part, which a plain list over
 * the top level alone never reaches. Confirmed live on a real book (The
 * Count of Monte Cristo): the reader's Contents sheet showed "VOLUME ONE"
 * through "VOLUME FIVE" and nothing else, even though Readium had already
 * parsed every real chapter underneath them correctly -- they just had
 * nowhere to render. [depth] (0 for a volume/part, 1+ for what nests inside
 * it) is what the sheet indents by, so the hierarchy still reads as one even
 * flattened into a single list.
 */
fun flattenToc(links: List<Link>, depth: Int = 0): List<Pair<Int, Link>> =
    links.flatMap { link -> listOf(depth to link) + flattenToc(link.children, depth + 1) }

/**
 * How few entries a book's own table of contents can have, once flattened,
 * before it counts as too broken to bother showing -- see [chaptersToc]'s
 * own doc for why replacing it is still the right call below this, and why
 * it is not above it.
 */
private const val MIN_USABLE_TOC_ENTRIES = 2

/**
 * Falls back to the audiobook's own chapter breaks when a book's real table
 * of contents is too broken to be worth showing -- confirmed live on a real
 * book ("The Cuckoo's Egg"): its own navigation document had exactly one
 * entry, and it was a stray footnote link, not a chapter list.
 *
 * [chapters] (already computed for read-along, no extra parsing needed here)
 * makes a strictly *worse* table of contents than a real one -- generic
 * "Chapter N" labels instead of the book's own chapter titles, and no
 * Part/Volume grouping -- so this only replaces a real table of contents at
 * or below [MIN_USABLE_TOC_ENTRIES] entries, never a real one that simply
 * groups its chapters more coarsely than audiblez happened to split the
 * file (confirmed on Monte Cristo: 7 top-level entries, ~126 flattened,
 * nowhere near this threshold, despite being far coarser at the top level
 * than the audiobook's own 125 per-file chapters).
 *
 * A chapter whose own stored [SyncChapterEntity.source] cannot be resolved
 * against this [Publication] is skipped rather than shown as a dead link --
 * the same defensive stance [dev.reedd.ui.reader.ReadAlongLocators.locator]
 * already takes for the identical lookup.
 */
fun chaptersToc(publication: Publication, realToc: List<Link>, chapters: List<SyncChapterEntity>): List<Link> {
    if (flattenToc(realToc).size > MIN_USABLE_TOC_ENTRIES) return realToc
    return chapters.mapNotNull { chapter ->
        val href = chapter.source?.let { resolveLink(publication, it) } ?: return@mapNotNull null
        Link(href = href, title = chapter.title)
    }
}

/**
 * The title of the table-of-contents entry the reader is currently inside --
 * for the page indicator's own chapter label, next to the page number.
 *
 * A table of contents entry only ever marks where a chapter *starts*; most
 * resources in [readingOrder] have no entry of their own (a chapter can span
 * several resources, or a resource can carry no heading at all). So this is
 * not a direct lookup -- it is whichever flattened entry (see [flattenToc])
 * resolves to the *closest* reading-order position at or before
 * [resourceHref], the same "which chapter contains this point" reasoning a
 * table of contents implies just by being ordered.
 *
 * @return null if [resourceHref] itself cannot be found in [readingOrder]
 *   (should not happen for the resource actually on screen), or if nothing
 *   in [tableOfContents] resolves at or before it (a book with no usable
 *   table of contents at all, or reading before its very first entry).
 */
fun currentChapterTitle(readingOrder: List<Link>, tableOfContents: List<Link>, resourceHref: String): String? {
    val currentIndex = readingOrder.indexOfFirst { bareResourceName(it.url().toString()) == bareResourceName(resourceHref) }
    if (currentIndex < 0) return null
    return flattenToc(tableOfContents)
        .mapNotNull { (_, link) ->
            val entryIndex = readingOrder.indexOfFirst { bareResourceName(it.url().toString()) == bareResourceName(link.url().toString()) }
            (entryIndex to link).takeIf { entryIndex in 0..currentIndex }
        }
        .maxByOrNull { (entryIndex, _) -> entryIndex }
        ?.second?.title
}
