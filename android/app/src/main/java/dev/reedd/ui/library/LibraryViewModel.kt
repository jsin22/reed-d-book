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
import dev.reedd.data.remote.EngineDto
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

/**
 * What the import sheet needs to offer, fetched from the server's
 * `GET /api/engines`. The app itself no longer lets the user choose an
 * engine -- it locks to `pocket_tts` (`ImportSheet.kt`) -- but the response
 * still lists every engine the server supports, so [voicesFor] can look up
 * that one engine's own voice catalog, and [engines] itself is kept so
 * `ImportSheet` can tell whether the server reported `pocket_tts` at all.
 */
data class ConversionOptions(
    val engines: List<EngineDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    fun voicesFor(engineId: String?): List<String> =
        engines.firstOrNull { it.id == engineId }?.voices.orEmpty()

    fun defaultVoiceFor(engineId: String?): String? =
        engines.firstOrNull { it.id == engineId }?.defaultVoice
}

/**
 * Whether this device can actually talk to the server as someone, checked with
 * `GET /api/me` -- distinct from [dev.reedd.ui.settings.ConnectionCheck], which
 * only runs when the user is on the Settings screen actively testing. This is
 * the passive version shown on the library itself, since a missing or wrong
 * token otherwise looks identical to "no books yet" (BUGS.md, BUG-23) -- the
 * whole point is to make that state legible without a trip to Settings first.
 */
sealed interface AuthStatus {
    /** Not checked yet, or nothing to report -- shows nothing. */
    data object Unknown : AuthStatus
    data object Ok : AuthStatus
    /** 401, or no token/server configured at all: same actionable fix either way. */
    data object NeedsToken : AuthStatus
    data class Unreachable(val reason: String) : AuthStatus
}

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

    private val _authStatus = MutableStateFlow<AuthStatus>(AuthStatus.Unknown)
    val authStatus: StateFlow<AuthStatus> = _authStatus.asStateFlow()

    /**
     * Whether the current token belongs to an admin -- gates the Detail screen
     * entirely (card tap/long-press) and, on that screen, whether its delete
     * button is a permanent server-side one. Defaults false, the safe side:
     * an unresolved or failed check must never grant elevated actions.
     */
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    /** True while a manually-triggered [refresh] is in flight, for the toolbar spinner. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        viewModelScope.launch { reconcileNow() }
        startLivePolling()
        // Connecting (idempotent -- see PlayerConnection) is what lets the "now
        // playing" bar appear the moment this screen opens, rather than only after
        // a reader has connected first: PlaybackService can already be alive in the
        // background from earlier in the session.
        viewModelScope.launch { player.connect() }
    }

    /**
     * The library's refresh button: re-checks auth and pulls in anything new
     * from the server on demand, rather than only on the next full app launch.
     *
     * Needed because [LibraryViewModel] lives for as long as its nav-graph entry
     * stays on the back stack -- visiting Settings to fix a token and pressing
     * back does not recreate it, so without an explicit trigger the library
     * would keep showing the stale pre-fix state until the whole app was
     * force-closed and reopened (see BUGS.md, BUG-23's aftermath).
     */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                reconcileNow()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun reconcileNow() {
        checkAuth()
        // Repair rows whose files vanished, then catch up on everything the
        // server did while the app was closed (or since the last refresh).
        runCatching { watcher.reconcile() }
        if (repository.awaitingConversion().isNotEmpty()) {
            PollWorker.enqueuePeriodic(context)
        }
    }

    /** See [AuthStatus] -- a direct `GET /api/me`, independent of whatever
     *  [watcher] does with jobs, so a bad token is reported even when there is
     *  nothing yet to poll or adopt. */
    private suspend fun checkAuth() {
        // Settled from DataStore directly, not ApiProvider's cached snapshot:
        // that cache is filled by a background collector started in
        // SettingsStore's own init, which is not guaranteed to have delivered
        // its first value yet by the time this runs (this call fires from
        // LibraryViewModel's own init, essentially at process start). Racing
        // that meant a real, saved token could briefly -- and then, since
        // nothing re-triggered a check, permanently -- read back as "no
        // token" on a cold launch, showing AuthStatusBanner over a perfectly
        // valid setup.
        val settled = settingsStore.current()
        if (settled.token.isNullOrBlank()) {
            _authStatus.value = AuthStatus.NeedsToken
            _isAdmin.value = false
            return
        }
        _authStatus.value = try {
            val me = api.service().me()
            _isAdmin.value = me.isAdmin
            AuthStatus.Ok
        } catch (e: ApiException) {
            _isAdmin.value = false
            if (e.isUnauthorized) AuthStatus.NeedsToken else AuthStatus.Unreachable(describe(e))
        } catch (e: ServerNotConfigured) {
            _isAdmin.value = false
            AuthStatus.NeedsToken
        } catch (e: IOException) {
            _isAdmin.value = false
            AuthStatus.Unreachable(describe(e))
        }
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
        if (_options.value.engines.isNotEmpty() || _options.value.loading) return
        viewModelScope.launch {
            _options.value = ConversionOptions(loading = true)
            _options.value = try {
                ConversionOptions(engines = api.service().engines().engines)
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
    fun importAndUpload(uri: Uri, voice: String?, speed: Double, engine: String?) {
        viewModelScope.launch {
            _importing.value = true
            try {
                val book = importer.import(uri)
                repository.insert(book.copy(voice = voice, speed = speed, engine = engine))
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

    /** The card's Download button: fetch a finished job's audiobook and sync file. */
    fun downloadBook(bookId: String) {
        DownloadWorker.enqueue(context, bookId)
    }

    /**
     * The card's trash icon: clear the audiobook/sync file this device already
     * downloaded, freeing their space, without touching the book row, the epub,
     * or the server's job. The card stays exactly where it is, just back in the
     * "ready to download" state -- distinct from [dev.reedd.ui.detail.
     * BookDetailViewModel.delete], which removes the whole book.
     */
    fun deleteDownloadedContent(bookId: String) {
        viewModelScope.launch {
            DownloadWorker.cancel(context, bookId)
            repository.deleteDownloadedContent(bookId)
        }
    }

    /**
     * The card's Retry button after an upload failure OR a conversion failure
     * (a job that came back `error`, or one the server lost -- [BookStage.LOST]).
     * One action for both, deliberately: the epub is already sitting in this
     * app's own storage from the original import (uploading never consumes or
     * moves it), so there is nothing to re-pick and nothing left for the user
     * to diagnose -- re-sending that same local file and getting a fresh job
     * is the whole of "try again" regardless of which of the two steps it
     * failed on. This mirrors [dev.reedd.ui.detail.BookDetailViewModel.
     * retryUpload] so the same action is reachable from the card directly,
     * now that non-admins have no Detail screen to reach it from otherwise.
     */
    fun retryConversion(bookId: String) {
        viewModelScope.launch {
            repository.clearJob(bookId)
            UploadWorker.enqueue(context, bookId)
            PollWorker.enqueuePeriodic(context)
            PollWorker.enqueueOnce(context)
        }
    }

    /**
     * The card's cancel (X) button while a book is queued, uploading, or
     * converting: stops the workers, asks the server to drop the job if it
     * has one, and clears this book's job state -- the epub and the row both
     * stay, so [retryConversion] can pick the card back up exactly where
     * cancel left it. A server-side failure is surfaced but not fatal to the
     * local cancel, matching [dev.reedd.ui.detail.BookDetailViewModel.cancel],
     * which this mirrors so the same action is reachable from the card.
     */
    fun cancelConversion(bookId: String) {
        viewModelScope.launch {
            UploadWorker.cancel(context, bookId)
            DownloadWorker.cancel(context, bookId)
            val book = repository.get(bookId)
            val jobId = book?.jobId
            if (jobId != null && book.jobMissing == false) {
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
enum class BookStage { LOCAL, UPLOADING, QUEUED, CONVERTING, AVAILABLE, DOWNLOADING, READY, FAILED, LOST }

fun BookEntity.stage(): BookStage = when {
    isPlayable -> BookStage.READY
    needsReupload -> BookStage.LOST
    uploadError != null -> BookStage.FAILED
    jobStatus == JobStatus.ERROR -> BookStage.FAILED
    downloadState == DownloadState.FAILED -> BookStage.FAILED
    downloadState == DownloadState.RUNNING || downloadState == DownloadState.QUEUED -> BookStage.DOWNLOADING
    // Converted, but nobody has tapped Download yet -- distinct from DOWNLOADING
    // (a transfer actually in flight), so the card knows to offer the button.
    jobStatus == JobStatus.DONE -> BookStage.AVAILABLE
    jobStatus == JobStatus.RUNNING -> BookStage.CONVERTING
    jobStatus == JobStatus.QUEUED || jobStatus == JobStatus.UNKNOWN -> BookStage.QUEUED
    jobId != null -> BookStage.QUEUED
    uploadedBytes > 0 -> BookStage.UPLOADING
    else -> BookStage.LOCAL
}
