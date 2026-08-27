package dev.reedd.domain

import android.content.Context
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.DownloadState
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.JobStatus
import dev.reedd.data.remote.ServerNotConfigured
import dev.reedd.notify.Notifications
import dev.reedd.work.DownloadWorker
import java.io.IOException

/**
 * Keeps the local view of every conversion in step with the server.
 *
 * This is the whole of "handle app state properly so the app can resume if the
 * user closes it while waiting" (project_plan Phase 3). The important property is
 * that **it is the same code in all three situations**: the foreground poll while
 * a screen is open, the periodic background poll while the app is closed, and the
 * reconcile on launch all call [pollOnce]. There is no separate path that can
 * drift, and the UI never reads anything but the database.
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
     * thing in `di/AppContainer.kt`.
     */
    private val adoptServerLibrary: suspend (known: Set<String>) -> Unit = {},
) {
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
            if (e.isNotFound) {
                // The server no longer has this job: its data directory was
                // wiped, or someone deleted it. Stop polling and let the UI offer
                // a re-upload -- otherwise the book sits at "queued" forever.
                repository.markJobMissing(bookId)
                return book.jobStatus
            }
            throw e
        }

        repository.applyJobState(bookId, dto)
        val status = JobStatus.fromWire(dto.status)

        when (status) {
            // Deliberately no download here: fetching the .m4b is a user action
            // now (the card's Download button), not something a finished job
            // starts on its own. The row just sits at BookStage.AVAILABLE until
            // then -- see awaitingDownload() in BookDao for the one case where a
            // download *does* resume itself: one already started that got
            // interrupted or failed.
            JobStatus.DONE -> Unit
            JobStatus.ERROR -> {
                // Only on the transition, so reopening the app does not re-notify
                // about a failure the user already saw and dismissed.
                if (book.jobStatus != JobStatus.ERROR) {
                    notifications.post(
                        Notifications.readyId(bookId),
                        notifications.failed(book.title, dto.error),
                    )
                }
            }
            else -> Unit
        }
        return status
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
     * Called when the app comes to the foreground.
     *
     * Beyond [pollAll], this repairs rows whose files went missing from disk --
     * the user cleared the app's storage, or a download was interrupted so hard
     * that the row still claims DONE. Trusting the database over the filesystem
     * here would give a book that opens to a missing-file error.
     *
     * It also adopts any book the server has finished converting that this
     * device does not have a row for -- see [adoptServerLibrary]. Both need the
     * same book list, fetched once rather than twice.
     */
    suspend fun reconcile(): Int {
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
        adoptServerLibrary(books.mapNotNull { it.jobId }.toSet())
        return pollAll()
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
}
