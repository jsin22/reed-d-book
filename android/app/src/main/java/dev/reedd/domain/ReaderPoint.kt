package dev.reedd.domain

import dev.reedd.data.db.SyncChunkEntity

/**
 * The furthest point a reader has reached, in the form the server's ask
 * endpoint places on a sentence: the chapter, and a window of the text just
 * before that point with the point at its end (`anchorOffset == anchorText.length`).
 * The server keeps whole sentences inside the window and cuts after the last,
 * so a sentence only part-shown at the bottom of a page is left out -- the safe
 * side for spoilers. [fraction] (0-1 through the chapter) orders two points in
 * one chapter and is the server's fallback if the window cannot be matched.
 */
data class ReaderPoint(
    val resourceHref: String,
    val anchorText: String,
    val anchorOffset: Int,
    val fraction: Double,
)

object ReaderPoints {

    /** Wide enough to hold a couple of whole sentences, so one always matches. */
    const val WINDOW = 600

    /** The end of the page being shown, [endOffset] into the chapter's [pageText]. */
    fun pageEnd(resourceHref: String, pageText: String, endOffset: Int): ReaderPoint? {
        if (pageText.isEmpty()) return null
        val end = endOffset.coerceIn(0, pageText.length)
        val window = pageText.substring((end - WINDOW).coerceAtLeast(0), end)
        return ReaderPoint(resourceHref, window, window.length, end.toDouble() / pageText.length)
    }

    /** The end of the sentence being read aloud, from its aligned text. */
    fun sentence(chunk: SyncChunkEntity?): ReaderPoint? {
        val href = chunk?.resourceHref ?: return null
        val highlight = chunk.textHighlight ?: return null
        val window = (chunk.textBefore.orEmpty() + highlight).takeLast(WINDOW)
        return ReaderPoint(href, window, window.length, chunk.progression ?: 0.0)
    }

    /** Whichever of two points is further into the book; [orderOf] gives a
     *  resource's position in the reading order. */
    fun furthest(a: ReaderPoint?, b: ReaderPoint?, orderOf: (String) -> Int): ReaderPoint? {
        if (a == null || b == null) return a ?: b
        val (ia, ib) = orderOf(a.resourceHref) to orderOf(b.resourceHref)
        return when {
            ib > ia -> b
            ib < ia -> a
            b.fraction > a.fraction -> b
            else -> a
        }
    }
}
