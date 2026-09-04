package dev.reedd.data

import android.app.Application
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.work.DownloadWorker
import dev.reedd.work.PollWorker
import dev.reedd.work.UploadWorker
import java.io.IOException

/**
 * Retry-after-failure and cancel-while-converting: the same two actions
 * reachable from both the library card and the detail screen, byte-for-byte
 * identical in [dev.reedd.ui.library.LibraryViewModel] and
 * [dev.reedd.ui.detail.BookDetailViewModel] until this was pulled out --
 * each had a comment naming the other as what it mirrored, which is exactly
 * the kind of duplication a future change to either one could silently
 * drift out of step with.
 */
class ConversionActions(
    private val context: Application,
    private val repository: BookRepository,
    private val api: ApiProvider,
) {
    /**
     * Re-send the book's already-imported epub: after an upload failure OR a
     * conversion failure (a job that came back `error`, or one the server
     * lost). One action for both, deliberately: the epub is already sitting
     * in this app's own storage from the original import (uploading never
     * consumes or moves it), so there is nothing to re-pick and nothing
     * left for the user to diagnose -- re-sending that same local file and
     * getting a fresh job is the whole of "try again" regardless of which
     * of the two steps it failed on.
     */
    suspend fun retry(bookId: String) {
        repository.clearJob(bookId)
        UploadWorker.enqueue(context, bookId)
        PollWorker.enqueuePeriodic(context)
        PollWorker.enqueueOnce(context)
    }

    /**
     * Stop the conversion: cancels both workers, asks the server to drop
     * the job if it has one, and clears this book's local job state -- the
     * epub and the row both stay, so [retry] can pick the card back up
     * exactly where cancel left it.
     *
     * A server-side failure other than "already gone" is not fatal to the
     * local cancel, which always proceeds -- but is worth surfacing, so the
     * caller decides how (a snackbar, an inline message); returns `null`
     * when there is nothing to report.
     */
    suspend fun cancel(bookId: String): String? {
        UploadWorker.cancel(context, bookId)
        DownloadWorker.cancel(context, bookId)
        val book = repository.get(bookId)
        val jobId = book?.jobId
        var message: String? = null
        if (jobId != null && !book.jobMissing) {
            try {
                api.service().deleteJob(jobId)
            } catch (e: ApiException) {
                if (!e.isNotFound) message = e.detail ?: e.message
            } catch (e: IOException) {
                message = e.message ?: "could not reach the server"
            }
        }
        repository.clearJob(bookId)
        return message
    }
}
