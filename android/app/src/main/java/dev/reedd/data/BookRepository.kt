package dev.reedd.data

import dev.reedd.data.align.ChunkAligner
import dev.reedd.data.db.BookDao
import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.Converters
import dev.reedd.data.db.DownloadState
import dev.reedd.data.db.SyncDao
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.JobDto
import dev.reedd.data.remote.JobStatus
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * The one place that writes a book's row.
 *
 * Everything else -- the upload, the pollers, the download workers, the reader --
 * goes through here, and the UI observes [books] / [book]. Keeping the writes in
 * a single type is what stops two components disagreeing about what "done" means.
 */
class BookRepository(
    private val bookDao: BookDao,
    private val syncDao: SyncDao,
    private val api: ApiProvider,
) {
    // Stateless -- used directly here to JSON-encode genres before a raw
    // @Query parameter, outside the entity-column path Room normally applies
    // it on. See applyJobState.
    private val converters = Converters()

    fun books(): Flow<List<BookEntity>> = bookDao.observeAll()

    fun book(id: String): Flow<BookEntity?> = bookDao.observe(id)

    suspend fun get(id: String): BookEntity? = bookDao.get(id)

    suspend fun allBooks(): List<BookEntity> = bookDao.all()

    suspend fun insert(book: BookEntity) = bookDao.insert(book)

    suspend fun awaitingConversion(): List<BookEntity> = bookDao.awaitingConversion()

    suspend fun awaitingDownload(): List<BookEntity> = bookDao.awaitingDownload()

    suspend fun attachJob(bookId: String, job: JobDto) {
        bookDao.attachJob(
            id = bookId,
            jobId = job.jobId,
            status = JobStatus.fromWire(job.status),
            voice = job.voice,
            speed = job.speed,
            engine = job.engine,
        )
    }

    suspend fun updateUploadedBytes(bookId: String, bytes: Long) =
        bookDao.updateUploadedBytes(bookId, bytes)

    suspend fun setUploadError(bookId: String, error: String?) =
        bookDao.setUploadError(bookId, error)

    /**
     * Write what a poll returned.
     *
     * `audiobookBytes` is taken from the manifest here rather than at download
     * time so the UI can show the expected size while the download is queued.
     *
     * `category`/`genres` come from a lookup that runs in the background on the
     * server and often has not finished by the first poll or two -- an empty
     * [JobDto.genres] is passed through as `null` here, not `emptyList()`, so
     * [BookDao.updateJobState]'s COALESCE treats "nothing new yet" the same way
     * it already treats a still-converting job's missing filenames, rather than
     * overwriting a genuine earlier result with nothing. Encoded to JSON here,
     * not passed as a `List<String>`: Room auto-expands a `List`-typed @Query
     * parameter into `IN`-style placeholders regardless of context, which
     * silently discarded every genre (see [BookDao.updateJobState]'s own
     * comment on `genres`).
     */
    suspend fun applyJobState(bookId: String, job: JobDto) {
        bookDao.updateJobState(
            id = bookId,
            status = JobStatus.fromWire(job.status),
            progress = job.progress,
            eta = job.eta,
            chaptersDone = job.chaptersDone,
            error = job.error,
            startedAt = job.startedAt,
            finishedAt = job.finishedAt,
            audiobookBytes = job.audiobook?.bytes,
            audiobookRemoteName = job.audiobook?.file,
            syncRemoteName = job.sync?.file,
            category = job.category,
            genres = job.genres.takeIf { it.isNotEmpty() }?.let { converters.genresToString(it) },
        )
    }

    /** The server 404'd this job; stop polling it. */
    suspend fun markJobMissing(bookId: String) = bookDao.markJobMissing(bookId)

    suspend fun clearAutoDownload(bookId: String) = bookDao.clearAutoDownload(bookId)

    suspend fun clearJob(bookId: String) = bookDao.clearJob(bookId)

    suspend fun updateDownload(
        bookId: String,
        state: DownloadState,
        downloadedBytes: Long = 0,
        totalBytes: Long = 0,
        error: String? = null,
    ) = bookDao.updateDownload(bookId, state, downloadedBytes, totalBytes, error)

    suspend fun updateDownloadState(bookId: String, state: DownloadState, error: String? = null) =
        bookDao.updateDownloadState(bookId, state, error)

    suspend fun setAudiobook(bookId: String, file: File?) =
        bookDao.setAudiobook(bookId, file?.absolutePath, file?.length())

    suspend fun setSync(bookId: String, file: File?, durationMs: Long?) =
        bookDao.setSync(bookId, file?.absolutePath, durationMs)

    suspend fun setAlignment(bookId: String, aligned: Int, total: Int, version: Int = ChunkAligner.ALIGNMENT_VERSION) =
        bookDao.updateAlignment(bookId, aligned, total, version)

    suspend fun updatePlaybackPosition(bookId: String, positionMs: Long) =
        bookDao.updatePlaybackPosition(bookId, positionMs)

    suspend fun updateSyncOffset(bookId: String, offsetMs: Long) =
        bookDao.updateSyncOffset(bookId, offsetMs)

    /** The read-along mapping, in playback order. */
    suspend fun syncChunks(bookId: String): List<dev.reedd.data.db.SyncChunkEntity> =
        syncDao.chunks(bookId)

    suspend fun updateMetadata(bookId: String, title: String, author: String?, coverPath: String?, sizeBytes: Long) =
        bookDao.updateMetadata(bookId, title, author, coverPath, sizeBytes)

    suspend fun setCoverPath(bookId: String, coverPath: String) = bookDao.setCoverPath(bookId, coverPath)

    suspend fun updateReadingPosition(bookId: String, locator: String?) =
        bookDao.updateReadingPosition(bookId, locator, System.currentTimeMillis())


    /**
     * Delete the book locally, and its job on the server if it still has one.
     *
     * A server-side failure is swallowed: the user asked to remove a book, and
     * refusing because an unreachable machine still holds a directory would be
     * the wrong answer. The job is left for the next `DELETE` or a manual sweep.
     */
    suspend fun deleteBook(bookId: String, deleteServerJob: Boolean = true) {
        val book = bookDao.get(bookId) ?: return
        if (deleteServerJob && book.jobId != null && !book.jobMissing) {
            runCatching { api.service().deleteJob(book.jobId) }
        }
        listOfNotNull(book.epubPath, book.coverPath, book.audiobookPath, book.syncPath)
            .forEach { runCatching { File(it).delete() } }
        // sync_chunks and sync_chapters cascade with the row.
        bookDao.delete(bookId)
    }

    /**
     * Wipes every local trace of a book except its library entry and the
     * server's job: the epub, cover, downloaded audiobook and sync file are
     * all deleted from disk; the read-along mapping (sync_chunks/
     * sync_chapters), reading position, playback position, sync offset, and
     * alignment state are all reset. This is the trash-can icon on the
     * library card -- "start this book's local state over, but keep it in
     * my library."
     *
     * Notes are the one deliberate exception: [NoteDao] has no server
     * counterpart at all, so deleting them here would be unrecoverable in a
     * way nothing else in this function is (everything else can simply be
     * re-fetched). A user asking to clear a book's local state is not
     * asking to lose annotations they wrote.
     *
     * The book row itself, and its `jobId`, survive: the card stays in the
     * library, back in the "ready to download" state, since the server
     * still has everything needed to fetch it all again --
     * `DownloadWorker.ensureEpub` already handles a book whose `epubPath`
     * names a file that turns out not to be there, re-fetching it (and
     * regenerating the cover from it) exactly the way it already does for
     * a book newly adopted from another device.
     */
    suspend fun deleteLocalContent(bookId: String) {
        val book = bookDao.get(bookId) ?: return
        listOfNotNull(book.epubPath, book.coverPath, book.audiobookPath, book.syncPath)
            .forEach { runCatching { File(it).delete() } }
        updateDownload(bookId, DownloadState.NONE, downloadedBytes = 0, totalBytes = 0, error = null)
        setAudiobook(bookId, null)
        setSync(bookId, null, null)
        bookDao.clearCover(bookId)
        updateReadingPosition(bookId, null)
        bookDao.updatePlaybackPosition(bookId, 0)
        bookDao.updateSyncOffset(bookId, 0)
        syncDao.clearChunks(bookId)
        syncDao.clearChapters(bookId)
        // Otherwise a re-download never gets a fresh alignment pass: the
        // sync_chunks rows themselves are untouched by this function, and
        // BookEntity.needsAlignment only becomes true again from
        // alignedChunks == 0 -- an existing (possibly imperfect, e.g. from
        // an aligner bug fixed since) alignment permanently forecloses
        // ever recomputing it otherwise. Moot now that clearChunks/
        // clearChapters above also run, but kept as the explicit, direct
        // statement of intent rather than relying on that as a side effect.
        setAlignment(bookId, aligned = 0, total = 0, version = 0)
    }

    /**
     * Ask the server to forget a finished job, once both files are local.
     *
     * The server has no cleanup policy of its own -- `server/README.md` says the
     * app is expected to do this. A 404 means someone got there first, which is
     * success, not an error.
     */
    suspend fun releaseServerJob(bookId: String) {
        val book = bookDao.get(bookId) ?: return
        val jobId = book.jobId ?: return
        if (!book.isPlayable) return
        try {
            api.service().deleteJob(jobId)
        } catch (e: ApiException) {
            if (!e.isNotFound) throw e
        }
        bookDao.markJobMissing(bookId)
    }
}
