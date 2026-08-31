package dev.reedd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.reedd.data.remote.JobStatus
import kotlinx.coroutines.flow.Flow

/**
 * Reads are `Flow`s so the UI re-renders from the database rather than from
 * whatever coroutine happened to fetch something.
 *
 * Writes are narrow `UPDATE` statements rather than whole-row `@Update`s on
 * purpose: the poller, the upload and the download workers all write to the same
 * row concurrently, and a read-modify-write of the entire entity would let one
 * of them clobber another's column with a stale value.
 */
@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Query("SELECT * FROM books WHERE jobId = :jobId LIMIT 1")
    suspend fun findByJobId(jobId: String): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Books the server still owes a conversion for.
     *
     * Drives both the periodic background poll and the reconcile on launch. The
     * status strings are the stored enum names (see [Converters]).
     */
    @Query(
        """
        SELECT * FROM books
        WHERE jobId IS NOT NULL
          AND jobMissing = 0
          AND (jobStatus IS NULL OR jobStatus NOT IN ('DONE', 'ERROR'))
        ORDER BY addedAt ASC
        """
    )
    suspend fun awaitingConversion(): List<BookEntity>

    /**
     * Downloads the user already started that did not finish -- interrupted
     * (QUEUED/RUNNING) or failed, so they get resumed/retried automatically.
     *
     * Deliberately excludes [DownloadState.NONE]: a finished conversion nobody
     * has tapped "Download" for yet is *not* picked up here -- that is the
     * library card's job, not the background poller's. Downloading a book's
     * `.m4b` (often hundreds of MB) is a deliberate user action now, not
     * something that starts itself the moment a job finishes.
     */
    @Query(
        """
        SELECT * FROM books
        WHERE jobStatus = 'DONE'
          AND jobId IS NOT NULL
          AND downloadState NOT IN ('NONE', 'DONE')
        ORDER BY addedAt ASC
        """
    )
    suspend fun awaitingDownload(): List<BookEntity>

    /** Called once the upload has returned a job id. Clears any previous attempt. */
    @Query(
        """
        UPDATE books SET
            jobId = :jobId,
            jobStatus = :status,
            voice = :voice,
            speed = :speed,
            engine = :engine,
            jobProgress = 0,
            jobEta = NULL,
            jobChaptersDone = 0,
            jobError = NULL,
            jobMissing = 0,
            jobStartedAt = NULL,
            jobFinishedAt = NULL
        WHERE id = :id
        """
    )
    suspend fun attachJob(id: String, jobId: String, status: JobStatus, voice: String?, speed: Double?, engine: String?)

    /** Everything a poll learns, written in one statement. */
    @Query(
        """
        UPDATE books SET
            jobStatus = :status,
            jobProgress = :progress,
            jobEta = :eta,
            jobChaptersDone = :chaptersDone,
            jobError = :error,
            jobStartedAt = :startedAt,
            jobFinishedAt = :finishedAt,
            audiobookBytes = :audiobookBytes,
            audiobookRemoteName = COALESCE(:audiobookRemoteName, audiobookRemoteName),
            syncRemoteName = COALESCE(:syncRemoteName, syncRemoteName),
            category = COALESCE(:category, category),
            genres = COALESCE(:genres, genres)
        WHERE id = :id
        """
    )
    suspend fun updateJobState(
        id: String,
        status: JobStatus,
        progress: Int,
        eta: String?,
        chaptersDone: Int,
        error: String?,
        startedAt: String?,
        finishedAt: String?,
        audiobookBytes: Long?,
        // COALESCE, not a plain assignment: a poll of a still-running job carries
        // no filenames, and must not erase ones an earlier poll already learned.
        audiobookRemoteName: String?,
        syncRemoteName: String?,
        // Same COALESCE reasoning: the background lookup on the server usually
        // finishes after the first poll or two, and a null here just means
        // "still nothing new to report" -- not "clear what was already found".
        category: String?,
        // Pre-encoded JSON, not List<String>?: Room auto-expands any
        // List/Collection/Array-typed *query parameter* into `IN`-style `?, ?,
        // ...` placeholders regardless of SQL context -- that only applies to
        // entity column properties, never to a raw @Query bind parameter, so a
        // List<String>? here silently bypassed Converters.genresToString and
        // always wrote an empty list (caught by a real test: `dao.
        // updateJobState(..., genres = listOf("Horror"))` read back as `[]`).
        // The caller (BookRepository.applyJobState) does the JSON encoding.
        genres: String?,
    )

    /**
     * The server returned 404: it has forgotten this job.
     *
     * Stops the pollers and lets the UI offer a re-upload.
     */
    @Query("UPDATE books SET jobMissing = 1 WHERE id = :id")
    suspend fun markJobMissing(id: String)

    /** Forget the server side entirely, e.g. after a `DELETE` or before a retry. */
    @Query(
        """
        UPDATE books SET
            jobId = NULL, jobStatus = NULL, jobProgress = 0, jobEta = NULL,
            jobChaptersDone = 0, jobError = NULL, jobMissing = 0,
            jobStartedAt = NULL, jobFinishedAt = NULL, uploadedBytes = 0,
            uploadError = NULL
        WHERE id = :id
        """
    )
    suspend fun clearJob(id: String)

    @Query("UPDATE books SET uploadedBytes = :bytes WHERE id = :id")
    suspend fun updateUploadedBytes(id: String, bytes: Long)

    @Query("UPDATE books SET uploadError = :error WHERE id = :id")
    suspend fun setUploadError(id: String, error: String?)

    @Query(
        """
        UPDATE books SET
            downloadState = :state,
            downloadedBytes = :downloadedBytes,
            downloadTotalBytes = :totalBytes,
            downloadError = :error
        WHERE id = :id
        """
    )
    suspend fun updateDownload(
        id: String,
        state: DownloadState,
        downloadedBytes: Long,
        totalBytes: Long,
        error: String?,
    )

    @Query("UPDATE books SET downloadState = :state, downloadError = :error WHERE id = :id")
    suspend fun updateDownloadState(id: String, state: DownloadState, error: String?)

    @Query("UPDATE books SET audiobookPath = :path, audiobookBytes = :bytes WHERE id = :id")
    suspend fun setAudiobook(id: String, path: String?, bytes: Long?)

    @Query("UPDATE books SET syncPath = :path, audioDurationMs = :durationMs WHERE id = :id")
    suspend fun setSync(id: String, path: String?, durationMs: Long?)

    @Query("UPDATE books SET readingLocator = :locator, lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun updateReadingPosition(id: String, locator: String?, openedAt: Long)

    /**
     * Written while audio plays, so it is deliberately the narrowest statement
     * here: it fires every few seconds and must not touch anything else.
     */
    @Query("UPDATE books SET playbackPositionMs = :positionMs WHERE id = :id")
    suspend fun updatePlaybackPosition(id: String, positionMs: Long)

    @Query("UPDATE books SET syncOffsetMs = :offsetMs WHERE id = :id")
    suspend fun updateSyncOffset(id: String, offsetMs: Long)

    @Query("UPDATE books SET alignedChunks = :aligned, totalChunks = :total WHERE id = :id")
    suspend fun updateAlignment(id: String, aligned: Int, total: Int)

    @Query(
        """
        UPDATE books SET title = :title, author = :author, coverPath = :coverPath, sizeBytes = :sizeBytes
        WHERE id = :id
        """
    )
    suspend fun updateMetadata(id: String, title: String, author: String?, coverPath: String?, sizeBytes: Long)
}
