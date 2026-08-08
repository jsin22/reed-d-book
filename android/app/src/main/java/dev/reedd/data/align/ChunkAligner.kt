package dev.reedd.data.align

import dev.reedd.data.db.SyncChapterEntity
import dev.reedd.data.db.SyncChunkEntity

/** One epub resource's text, as it appears in the DOM. */
data class ResourceText(
    /** The publication-relative href, e.g. `EPUB/chap_01.xhtml`. */
    val href: String,
    /**
     * Text content, **whitespace preserved**. Readium's locator resolution
     * searches the rendered DOM's text, so what is stored has to look like that
     * text rather than a tidied version of it.
     */
    val text: String,
)

data class AlignmentResult(
    val chunks: List<SyncChunkEntity>,
    val aligned: Int,
    val total: Int,
) {
    val ratio: Float get() = if (total == 0) 0f else aligned.toFloat() / total
}

/**
 * Works out where each spoken sentence sits in the epub.
 *
 * Pure text logic with no Android or Readium dependency, so the interesting part
 * of Phase 4 can be tested against the real book without a device.
 *
 * The output per chunk is what Readium's JavaScript actually consumes: a resource
 * href plus a text-quote anchor — the sentence as it appears on the page, with
 * context either side so a sentence that recurs resolves to the right occurrence
 * instead of the first.
 *
 * Why a cursor rather than a search per sentence: chunks are in reading order, so
 * one linear pass over each chapter is enough. Searching the whole resource for
 * every sentence would be quadratic on a novel, and would happily match the wrong
 * occurrence of "He nodded."
 *
 * Not everything aligns, by design:
 *  * chunk 0 is audiblez' injected `"<title> - <author>."`, which appears in no
 *    epub (`audiblez/SYNC.md`);
 *  * audiblez extracts only `title/p/h1-h4/li`, so the resource contains text the
 *    chunks skip — handled by searching forward from the cursor rather than
 *    expecting the next sentence to be adjacent;
 *  * a `.` is appended to anything not ending in one, so a heading's chunk has a
 *    period the page does not.
 *
 * An unmatched chunk keeps its timings and simply is not highlighted. The audio
 * still plays.
 */
class ChunkAligner(private val contextChars: Int = DEFAULT_CONTEXT) {

    fun align(
        chunks: List<SyncChunkEntity>,
        chapters: List<SyncChapterEntity>,
        resources: List<ResourceText>,
    ): AlignmentResult {
        val sourceByChapter = chapters.associate { it.chapterIndex to it.source }
        // Results are collected by position in the input list, deliberately not by
        // rowId: this runs on freshly-parsed chunks too, and Room has not assigned
        // their rowIds yet, so every one of them is still 0.
        val result = arrayOfNulls<SyncChunkEntity>(chunks.size)

        chunks.withIndex()
            .groupBy { (_, chunk) -> chunk.chapter }
            .forEach { (chapterIndex, indexed) ->
                val resource = resolveResource(sourceByChapter[chapterIndex], resources)
                    ?: return@forEach
                val aligned = alignChapter(indexed.map { it.value }, resource)
                indexed.forEachIndexed { position, (originalIndex, _) ->
                    result[originalIndex] = aligned[position]
                }
            }

        val chunksOut = chunks.mapIndexed { index, chunk -> result[index] ?: chunk }
        return AlignmentResult(
            chunks = chunksOut,
            aligned = chunksOut.count { it.isAligned },
            total = chunksOut.size,
        )
    }

    /**
     * The sync file names a chapter by bare filename (`chap_01.xhtml`) while the
     * publication addresses it by path (`EPUB/chap_01.xhtml`), so they are matched
     * on the last segment. An exact href match is preferred when one exists.
     */
    private fun resolveResource(source: String?, resources: List<ResourceText>): ResourceText? {
        if (source.isNullOrBlank()) return null
        resources.firstOrNull { it.href == source }?.let { return it }
        val name = source.substringAfterLast('/')
        return resources.firstOrNull { it.href.substringAfterLast('/') == name }
    }

    private fun alignChapter(chunks: List<SyncChunkEntity>, resource: ResourceText): List<SyncChunkEntity> {
        val haystack = TextNormalizer.normalize(resource.text)
        var cursor = 0

        return chunks.map { chunk ->
            val match = findNext(haystack, chunk.text, cursor)
            if (match == null) {
                chunk
            } else {
                cursor = match.last + 1
                val original = haystack.originalRange(match.first, match.last + 1)
                chunk.withLocator(resource, original)
            }
        }
    }

    /**
     * @return the matched range in the *normalised* haystack, inclusive.
     */
    private fun findNext(haystack: NormalizedText, needleText: String, cursor: Int): IntRange? {
        val needle = TextNormalizer.normalizeToString(needleText).trim()
        if (needle.isEmpty()) return null

        // Also try without the trailing period audiblez adds to headings and list
        // items, which the page itself does not have.
        val candidates = buildList {
            add(needle)
            if (needle.endsWith('.') && needle.length > 1) add(needle.dropLast(1).trimEnd())
        }

        for (candidate in candidates) {
            // Forward from the cursor first: that is the occurrence being spoken.
            val forward = haystack.text.indexOf(candidate, startIndex = cursor.coerceIn(0, haystack.length))
            if (forward >= 0) return forward..(forward + candidate.length - 1)
        }
        for (candidate in candidates) {
            // Only then fall back to the whole resource, for a chapter whose chunks
            // are not in document order (a footnote pulled inline, say). The cursor
            // still advances, so this cannot loop.
            val anywhere = haystack.text.indexOf(candidate)
            if (anywhere >= 0) return anywhere..(anywhere + candidate.length - 1)
        }
        return null
    }

    private fun SyncChunkEntity.withLocator(resource: ResourceText, range: IntRange): SyncChunkEntity {
        val text = resource.text
        // Trim to non-whitespace bounds: a highlight should not start with the
        // newline that preceded the sentence in the source XHTML.
        var start = range.first
        var end = range.last + 1
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return this

        return copy(
            resourceHref = resource.href,
            textHighlight = text.substring(start, end),
            textBefore = text.substring((start - contextChars).coerceAtLeast(0), start)
                .takeIf { it.isNotEmpty() },
            textAfter = text.substring(end, (end + contextChars).coerceAtMost(text.length))
                .takeIf { it.isNotEmpty() },
            // Character offset over resource length: an approximation of Readium's
            // progression, good enough to scroll near the right place if the text
            // anchor ever fails to resolve.
            progression = if (text.isEmpty()) null else (start.toDouble() / text.length).coerceIn(0.0, 1.0),
        )
    }

    private companion object {
        /**
         * Enough context to disambiguate a repeated sentence without bloating the
         * database by a multiple of the book's text.
         */
        const val DEFAULT_CONTEXT = 40
    }
}
