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
import dev.reedd.diagnostics.CrashLog
import dev.reedd.domain.ConversionWatcher
import dev.reedd.playback.PlayerConnection
import dev.reedd.playback.PlayerState
import dev.reedd.work.DownloadWorker
import dev.reedd.work.PollWorker
import dev.reedd.work.UploadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val crashLog: CrashLog,
    private val player: PlayerConnection,
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> =
        repository.books().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Which book, if any, [player] currently has loaded, and whether it is
     * playing -- so the library can show a "now playing" bar and mark the matching
     * card, the same connection the reader itself uses (`AppContainer.playerConnection`).
     */
    val playerState: StateFlow<PlayerState> = player.state

    val settings: StateFlow<ServerSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ServerSettings())

    private val _options = MutableStateFlow(ConversionOptions())
    val options: StateFlow<ConversionOptions> = _options.asStateFlow()

    /** Transient one-line messages: an import that failed, a job that was cancelled. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Stack trace from a previous crash, shown once on the next launch. */
    val crashReport: StateFlow<String?> = crashLog.lastReport

    fun dismissCrashReport() = crashLog.dismiss()

    /** The mini-player's play/pause button. */
    fun togglePlayPause() = player.togglePlayPause()

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
        // Connecting (idempotent -- see PlayerConnection) is what lets the "now
        // playing" bar appear the moment this screen opens, rather than only after
        // a reader has connected first: PlaybackService can already be alive in the
        // background from earlier in the session.
        viewModelScope.launch { player.connect() }
    }

    /**
     * Wakes the poll loop early.
     *
     * Conflated: several nudges in a row are one wake-up, and a nudge sent while the
     * loop is busy is not lost.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Polls while a screen is open.
     *
     * The same [ConversionWatcher.pollAll] the background worker calls, just far
     * more often -- the 15-minute floor on periodic work is fine for noticing a
     * finished book but useless for a progress bar someone is watching. Both write
     * to the database and the UI only ever reads from there, so the two cannot
     * disagree.
     *
     * The wait is interruptible, which fixes a real bug: with a plain `delay`, adding
     * a book just after the loop had found nothing to do meant waiting out the whole
     * idle period before any progress appeared. Uploading now nudges the loop awake
     * immediately.
     */
    private fun startLivePolling() = viewModelScope.launch {
        while (isActive) {
            val remaining = runCatching { watcher.pollAll() }.getOrDefault(0)
            val wait = if (remaining > 0) ACTIVE_POLL_MS else IDLE_POLL_MS
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /** Poll now rather than at the end of the current wait. */
    fun pollSoon() {
        wake.trySend(Unit)
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
                // The job exists now; do not wait out the idle period before the
                // card starts showing progress.
                pollSoon()
            } catch (e: CancellationException) {
                // Never swallow cancellation: the coroutine machinery needs it.
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception. Importing runs third-party code over a
                // file chosen by the user -- Readium's parser, a bitmap decode, a
                // WorkManager enqueue -- and an Error from any of it (OutOfMemory on
                // a huge cover, NoClassDefFound) would otherwise kill the app
                // instead of showing a message. Reported as a failed import.
                _message.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "could not import that file (${e.javaClass.simpleName})"
            } finally {
                _importing.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Surface a problem raised by the UI layer, e.g. a picker that cannot open. */
    fun reportProblem(message: String) {
        _message.value = message
    }

    companion object {
        private const val ACTIVE_POLL_MS = 4_000L

        /**
         * Backstop for when nothing is converting. Short enough that a job started
         * elsewhere (the background worker, another device) is noticed reasonably
         * soon; the interruptible wait above is what makes the common case instant.
         */
        private const val IDLE_POLL_MS = 10_000L

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
                container.crashLog,
                container.playerConnection,
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
