package dev.reedd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SyncDao {

    @Insert
    suspend fun insertChunks(chunks: List<SyncChunkEntity>)

    @Insert
    suspend fun insertChapters(chapters: List<SyncChapterEntity>)

    @Query("SELECT * FROM sync_chunks WHERE bookId = :bookId ORDER BY ordinal ASC")
    suspend fun chunks(bookId: String): List<SyncChunkEntity>

    @Query("SELECT COUNT(*) FROM sync_chunks WHERE bookId = :bookId")
    suspend fun chunkCount(bookId: String): Int

    @Query("SELECT * FROM sync_chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun chapters(bookId: String): List<SyncChapterEntity>

    /**
     * The chunk covering a playback position, for Phase 4's highlighting.
     *
     * `startMs <= position` ordered descending rather than a `BETWEEN`: it lands
     * on the correct row even if a future sync file ever had a gap between
     * chunks, and it uses the `(bookId, startMs)` index directly.
     */
    @Query(
        """
        SELECT * FROM sync_chunks
        WHERE bookId = :bookId AND startMs <= :positionMs
        ORDER BY startMs DESC
        LIMIT 1
        """
    )
    suspend fun chunkAt(bookId: String, positionMs: Long): SyncChunkEntity?

    @Query("DELETE FROM sync_chunks WHERE bookId = :bookId")
    suspend fun clearChunks(bookId: String)

    @Query("DELETE FROM sync_chapters WHERE bookId = :bookId")
    suspend fun clearChapters(bookId: String)

    /**
     * Replace a book's mapping wholesale.
     *
     * Transactional because a re-download must never leave the table holding
     * half of one sync file and half of another.
     */
    @Transaction
    suspend fun replace(
        bookId: String,
        chunks: List<SyncChunkEntity>,
        chapters: List<SyncChapterEntity>,
    ) {
        clearChunks(bookId)
        clearChapters(bookId)
        insertChunks(chunks)
        insertChapters(chapters)
    }
}
