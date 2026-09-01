package dev.reedd.domain

import android.content.Context
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.DownloadState
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.JobDto
import dev.reedd.data.remote.JobStatus
import dev.reedd.data.remote.ServerNotConfigured
import dev.reedd.notify.Notifications
import dev.reedd.work.DownloadWorker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Keeps the local view of every conversion in step with the server.
 *
 * This is the whole of "handle app state properly so the app can resume if the
 * user closes it while waiting" (project_plan Phase 3). Two entry points, for
 * two different situations, both writing through the same [applyJobUpdate]:
 * [pollOnce]/[pollAll] for the frequent, foreground/background poll of jobs
 * still converting (one book, or a few, checked often); [reconcile] for the
 * occasional full resync (every book, checked once, in a single request) --
 * see its own docstring for why it stopped being "call pollOnce in a loop."
 * Either way, the UI never reads anything but the database.
 *
 * The server was built for this: job state lives in `job.json` on disk rather
 * than in Celery's result backend precisely so an id polled hours later still
 * answers (see `server/README.md`).
 */
class ConversionWatcher(
    private val context: Context,
    private val repository: BookRepository,
    private val api: ApiProvider,
    private val notifications: Notifications,
    /**
     * Pulls in server-side books this device does not have a row for yet. A
     * plain function rather than [ServerLibraryAdopter] itself so tests of
     * everything else here are not forced to construct one -- it depends on
     * [dev.reedd.data.local.EpubImporter] and, through it, Readium, which is
     * exactly the weight this class's own tests go out of their way to avoid.
     * Defaults to a no-op for exactly that reason; production wires the real
     * thing in `di/AppContainer.kt`. Takes the job list [reconcile] already
     * fetched rather than fetching its own -- see [reconcile]'s docstring.
     */
    private val adoptServerLibrary: suspend (jobs: List<JobDto>, known: Set<String>) -> Unit = { _, _ -> },
) {
    /**
     * Guards [reconcile] against itself. It reads "which jobs already have a
     * local row" up front and only inserts new ones after that snapshot, so
     * two calls overlapping (confirmed on a real device: the Library
     * screen's own launch-time reconcile racing the Admin screen's manual
     * "Re-check all book metadata") can each decide the same finished job is
     * new and both adopt it, leaving two rows for the same `jobId`. A plain
     * [Mutex], not per-book locking: reconcile is already meant to run to
     * completion before another one starts, and this app never runs more
     * than one [ConversionWatcher] at a time to make a finer-grained lock
     * worth the complexity. [BookEntity.jobId]'s unique index (see
     * `MIGRATION_4_5`) is the backstop for any caller that ever bypasses
     * this -- belt and suspenders, not either/or.
     */
    private val reconcileLock = Mutex()

    /**
     * Write down what a job's current state says, shared by [pollOnce] (one
     * network call, one book) and [reconcile] (one network call, every book).
     */
    private suspend fun applyJobUpdate(book: BookEntity, dto: JobDto): JobStatus {
        repository.applyJobState(book.id, dto)
        val status = JobStatus.fromWire(dto.status)

        when (status) {
            // Fetching the .m4b is a user action (the card's Download button),
            // not something every finished job starts on its own -- a book
            // adopted from server sync, say, may have finished converting long
            // before this device ever heard of it, and downloading a whole
            // library's worth of newly-adopted books unasked would be a bad
            // surprise. The one exception is book.autoDownload: set only when
            // this device is the one that submitted the job (see
            // LibraryViewModel.importAndUpload), which is exactly "the user is
            // sitting here waiting for this specific one" -- see
            // awaitingDownload() in BookDao for the other case where a download
            // resumes itself: one already started that got interrupted or failed.
            JobStatus.DONE -> if (book.autoDownload) {
                DownloadWorker.enqueue(context, book.id)
                repository.clearAutoDownload(book.id)
            }
            JobStatus.ERROR -> {
                // Only on the transition, so reopening the app does not re-notify
                // about a failure the user already saw and dismissed.
                if (book.jobStatus != JobStatus.ERROR) {
                    notifications.post(
                        Notifications.readyId(book.id),
                        notifications.failed(book.title, dto.error),
                    )
                }
            }
            else -> Unit
        }
        return status
    }

    /**
     * The server no longer has this job -- its data directory was wiped
     * (deliberately, by an admin's delete, or by some future cleanup sweep)
     * or someone deleted it. Shared by [pollOnce] (live, while a book is
     * actively converting) and [reconcile] (the periodic/manual full
     * resync), so a job vanishing is handled identically regardless of
     * which one notices first.
     *
     * A book already fully downloaded keeps working either way -- its
     * audio and mapping are local files a server-side delete never touches
     * -- so this only marks it, quietly, the same as before: see
     * [BookEntity.jobMissing]'s own doc for why that alone is not a reason
     * to tell the user anything. One that never finished downloading has
     * nothing to show for itself, so its card is removed outright rather
     * than sitting there stuck at whatever progress it last reported, or
     * offering a re-upload for a job this device is not the owner of.
     * `deleteServerJob = false`: the server already 404'd it, so asking it
     * to delete the same job again would just be a second failing request.
     *
     * @return the status now known, or null once the row is gone.
     */
    private suspend fun handleJobGone(book: BookEntity): JobStatus? =
        if (book.isPlayable) {
            repository.markJobMissing(book.id)
            book.jobStatus
        } else {
            repository.deleteBook(book.id, deleteServerJob = false)
            null
        }

    /**
     * Poll one job and write down what it said.
     *
     * @return the status now known, or null if there was nothing to poll.
     */
    suspend fun pollOnce(bookId: String): JobStatus? {
        val book = repository.get(bookId) ?: return null
        val jobId = book.jobId ?: return null
        if (book.jobMissing) return book.jobStatus

        val dto = try {
            api.service().job(jobId)
        } catch (e: ApiException) {
            if (e.isNotFound) return handleJobGone(book)
            throw e
        }

        return applyJobUpdate(book, dto)
    }

    /**
     * Bring everything up to date.
     *
     * Two separate concerns, and both matter after the app has been closed for a
     * while:
     *
     *  * jobs still converting need polling;
     *  * a download the user had already started -- interrupted mid-transfer, or
     *    failed -- needs picking up again. A finished job nobody has tapped
     *    Download for yet is not resumed here; see [dev.reedd.data.db.BookDao.awaitingDownload].
     *
     * @return how many books are still waiting on the server, so a caller can
     *   stop scheduling itself when the answer is zero.
     */
    suspend fun pollAll(): Int {
        for (book in repository.awaitingConversion()) {
            // Per-book isolation: one 500, one dropped socket, or an unconfigured
            // server must not stop the other books being polled. A book that
            // failed to poll simply stays pending, which keeps the caller
            // scheduling itself.
            runCatching { pollOnce(book.id) }
        }

        for (book in repository.awaitingDownload()) {
            DownloadWorker.enqueue(context, book.id)
        }

        return repository.awaitingConversion().size
    }

    /**
     * Called when the app comes to the foreground (and by the Admin screen's
     * "Re-check all book metadata").
     *
     * One `GET /api/jobs` call updates *every* local book that has a job --
     * status, progress, category/genres, all of it -- rather than one
     * `GET /api/jobs/{id}` per book. Originally this ran [pollOnce] in a loop
     * (for jobs still converting) plus a second loop for category/genre that
     * had never resolved, which meant one round trip per book, sequentially,
     * every single reconcile -- confirmed slow in practice with even a modest
     * library, especially over a real network rather than localhost. Fetching
     * the full list once and applying it to everything already known is both
     * the same number of facts learned and a single request regardless of how
     * many books there are. This also makes the old "never resolved" carve-out
     * for category/genre unnecessary: every book's fields are refreshed from
     * whatever the server currently has on every reconcile, not just ones that
     * were never checked before, so a book resolved with stale or incomplete
     * data (an older lookup pipeline, say) catches up automatically too.
     *
     * Also repairs rows whose files went missing from disk -- the user cleared
     * the app's storage, or a download was interrupted so hard that the row
     * still claims DONE. Trusting the database over the filesystem here would
     * give a book that opens to a missing-file error.
     *
     * A network failure here degrades to "nothing updated this time," same as
     * [ServerLibraryAdopter.adopt] already tolerated on its own -- there is
     * always a later reconcile to try again.
     */
    suspend fun reconcile(): Int = reconcileLock.withLock {
        val books = repository.allBooks()
        for (book in books) {
            if (book.downloadState == DownloadState.DONE && !book.filesPresent()) {
                repository.updateDownload(
                    book.id,
                    DownloadState.NONE,
                    error = "the downloaded files are no longer on the device",
                )
            }
        }

        val jobs = runCatching { api.service().listJobs(limit = LIST_LIMIT).jobs }.getOrNull()
        if (jobs != null) {
            val byJobId = jobs.associateBy { it.jobId }
            for (book in books) {
                val jobId = book.jobId ?: continue
                // Not skipped just because book.jobMissing is already true: a
                // book marked missing while it was still playable is exactly
                // right to leave alone, but the user can delete its local
                // copy *after* that happens -- confirmed live, a book stuck
                // showing "Job lost on server" forever, because a still-true
                // jobMissing from before short-circuited this loop and
                // handleJobGone (the thing that would have removed its now
                // useless card) never ran again. Re-deciding every time is
                // cheap: a still-playable book just gets marked missing
                // again, a harmless no-op write.
                val dto = byJobId[jobId]
                if (dto == null) {
                    // Not in a list of everything visible to this user, up to
                    // LIST_LIMIT: gone (deleted server-side) or no longer
                    // visible, the same two cases pollOnce's 404 branch
                    // handles -- see handleJobGone. A personal library never
                    // approaches the limit.
                    handleJobGone(book)
                } else {
                    runCatching { applyJobUpdate(book, dto) }
                }
            }
            adoptServerLibrary(jobs, books.mapNotNull { it.jobId }.toSet())
        }

        for (book in repository.awaitingDownload()) {
            DownloadWorker.enqueue(context, book.id)
        }
        repository.awaitingConversion().size
    }

    private fun BookEntity.filesPresent(): Boolean {
        val audiobook = audiobookPath?.let { java.io.File(it) }
        val sync = syncPath?.let { java.io.File(it) }
        return audiobook?.isFile == true && sync?.isFile == true
    }

    /** True when the exception is worth another attempt rather than a hard stop. */
    fun isTransient(e: Throwable): Boolean = when (e) {
        is ServerNotConfigured -> false
        is ApiException -> e.code >= 500
        is IOException -> true
        else -> false
    }

    private companion object {
        /** The server's own cap on `GET /api/jobs`; see `server/app/main.py`.
         *  Comfortably above what a personal library ever reaches. */
        const val LIST_LIMIT = 500
    }
}
