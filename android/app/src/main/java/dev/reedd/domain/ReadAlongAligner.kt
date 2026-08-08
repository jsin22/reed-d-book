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
        aligner.align(chunks, chapters, resources)
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

        val result = aligner.align(chunks, syncDao.chapters(bookId), resources)
        // rowIds are real here, so this can be a targeted update. One transaction:
        // a novel is tens of thousands of chunks, and that many separate writes
        // would be tens of thousands of disk syncs.
        syncDao.saveAlignment(result.chunks.filter { it.isAligned })
        result
    }
}
