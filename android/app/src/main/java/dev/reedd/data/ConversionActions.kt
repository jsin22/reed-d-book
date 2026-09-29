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
     * A deliberate re-conversion of an already-finished book, with a
     * different voice -- "update the offline voice for a book." Voices and
     * engines are immutable on a job once created server-side (confirmed:
     * no route updates them, and the Celery task only ever reads them at
     * its own start), so this is really just [retry] with two things done
     * first:
     *
     *  * the new voice is persisted ([BookRepository.updateVoice]) *before*
     *    [retry] re-uploads, since [dev.reedd.work.UploadWorker] reads
     *    `book.voice` fresh at submission time -- no change needed there;
     *  * this device's own old audiobook/sync/playback state is wiped
     *    ([BookRepository.resetAudioForReconversion], not the more
     *    thorough [BookRepository.deleteLocalContent] -- that one also
     *    deletes the local epub file [retry] needs to re-upload), and
     *    `autoDownload` is re-armed so the freshly finished job's `.m4b`
     *    fetches itself the moment it's ready, the same one-shot guarantee
     *    a brand-new upload gets -- otherwise it would already have been
     *    consumed by the *original* conversion, and this device would just
     *    sit on a finished job nobody downloads.
     *
     * Deliberately does nothing about any other device that already
     * downloaded the old audiobook -- there is no mechanism (yet) for one
     * to learn this book's audio changed at all; it keeps its old copy
     * until it deletes and re-downloads it. Accepted for now rather than
     * building a cross-device staleness signal.
     *
     * The *old* job itself (its own audiobook file included) is not
     * deleted here, immediately -- deleting it before the new one is
     * confirmed working would risk losing the only good copy over a
     * conversion that could still fail. Instead its id is stashed in
     * [BookEntity.previousJobId], and `ConversionWatcher` deletes it
     * server-side once the *new* job actually reaches `DONE`.
     */
    suspend fun changeVoiceAndReconvert(bookId: String, voice: String) {
        val book = repository.get(bookId)
        val oldJobId = book?.jobId
        // An earlier reconvert's own cleanup may not have finished yet (the
        // reader changed voice again before that one even finished) --
        // best-effort clean it up now too, rather than losing track of it
        // by overwriting previousJobId below.
        book?.previousJobId?.let { stillPending -> runCatching { repository.releaseStaleJob(bookId, stillPending) } }
        repository.updateVoice(bookId, voice)
        repository.resetAudioForReconversion(bookId)
        repository.setAutoDownload(bookId)
        if (oldJobId != null) repository.setPreviousJobId(bookId, oldJobId)
        retry(bookId)
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
