package dev.reedd.ui.library

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.DownloadState
import dev.reedd.data.local.EpubImporter
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.JobStatus
import dev.reedd.data.remote.ServerNotConfigured
import dev.reedd.data.settings.ServerSettings
import dev.reedd.data.settings.SettingsStore
import dev.reedd.di.AppContainer
import dev.reedd.domain.ConversionWatcher
import dev.reedd.work.DownloadWorker
import dev.reedd.work.PollWorker
import dev.reedd.work.UploadWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/** What the import sheet needs to offer, fetched from the server. */
data class ConversionOptions(
    val voices: List<String> = emptyList(),
    val defaultVoice: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class LibraryViewModel(
    // Typed as Application, not Context: a ViewModel outlives any Activity, and
    // holding a narrower Context here would be a leak.
    private val context: Application,
    private val repository: BookRepository,
    private val importer: EpubImporter,
    private val watcher: ConversionWatcher,
    private val api: ApiProvider,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> =
        repository.books().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<ServerSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ServerSettings())

    private val _options = MutableStateFlow(ConversionOptions())
    val options: StateFlow<ConversionOptions> = _options.asStateFlow()

    /** Transient one-line messages: an import that failed, a job that was cancelled. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    init {
        viewModelScope.launch {
            // Repair rows whose files vanished, then catch up on everything the
            // server did while the app was closed.
            runCatching { watcher.reconcile() }
            if (repository.awaitingConversion().isNotEmpty()) {
                PollWorker.enqueuePeriodic(context)
            }
        }
        startLivePolling()
    }

    /**
     * Polls while a screen is open.
     *
     * The same [ConversionWatcher.pollAll] the background worker calls, just far
     * more often -- the 15-minute floor on periodic work is fine for noticing a
     * finished book but useless for a progress bar someone is watching. Both write
     * to the database and the UI only ever reads from there, so the two cannot
     * disagree.
     */
    private fun startLivePolling() = viewModelScope.launch {
        while (isActive) {
            val remaining = runCatching { watcher.pollAll() }.getOrDefault(0)
            delay(if (remaining > 0) ACTIVE_POLL_MS else IDLE_POLL_MS)
        }
    }

    fun loadConversionOptions() {
        if (_options.value.voices.isNotEmpty() || _options.value.loading) return
        viewModelScope.launch {
            _options.value = ConversionOptions(loading = true)
            _options.value = try {
                val voices = api.service().voices()
                ConversionOptions(
                    voices = voices.voices,
                    defaultVoice = voices.default ?: voices.voices.firstOrNull(),
                )
            } catch (e: ServerNotConfigured) {
                ConversionOptions(error = "No server address set. Add one in Settings.")
            } catch (e: IOException) {
                ConversionOptions(error = describe(e))
            }
        }
    }

    /**
     * Import a picked epub and send it.
     *
     * The row is inserted before the upload starts, so a book the user chose shows
     * up in the library immediately and stays there if the upload fails.
     */
    fun importAndUpload(uri: Uri, voice: String?, speed: Double) {
        viewModelScope.launch {
            _importing.value = true
            try {
                val book = importer.import(uri)
                repository.insert(book.copy(voice = voice, speed = speed))
                UploadWorker.enqueue(context, book.id)
                PollWorker.enqueuePeriodic(context)
            } catch (e: Exception) {
                _message.value = e.message ?: "could not import that file"
            } finally {
                _importing.value = false
            }
        }
    }

    /** Send a book again: after an upload failure, or a job the server lost. */
    fun retry(bookId: String) {
        viewModelScope.launch {
            repository.clearJob(bookId)
            UploadWorker.enqueue(context, bookId)
            PollWorker.enqueuePeriodic(context)
        }
    }

    /** Fetch the finished files again, e.g. after a failed download. */
    fun retryDownload(bookId: String) {
        DownloadWorker.enqueue(context, bookId)
    }

    /** Stop the conversion and let the server reclaim its disk. */
    fun cancel(bookId: String) {
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
                    _message.value = describe(e)
                }
            }
            repository.clearJob(bookId)
        }
    }

    fun delete(bookId: String) {
        viewModelScope.launch {
            UploadWorker.cancel(context, bookId)
            DownloadWorker.cancel(context, bookId)
            repository.deleteBook(bookId)
        }
    }

    fun refresh() {
        viewModelScope.launch { runCatching { watcher.pollAll() } }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        private const val ACTIVE_POLL_MS = 4_000L
        private const val IDLE_POLL_MS = 30_000L

        /** Human-readable text for a network failure, for a snackbar. */
        fun describe(e: Throwable): String = when (e) {
            is ApiException -> e.detail ?: "the server returned ${e.code}"
            is ServerNotConfigured -> "No server address set. Add one in Settings."
            else -> e.message ?: "could not reach the server"
        }

        fun factory(container: AppContainer, context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(
                context.applicationContext as Application,
                container.repository,
                container.importer,
                container.watcher,
                container.api,
                container.settings,
            ) as T
        }
    }
}

/**
 * What a book is doing, collapsed into the one thing worth showing in a list.
 *
 * Derived rather than stored: every input is already a column, and a stored copy
 * would be one more thing to keep in step.
 */
enum class BookStage { LOCAL, UPLOADING, QUEUED, CONVERTING, DOWNLOADING, READY, FAILED, LOST }

fun BookEntity.stage(): BookStage = when {
    isPlayable -> BookStage.READY
    needsReupload -> BookStage.LOST
    uploadError != null -> BookStage.FAILED
    jobStatus == JobStatus.ERROR -> BookStage.FAILED
    downloadState == DownloadState.FAILED -> BookStage.FAILED
    downloadState == DownloadState.RUNNING || downloadState == DownloadState.QUEUED -> BookStage.DOWNLOADING
    jobStatus == JobStatus.DONE -> BookStage.DOWNLOADING
    jobStatus == JobStatus.RUNNING -> BookStage.CONVERTING
    jobStatus == JobStatus.QUEUED || jobStatus == JobStatus.UNKNOWN -> BookStage.QUEUED
    jobId != null -> BookStage.QUEUED
    uploadedBytes > 0 -> BookStage.UPLOADING
    else -> BookStage.LOCAL
}
