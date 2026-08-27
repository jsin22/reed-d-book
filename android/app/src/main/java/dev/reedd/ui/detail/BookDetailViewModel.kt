package dev.reedd.ui.detail

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.di.AppContainer
import dev.reedd.work.DownloadWorker
import dev.reedd.work.PollWorker
import dev.reedd.work.UploadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The detail screen's state *and* its actions.
 *
 * The actions used to live on [dev.reedd.ui.library.LibraryViewModel], which meant
 * the detail destination constructed a second one — and since that ViewModel starts a
 * polling loop in its `init`, opening this screen started a second poller hitting the
 * server. Worse, that hidden second poll was what made conversion progress appear
 * after visiting this screen and going back, masking the fact that the library's own
 * loop was asleep. One owner per job, so both problems go away.
 */
class BookDetailViewModel(
    private val bookId: String,
    private val context: Application,
    private val repository: BookRepository,
    private val api: ApiProvider,
) : ViewModel() {

    val book: StateFlow<BookEntity?> =
        repository.book(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether the current token belongs to an admin, checked fresh here rather
     * than trusted from [dev.reedd.ui.library.LibraryViewModel] -- this screen
     * is only ever reachable through that one's own admin gate today, but this
     * keeps [delete] correct on its own if that ever changes. Defaults false,
     * the safe side: an unresolved or failed check must not grant [delete] the
     * permanent, cross-device server deletion.
     */
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            _isAdmin.value = runCatching { api.service().me().isAdmin }.getOrDefault(false)
        }
    }

    private val _log = MutableStateFlow<String?>(null)
    val log: StateFlow<String?> = _log.asStateFlow()

    private val _loadingLog = MutableStateFlow(false)
    val loadingLog: StateFlow<Boolean> = _loadingLog.asStateFlow()

    /**
     * Fetches `GET /api/jobs/{id}/log`: audiblez' own output, ffmpeg included.
     *
     * Worth surfacing rather than hiding behind a generic failure message -- when a
     * conversion dies it is nearly always something in here (a missing espeak
     * library, ffmpeg absent, a chapter audiblez could not parse).
     */
    fun loadLog() {
        val jobId = book.value?.jobId ?: run {
            _log.value = "This book has no job on the server."
            return
        }
        viewModelScope.launch {
            _loadingLog.value = true
            _log.value = try {
                api.service().log(jobId).string().takeLast(MAX_LOG_CHARS)
            } catch (e: IOException) {
                "Could not fetch the log: ${e.message}"
            } finally {
                _loadingLog.value = false
            }
        }
    }

    // -- actions ---------------------------------------------------------------

    /** Send the book again: after an upload failure, or a job the server lost. */
    fun retryUpload() {
        viewModelScope.launch {
            repository.clearJob(bookId)
            UploadWorker.enqueue(context, bookId)
            PollWorker.enqueuePeriodic(context)
            PollWorker.enqueueOnce(context)
        }
    }

    /** Fetch the finished files again, e.g. after a failed download. */
    fun retryDownload() {
        DownloadWorker.enqueue(context, bookId)
    }

    /** Stop the conversion and let the server reclaim its disk. */
    fun cancel() {
        viewModelScope.launch {
            UploadWorker.cancel(context, bookId)
            DownloadWorker.cancel(context, bookId)
            val book = repository.get(bookId)
            val jobId = book?.jobId
            if (jobId != null && !book.jobMissing) {
                try {
                    api.service().deleteJob(jobId)
                } catch (e: ApiException) {
                    if (!e.isNotFound) _message.value = e.detail ?: e.message
                } catch (e: IOException) {
                    _message.value = e.message ?: "could not reach the server"
                }
            }
            repository.clearJob(bookId)
        }
    }

    /**
     * Delete the book. For an ordinary user this only ever touches this device
     * -- the server keeps its job, since deleting it by default would mean
     * losing a conversion (possibly hours of TTS) that another device, or its
     * owner, might still want. An admin's delete is the permanent, alternate
     * path to the same action the Admin screen's own trash icon performs:
     * `DELETE /api/jobs/{id}`, which the server allows an admin to call on any
     * job regardless of ownership (see `_owns_or_admin` in
     * `server/app/main.py`) -- it removes the job everywhere, not just here.
     */
    fun delete() {
        viewModelScope.launch {
            UploadWorker.cancel(context, bookId)
            DownloadWorker.cancel(context, bookId)
            repository.deleteBook(bookId, deleteServerJob = _isAdmin.value)
        }
    }

    /** The Download button: fetch a finished job's audiobook and sync file. */
    fun download() {
        DownloadWorker.enqueue(context, bookId)
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        /** Enough for the tail that matters; a novel's log can be megabytes. */
        private const val MAX_LOG_CHARS = 20_000

        fun factory(container: AppContainer, context: Context, bookId: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BookDetailViewModel(
                        bookId,
                        context.applicationContext as Application,
                        container.repository,
                        container.api,
                    ) as T
            }
    }
}
