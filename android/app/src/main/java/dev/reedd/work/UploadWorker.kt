package dev.reedd.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.reedd.ReeddApp
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ProgressRequestBody
import dev.reedd.data.remote.ServerNotConfigured
import dev.reedd.notify.Notifications
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads a book's epub and records the job id the server hands back.
 *
 * A worker rather than a coroutine in a ViewModel: an upload of a few hundred
 * megabytes over wifi outlives the screen that started it, and the job id has to
 * be persisted even if the user leaves the moment they tap send.
 *
 * Nothing here waits for the conversion. The upload returns as soon as the file
 * is on the server's disk; [PollWorker] takes it from there.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val container = (context.applicationContext as ReeddApp).container

    private val bookId: String
        get() = requireNotNull(inputData.getString(KEY_BOOK_ID)) { "UploadWorker needs a bookId" }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val title = container.repository.get(bookId)?.title ?: "book"
        return container.notifications.foreground(
            Notifications.transferId(bookId),
            container.notifications.transfer("Uploading $title", "Sending to the conversion server", null),
        )
    }

    override suspend fun doWork(): Result = coroutineScope {
        val book = container.repository.get(bookId) ?: return@coroutineScope Result.failure()
        val epub = File(book.epubPath)
        if (!epub.isFile) {
            return@coroutineScope fail("the imported epub is missing from storage")
        }

        val settings = container.settings.current()
        val voice = book.voice ?: settings.voice
        val speed = book.speed ?: settings.speed

        // OkHttp calls the progress callback on a thread that cannot suspend, so
        // byte counts cross into coroutine land through a conflated channel: the
        // writer only ever cares about the most recent value.
        val progress = Channel<Long>(Channel.CONFLATED)
        val writer = launch {
            for (sent in progress) container.repository.updateUploadedBytes(bookId, sent)
        }

        try {
            setForeground(getForegroundInfo())
            container.repository.setUploadError(bookId, null)

            // Only whole percents are forwarded; the callback itself fires per
            // 64 KB, which for a large book is thousands of calls.
            var lastPercent = -1
            val body = ProgressRequestBody(epub, EPUB_MEDIA_TYPE) { sent, total ->
                val percent = if (total <= 0L) 0 else ((sent * 100) / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    progress.trySend(sent)
                }
            }

            val job = container.api.service().createJob(
                file = MultipartBody.Part.createFormData("file", book.originalFilename, body),
                voice = voice?.toRequestBody(TEXT),
                speed = speed.toString().toRequestBody(TEXT),
            )

            container.repository.updateUploadedBytes(bookId, epub.length())
            container.repository.attachJob(bookId, job)

            // The conversion has only just been queued. Start the background
            // watcher, and take one immediate look so a very short book does not
            // wait a whole period to be noticed.
            PollWorker.enqueuePeriodic(applicationContext)
            PollWorker.enqueueOnce(applicationContext)
            Result.success()
        } catch (e: ServerNotConfigured) {
            fail("no server address configured; set one in Settings")
        } catch (e: ApiException) {
            // 4xx is the server refusing this request: an unknown voice, a file
            // over the size limit, a bad token. Re-sending identical bytes cannot
            // help, so record the reason and stop.
            if (e.code in 400..499) fail(e.detail ?: e.message) else retryOrFail(e)
        } catch (e: IOException) {
            // Wifi dropped, or the machine is asleep. Worth another attempt.
            retryOrFail(e)
        } finally {
            progress.close()
            writer.join()
        }
    }

    private suspend fun retryOrFail(cause: Throwable): Result =
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            fail(cause.message ?: "upload failed after $MAX_ATTEMPTS attempts")
        } else {
            Result.retry()
        }

    private suspend fun fail(reason: String?): Result {
        container.repository.updateUploadedBytes(bookId, 0)
        container.repository.setUploadError(bookId, reason)
        return Result.failure(workDataOf(KEY_ERROR to reason))
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 5
        private val EPUB_MEDIA_TYPE = "application/epub+zip".toMediaType()
        private val TEXT = "text/plain".toMediaType()

        private fun workName(bookId: String) = "upload-$bookId"

        fun enqueue(context: Context, bookId: String) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_BOOK_ID to bookId))
                .setConstraints(
                    Constraints.Builder()
                        // Any connection: the server is usually on the same LAN,
                        // so demanding "unmetered" would be wrong on a hotspot.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                // KEEP, not REPLACE: tapping upload twice must not send the book
                // twice and leave two jobs converting on the server.
                .enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, bookId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(bookId))
        }
    }
}
