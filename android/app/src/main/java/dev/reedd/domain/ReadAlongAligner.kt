package dev.reedd.domain

import dev.reedd.data.align.AlignmentResult
import dev.reedd.data.align.ChunkAligner
import dev.reedd.data.align.EpubTextExtractor
import dev.reedd.data.db.SyncChapterEntity
import dev.reedd.data.db.SyncChunkEntity
import dev.reedd.data.db.SyncDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs the aligner and records the result.
 *
 * Two entry points, because alignment happens at two different moments:
 *
 *  * [alignForDownload] runs on freshly-parsed chunks *before* they are inserted,
 *    so the locators land in the same write as the timings — one insert, no update
 *    pass, and the book is never briefly visible as playable-but-unhighlightable.
 *  * [alignExisting] repairs a book already in the database: one downloaded before
 *    the aligner existed (see the 1 -> 2 migration), or one whose first attempt
 *    failed. It has to update rows rather than insert them.
 */
class ReadAlongAligner(
    private val syncDao: SyncDao,
    private val aligner: ChunkAligner = ChunkAligner(),
) {
    /**
     * Align chunks that are not in the database yet.
     *
     * @return the chunks to insert, with locators attached where they were found.
     */
    suspend fun alignForDownload(
        epub: File,
        chunks: List<SyncChunkEntity>,
        chapters: List<SyncChapterEntity>,
    ): AlignmentResult = withContext(Dispatchers.IO) {
        if (!epub.isFile) return@withContext AlignmentResult(chunks, aligned = 0, total = chunks.size)
        val resources = runCatching { EpubTextExtractor.extract(epub) }.getOrDefault(emptyList())
        // A book that cannot be read still gets its timings stored: playback works,
        // only the highlighting is missing.
        if (resources.isEmpty()) return@withContext AlignmentResult(chunks, aligned = 0, total = chunks.size)
        // Same reasoning as the extraction step just above, now also covering
        // the aligner itself: this ran unguarded until a real crash there
        // (an unusually punctuated chapter) propagated all the way up through
        // DownloadWorker uncaught, leaving a download stuck retrying forever
        // instead of landing on "no highlighting, but the book still plays."
        runCatching { aligner.align(chunks, chapters, resources) }
            .getOrDefault(AlignmentResult(chunks, aligned = 0, total = chunks.size))
    }

    /**
     * Align a book whose chunks are already stored, writing the locators back.
     *
     * @return the result, or null if there was nothing to align.
     */
    suspend fun alignExisting(bookId: String, epub: File): AlignmentResult? = withContext(Dispatchers.IO) {
        val chunks = syncDao.chunks(bookId)
        if (chunks.isEmpty() || !epub.isFile) return@withContext null

        val resources = runCatching { EpubTextExtractor.extract(epub) }.getOrDefault(emptyList())
        if (resources.isEmpty()) return@withContext null

        // See alignForDownload's own comment: the aligner itself can throw on
        // unusual content, and that must not crash whatever background retry
        // is calling this rather than just leaving the book unaligned.
        val result = runCatching { aligner.align(chunks, syncDao.chapters(bookId), resources) }
            .getOrNull() ?: return@withContext null
        // rowIds are real here, so this can be a targeted update. One transaction:
        // a novel is tens of thousands of chunks, and that many separate writes
        // would be tens of thousands of disk syncs.
        syncDao.saveAlignment(result.chunks.filter { it.isAligned })
        result
    }
}
