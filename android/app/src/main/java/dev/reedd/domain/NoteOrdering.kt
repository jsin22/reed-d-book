package dev.reedd.domain

import org.readium.r2.shared.publication.Link

/**
 * Where a note's resource sits in the book, for cross-chapter ordering.
 *
 * `Locator.locations.totalProgression` would be the more direct answer, but
 * nothing in this codebase populates it for a locator built from a raw
 * tap/selection, and backfilling it costs an extra `Publication.positions()`
 * call for no real benefit here. The reading-order index plus the existing
 * within-resource `progression` (see [NoteEntity][dev.reedd.data.db.
 * NoteEntity]) is enough to sort notes correctly with a plain `ORDER BY`,
 * resolved once at save time rather than re-derived on every read.
 *
 * Same filename-normalization convention as [ChunkIndex.indexOfTap] /
 * [dev.reedd.ui.reader.ReadAlongLocators]'s href matching.
 */
fun spineIndexOf(readingOrder: List<Link>, href: String): Int? {
    val name = href.substringAfterLast('/')
    val index = readingOrder.indexOfFirst { it.url().toString().substringAfterLast('/') == name }
    return index.takeIf { it >= 0 }
}
