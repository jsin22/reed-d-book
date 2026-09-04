package dev.reedd.ui.library

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.ConversionActions
import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.DownloadState
import dev.reedd.data.db.NoteDao
import dev.reedd.data.local.EpubImporter
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.EngineDto
import dev.reedd.data.remote.JobStatus
import dev.reedd.data.remote.ServerNotConfigured
import dev.reedd.data.settings.LibraryViewSettings
import dev.reedd.data.settings.ServerSettings
import dev.reedd.data.settings.SettingsStore
import dev.reedd.di.AppContainer
import dev.reedd.diagnostics.CrashLog
import dev.reedd.diagnostics.CrashReporter
import dev.reedd.domain.AuthStatus
import dev.reedd.domain.AuthStatusMonitor
import dev.reedd.domain.ConversionWatcher
import dev.reedd.domain.LibraryFilter
import dev.reedd.domain.LibrarySort
import dev.reedd.domain.availableCategories
import dev.reedd.domain.availableGenres
import dev.reedd.domain.filteredBy
import dev.reedd.domain.librarySorted
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val authMonitor: AuthStatusMonitor,
    private val noteDao: NoteDao,
    private val conversionActions: ConversionActions = ConversionActions(context, repository, api),
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> =
        repository.books().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How the library is currently sorted/filtered, persisted across launches
     *  (see [SettingsStore.libraryViewSettings]). */
    val libraryView: StateFlow<LibraryViewSettings> =
        settingsStore.libraryViewSettings.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryViewSettings())

    /** [books], sorted and filtered per [libraryView] -- the one list the
     *  screen actually renders; [books] itself stays the raw, unsorted source
     *  so [availableCategories]/[availableGenres] can offer every tag in the
     *  library, not just the ones currently passing the filter. */
    val visibleBooks: StateFlow<List<BookEntity>> = combine(books, libraryView) { all, view ->
        all.librarySorted(view.sort).filteredBy(LibraryFilter(view.filterCategory, view.filterGenres))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every category/genre tag seen in the library today, for the filter
     *  sheet's own option list -- recomputed from the unfiltered [books] so a
     *  tag does not vanish from the sheet the moment it is the only thing
     *  selected. */
    val availableCategories: StateFlow<List<String>> = books.map { it.availableCategories() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val availableGenres: StateFlow<List<String>> = books.map { it.availableGenres() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSort(sort: LibrarySort) {
        viewModelScope.launch { settingsStore.setLibraryViewSettings(libraryView.value.copy(sort = sort)) }
    }

    fun setFilterCategory(category: String?) {
        viewModelScope.launch { settingsStore.setLibraryViewSettings(libraryView.value.copy(filterCategory = category)) }
    }

    fun setFilterGenres(genres: Set<String>) {
        viewModelScope.launch { settingsStore.setLibraryViewSettings(libraryView.value.copy(filterGenres = genres)) }
    }

    fun clearFilters() {
        viewModelScope.launch {
            settingsStore.setLibraryViewSettings(libraryView.value.copy(filterCategory = null, filterGenres = emptySet()))
        }
    }

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

    /** Shared across screens -- see [AuthStatusMonitor]. Saving a working
     *  token in Settings updates this directly, so the banner clears without
     *  waiting for this ViewModel's own next reconcile or a manual Refresh. */
    val authStatus: StateFlow<AuthStatus> = authMonitor.status

    /**
     * Whether the current token belongs to an admin -- gates the Detail screen
     * entirely (card tap/long-press) and, on that screen, whether its delete
     * button is a permanent server-side one. Defaults false, the safe side:
     * an unresolved or failed check must never grant elevated actions.
     */
    val isAdmin: StateFlow<Boolean> = authMonitor.isAdmin

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
        authMonitor.check()
        // Repair rows whose files vanished, then catch up on everything the
        // server did while the app was closed (or since the last refresh).
        runCatching { watcher.reconcile() }
        if (repository.awaitingConversion().isNotEmpty()) {
            PollWorker.enqueuePeriodic(context)
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
                // autoDownload: the user is sitting on this one right now (they
                // just picked it and hit convert), unlike a book adopted from
                // server sync or one a previous app run already submitted --
                // see BookEntity.autoDownload's own doc.
                repository.insert(book.copy(voice = voice, speed = speed, engine = engine, autoDownload = true))
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
     * The card's trash icon: wipe everything this device holds locally for the
     * book -- epub, cover, audiobook, sync file, reading/playback position,
     * alignment -- without touching the book row or the server's job (see
     * [BookRepository.deleteLocalContent] for exactly what survives and why).
     * The card stays exactly where it is, just back in the "ready to download"
     * state -- distinct from [dev.reedd.ui.detail.BookDetailViewModel.delete],
     * which removes the whole book, including from the server.
     */
    fun deleteLocalContent(bookId: String) {
        viewModelScope.launch {
            DownloadWorker.cancel(context, bookId)
            // Otherwise a book playing (or paused) right now keeps showing
            // in the "now playing" bar and its own card's chip after its
            // audio file is gone -- see PlayerConnection.clear's own doc.
            player.clear(bookId)
            // Captured before the delete: the old paths are what has to
            // actually be gone from disk afterward, and the row itself no
            // longer names them once deleteLocalContent clears the columns.
            val before = repository.get(bookId)
            repository.deleteLocalContent(bookId)
            verifyLocalContentCleared(bookId, before)
        }
    }

    /**
     * Diagnostic for confirming the trash-can icon actually leaves nothing
     * behind except notes -- remove once confirmed. Posts what survived to
     * the server's crash-report endpoint the same way every other
     * diagnostic this session has (see CrashReporter.reportDiagnostic);
     * readable straight off the `.txt` files under `server/data/crashes`,
     * no device access needed to check it.
     */
    private suspend fun verifyLocalContentCleared(bookId: String, before: BookEntity?) {
        val after = repository.get(bookId)
        val filesGone = listOfNotNull(before?.epubPath, before?.coverPath, before?.audiobookPath, before?.syncPath)
            .all { !java.io.File(it).exists() }
        val chunksLeft = repository.syncChunks(bookId).size
        val notesLeft = noteDao.observe(bookId).first().size
        CrashReporter.reportDiagnostic(
            context, "ReeddDeleteLocalContent",
            "book='${before?.title}' rowSurvived=${after != null} filesGone=$filesGone " +
                "audiobookPath=${after?.audiobookPath} syncPath=${after?.syncPath} coverPath=${after?.coverPath} " +
                "downloadState=${after?.downloadState} readingLocator=${after?.readingLocator} " +
                "playbackPositionMs=${after?.playbackPositionMs} syncOffsetMs=${after?.syncOffsetMs} " +
                "alignedChunks=${after?.alignedChunks} totalChunks=${after?.totalChunks} " +
                "alignmentVersion=${after?.alignmentVersion} chunksLeft=$chunksLeft notesLeft=$notesLeft",
        )
    }

    /**
     * The card's Retry button after an upload failure OR a conversion failure
     * (a job that came back `error`, or one the server lost -- [BookStage.LOST]).
     * One action for both, deliberately: the epub is already sitting in this
     * app's own storage from the original import (uploading never consumes or
     * moves it), so there is nothing to re-pick and nothing left for the user
     * to diagnose -- re-sending that same local file and getting a fresh job
     * is the whole of "try again" regardless of which of the two steps it
     * failed on. Reachable from the detail screen too (now that non-admins
     * have no Detail screen at all) via the same [ConversionActions.retry]
     * this delegates to.
     */
    fun retryConversion(bookId: String) {
        viewModelScope.launch { conversionActions.retry(bookId) }
    }

    /**
     * The card's cancel (X) button while a book is queued, uploading, or
     * converting -- so the card the user tapped it from can go back to
     * showing the retry it left, via [ConversionActions.cancel].
     */
    fun cancelConversion(bookId: String) {
        viewModelScope.launch {
            conversionActions.cancel(bookId)?.let { _message.value = it }
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
                container.authStatusMonitor,
                container.noteStore,
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
