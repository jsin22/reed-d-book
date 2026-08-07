package dev.reedd.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.reedd.ReeddApp
import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.DownloadState
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.JobStatus
import dev.reedd.data.remote.ServerNotConfigured
import dev.reedd.data.sync.SyncFileError
import dev.reedd.data.sync.SyncFileParser
import dev.reedd.notify.Notifications
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches a finished conversion: the `.m4b`, then the timing `.json`.
 *
 * In that order deliberately. The audiobook is the hundreds-of-megabytes part and
 * the one likely to be interrupted; the sync file is a few kilobytes. Doing the
 * big one first means a book is never left looking ready because the cheap half
 * arrived.
 *
 * The row only becomes [DownloadState.DONE] once both files are on disk *and* the
 * sync file has parsed into `sync_chunks` -- a book that says "ready" has to be
 * one Phase 4 can actually highlight.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val container = (context.applicationContext as ReeddApp).container

    private val bookId: String
        get() = requireNotNull(inputData.getString(KEY_BOOK_ID)) { "DownloadWorker needs a bookId" }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        container.notifications.foreground(
            Notifications.transferId(bookId),
            notification(container.repository.get(bookId)?.title ?: "book", null),
        )

    private fun notification(title: String, percent: Int?) =
        container.notifications.transfer(
            title = "Downloading $title",
            text = if (percent == null) "Fetching the audiobook" else "$percent%",
            progress = percent,
        )

    override suspend fun doWork(): Result = coroutineScope {
        val book = container.repository.get(bookId) ?: return@coroutineScope Result.failure()
        val jobId = book.jobId ?: return@coroutineScope Result.failure()
        if (book.jobStatus != JobStatus.DONE) {
            // Enqueued early by something; the poller re-enqueues when the
            // conversion actually finishes.
            return@coroutineScope Result.success()
        }

        // Byte counts arrive far too often to write straight to the database, so
        // they cross into coroutine land through a conflated channel: only the
        // latest matters.
        val progress = Channel<Pair<Long, Long>>(Channel.CONFLATED)
        val writer = launch {
            for ((downloaded, total) in progress) {
                container.repository.updateDownload(
                    bookId,
                    DownloadState.RUNNING,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    error = null,
                )
                val percent = if (total > 0L) ((downloaded * 100) / total).toInt() else null
                container.notifications.post(
                    Notifications.transferId(bookId),
                    notification(book.title, percent),
                )
            }
        }

        try {
            setForeground(getForegroundInfo())
            container.repository.updateDownload(bookId, DownloadState.RUNNING, error = null)

            val audiobook = downloadAudiobook(book, jobId, progress)
            val sync = downloadSync(book, jobId)
            parseSync(sync)

            container.repository.updateDownload(
                bookId,
                DownloadState.DONE,
                downloadedBytes = audiobook.length(),
                totalBytes = audiobook.length(),
                error = null,
            )
            container.notifications.post(
                Notifications.readyId(bookId),
                container.notifications.ready(book.title),
            )

            // The server keeps finished jobs until something deletes them, and its
            // README makes that the app's job. Both files are local now.
            if (container.settings.current().deleteJobAfterDownload) {
                runCatching { container.repository.releaseServerJob(bookId) }
            }
            Result.success()
        } catch (e: ServerNotConfigured) {
            fail("no server address configured")
        } catch (e: SyncFileError) {
            // The audio is fine but the mapping is not, so the book cannot be read
            // along with. An identical retry will not help.
            fail(e.message)
        } catch (e: ApiException) {
            when {
                // Not finished after all, or the files were cleaned up
                // server-side. Neither is fixed by retrying.
                e.isNotReady || e.isGone -> fail(e.detail ?: e.message)
                e.isNotFound -> {
                    container.repository.markJobMissing(bookId)
                    fail("the server no longer has this job")
                }
                e.code in 400..499 -> fail(e.detail ?: e.message)
                else -> retryOrFail(e)
            }
        } catch (e: IOException) {
            // Wifi dropped mid-transfer. The .part file survives, so the next
            // attempt resumes instead of restarting.
            retryOrFail(e)
        } finally {
            progress.close()
            writer.join()
        }
    }

    private suspend fun downloadAudiobook(
        book: BookEntity,
        jobId: String,
        progress: SendChannel<Pair<Long, Long>>,
    ): File {
        val target = container.files.audiobook(bookId, book.audiobookFilename())
        var lastPercent = -1
        val file = container.downloader.download(
            url = container.api.url("api/jobs/$jobId/audiobook"),
            target = target,
            expectedBytes = book.audiobookBytes,
        ) { downloaded, total ->
            val percent = if (total <= 0L) -1 else ((downloaded * 100) / total).toInt()
            if (percent != lastPercent) {
                lastPercent = percent
                progress.trySend(downloaded to total)
            }
        }
        container.repository.setAudiobook(bookId, file)
        return file
    }

    private suspend fun downloadSync(book: BookEntity, jobId: String): File {
        val target = File(container.files.audiobookDir(bookId), book.syncFilename())
        return container.downloader.download(
            url = container.api.url("api/jobs/$jobId/sync"),
            target = target,
        )
    }

    private suspend fun parseSync(file: File) {
        val parsed = SyncFileParser.parse(file, bookId)
        container.syncStore.replace(bookId, parsed.chunks, parsed.chapters)
        container.repository.setSync(bookId, file, parsed.durationMs)
    }

    private suspend fun retryOrFail(cause: Throwable): Result =
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            fail(cause.message ?: "download failed after $MAX_ATTEMPTS attempts")
        } else {
            container.repository.updateDownloadState(bookId, DownloadState.QUEUED, cause.message)
            Result.retry()
        }

    private suspend fun fail(reason: String?): Result {
        container.repository.updateDownloadState(bookId, DownloadState.FAILED, reason)
        return Result.failure(workDataOf(KEY_ERROR to reason))
    }

    /**
     * Prefer the name from the job manifest; fall back to the local title only if
     * a poll never carried one.
     */
    private fun BookEntity.audiobookFilename(): String =
        audiobookRemoteName ?: audiobookPath?.let { File(it).name } ?: "$title.m4b"

    private fun BookEntity.syncFilename(): String =
        syncRemoteName ?: audiobookFilename().substringBeforeLast('.') + ".json"

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 8

        private fun workName(bookId: String) = "download-$bookId"

        /**
         * Safe to call from anywhere, repeatedly: unique work per book with KEEP
         * lets the poller shout "done!" on every tick without starting a second
         * transfer of the same file.
         */
        fun enqueue(context: Context, bookId: String) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_BOOK_ID to bookId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        // A few hundred megabytes is worth not starting on a
                        // nearly-flat battery.
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, bookId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(bookId))
        }
    }
}
