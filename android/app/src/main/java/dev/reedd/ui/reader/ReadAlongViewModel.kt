package dev.reedd.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.dictionary.Dictionary
import dev.reedd.di.AppContainer
import dev.reedd.diagnostics.Breadcrumbs
import dev.reedd.domain.ChunkIndex
import dev.reedd.domain.FollowController
import dev.reedd.domain.ReadAlongAligner
import dev.reedd.playback.PlayerConnection
import dev.reedd.playback.PlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** A note's passage, waiting to be highlighted once [resourceHref] is the
 *  resource actually loaded in the navigator -- see [ReadAlongViewModel.
 *  pendingHighlight]'s own docstring. */
data class PendingHighlight(
    val resourceHref: String,
    val text: String,
    val before: String,
    val after: String,
)

/** A definition being shown, or being looked up. */
data class DefinitionState(
    val word: String,
    val loading: Boolean = false,
    val definition: dev.reedd.data.dictionary.Definition? = null,
    val notFound: Boolean = false,
)

/** What the read-along UI renders, and what the reader screen highlights from. */
data class ReadAlongState(
    val available: Boolean = false,
    val player: PlayerState = PlayerState(),
    /** Index into [ChunkIndex], or -1 before the first sentence. */
    val currentIndex: Int = -1,
    val following: Boolean = true,
    val alignedChunks: Int = 0,
    val totalChunks: Int = 0,
    val syncOffsetMs: Long = 0,
    val aligning: Boolean = false,
)

/**
 * Drives read-along: playback, the position poll, and which sentence is current.
 *
 * Kept apart from [ReaderViewModel], which owns the open publication, because the
 * two have different lifetimes and different reasons to change. This one is about
 * the audio; that one is about the book.
 *
 * The position poll is the loop the plan calls for. It reads an in-memory field
 * from the player and does a binary search over an in-memory [ChunkIndex], so the
 * per-tick cost is negligible; nothing is written to the database except a
 * throttled bookmark of the playback position.
 */
class ReadAlongViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val aligner: ReadAlongAligner,
    private val dictionary: Dictionary,
    val player: PlayerConnection,
    /** Start audio immediately once loaded -- the library's play button, not
     *  every way of reaching the reader. See `ReeddNavHost.kt`'s
     *  `ReaderRoute.autoPlay` for where this comes from. */
    private val autoPlay: Boolean = false,
) : ViewModel() {

    private val follower = FollowController(following = true)

    private val _state = MutableStateFlow(ReadAlongState())
    val state: StateFlow<ReadAlongState> = _state.asStateFlow()

    /**
     * Emits the sentence index the page should move to.
     *
     * A separate signal from [state] on purpose: navigating is an *event*: replaying
     * it because something else in the state changed would yank the page back.
     */
    private val _navigateTo = MutableStateFlow<Int?>(null)
    val navigateTo: StateFlow<Int?> = _navigateTo.asStateFlow()

    /**
     * Word-tap and drag-selection interaction -- armed handles, their live
     * position, and the resulting [WordMenuTarget] -- pulled out into its
     * own state machine; see [WordSelectionController]'s own doc. This
     * ViewModel's own [tappedWord]/[selectionHandles]/[isHandleDragActive]/
     * [onWordTapped]/[armHandles]/[onHandleDragStart]/[onHandleMoved]/
     * [onExtendResolved]/[onHandleDragEnd] are thin delegates to it, kept
     * here so nothing above this class -- `ReaderScreen` included -- had to
     * change.
     */
    private val selection = WordSelectionController(chunkIndex = ::chunkIndex)

    /**
     * The word the reader tapped, if its menu is open.
     *
     * Separate from [state] because it is not a property of playback: it is a
     * transient selection that any subsequent tap replaces.
     */
    val tappedWord: StateFlow<WordMenuTarget?> = selection.tappedWord

    private val _definition = MutableStateFlow<DefinitionState?>(null)
    val definition: StateFlow<DefinitionState?> = _definition.asStateFlow()

    /**
     * The tap/selection a note is being written for, once the reader has
     * picked "Notes" off the menu -- separate from [tappedWord] so choosing
     * Notes can close the menu (clearing [tappedWord], which is what makes
     * the popup and its highlight/selection cleanup disappear) without also
     * losing what the note is about.
     */
    private val _pendingNoteTarget = MutableStateFlow<WordMenuTarget?>(null)
    val pendingNoteTarget: StateFlow<WordMenuTarget?> = _pendingNoteTarget.asStateFlow()

    /**
     * A passage to highlight (display-only, no handles/menu) once its
     * resource has loaded, requested by [NotesSheet]'s "go to this spot"
     * button. `ReaderScreen`'s `EpubNavigator` watches this and
     * `fragment.currentLocator`'s href together: `fragment.go(locator)` can
     * land on a resource already open (no reload, highlight straight away)
     * or one that still needs to load its HTML document first (wait for the
     * href to match before searching it), and this alone can't tell which.
     * Cleared once painted, by [clearPendingHighlight].
     */
    private val _pendingHighlight = MutableStateFlow<PendingHighlight?>(null)
    val pendingHighlight: StateFlow<PendingHighlight?> = _pendingHighlight.asStateFlow()

    fun requestHighlight(resourceHref: String, text: String, before: String, after: String) {
        _pendingHighlight.value = PendingHighlight(resourceHref, text, before, after)
    }

    fun clearPendingHighlight() {
        _pendingHighlight.value = null
    }

    /** See [selection]'s own doc. */
    val selectionHandles: StateFlow<SelectionHandles?> = selection.selectionHandles

    /** See [selection]'s own doc. */
    val isHandleDragActive: Boolean get() = selection.isHandleDragActive

    private var index: ChunkIndex = ChunkIndex.EMPTY
    private var lastSavedPositionMs = 0L

    /**
     * True until [pollPosition] has processed one tick.
     *
     * `player.currentPositionMs()` right after `prepare()` cannot be trusted the
     * instant this ViewModel is created: [player] is shared app-wide and its
     * `MediaController` talks to the session asynchronously, so a poll tick that
     * lands before that round trip completes can read a stale position -- often
     * whatever a *previously* open book left behind. Treating that as a genuine
     * sentence change would fight the page [dev.reedd.ui.reader.ReaderViewModel]
     * already opened at (its own saved locator), which is exactly BUGS.md BUG-10's
     * successor: the reader "waking up at the beginning" despite a saved position
     * elsewhere. The first tick only establishes the baseline; navigation is
     * trusted from the second tick on, by which point the read has settled in
     * every case that matters here.
     */
    private var firstTick = true

    fun chunkIndex(): ChunkIndex = index

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        val book = repository.get(bookId) ?: return
        if (!book.isPlayable) return

        loadIndex(book)
        if (book.needsAlignment) realign(book)

        // The saved position is wherever playback last happened to be paused
        // -- almost never a sentence's own start -- but the sentence it falls
        // inside gets highlighted whole either way, so resuming from the raw
        // saved value played from *inside* the highlighted sentence rather
        // than its beginning. playFrom()/seekPositionFor already snap a
        // deliberate "read from here" or a next/previous-sentence tap to the
        // sentence's own start; this makes the very first resume do the same
        // rather than being the one path that does not.
        val resumeStartMs = index.indexAt(book.playbackPositionMs).takeIf { it >= 0 }
            ?.let { index.seekPositionFor(it) }
            ?: book.playbackPositionMs

        player.connect()
        player.prepare(
            bookId = bookId,
            audiobook = File(book.audiobookPath!!),
            title = book.title,
            author = book.author,
            coverPath = book.coverPath,
            startMs = resumeStartMs,
        )
        // The library's play button, not every way of reaching the reader --
        // see this class's own constructor doc. prepare() only loads/buffers;
        // without this, the reader opened paused regardless of how it was
        // reached, requiring an extra manual tap on the transport controls
        // every time, first sentence or a hundredth chapter in alike.
        if (autoPlay) player.play()

        // Seed from the position just handed to the player, not a live read of
        // it, for the same reason `firstTick` exists: this value is known good,
        // a poll tick's read of the player this soon after `prepare()` might not
        // be yet. `onNavigated` records it without emitting a navigation, so the
        // reader is not asked to move from wherever it already opened.
        index.indexAt(resumeStartMs).takeIf { it >= 0 }?.let { seedIndex ->
            _state.value = _state.value.copy(currentIndex = seedIndex)
            follower.onNavigated(seedIndex)
        }

        _state.value = _state.value.copy(available = true)
        pollPosition()
    }

    private suspend fun loadIndex(book: BookEntity) {
        val chunks = repository.syncChunks(bookId)
        index = ChunkIndex(chunks, offsetMs = book.syncOffsetMs)
        _state.value = _state.value.copy(
            alignedChunks = chunks.count { it.isAligned },
            totalChunks = chunks.size,
            syncOffsetMs = book.syncOffsetMs,
        )
    }

    /**
     * Align a book that has none, e.g. one downloaded before the aligner existed.
     *
     * Silent and best-effort: playback works either way, so a failure here must not
     * stop the audio starting.
     */
    private suspend fun realign(book: BookEntity) {
        _state.value = _state.value.copy(aligning = true)
        val result = runCatching { aligner.alignExisting(bookId, File(book.epubPath)) }.getOrNull()
        if (result != null) {
            repository.setAlignment(bookId, result.aligned, result.total)
            loadIndex(book)
        }
        _state.value = _state.value.copy(aligning = false)
    }

    /**
     * The poll loop.
     *
     * Ticks fast while playing and slowly while paused -- a paused player still
     * moves when the user scrubs, so the highlight has to keep up, but there is no
     * reason to wake up ten times a second to watch a position that is not changing.
     */
    private suspend fun pollPosition() {
        while (viewModelScope.isActive) {
            val playerState = player.state.value
            val position = player.currentPositionMs()
            val nextIndex = index.indexAt(position)

            if (firstTick) {
                // See the KDoc on firstTick: this read is not trusted for
                // navigation, only start()'s seeded baseline is, until the
                // player's own state has had one tick to settle.
                firstTick = false
            } else if (nextIndex != _state.value.currentIndex) {
                _state.value = _state.value.copy(currentIndex = nextIndex)
                if (follower.onSentenceChanged(nextIndex)) {
                    _navigateTo.value = nextIndex
                }
            }
            player.refresh()
            _state.value = _state.value.copy(player = player.state.value, following = follower.isFollowing)

            if (playerState.isPlaying) saveProgress(position)
            delay(if (playerState.isPlaying) PLAYING_TICK_MS else IDLE_TICK_MS)
        }
    }

    /** Bookmark the position, but not on every tick: once every few seconds is plenty. */
    private suspend fun saveProgress(positionMs: Long) {
        if (kotlin.math.abs(positionMs - lastSavedPositionMs) < SAVE_INTERVAL_MS) return
        lastSavedPositionMs = positionMs
        repository.updatePlaybackPosition(bookId, positionMs)
    }

    // -- controls ------------------------------------------------------------

    fun togglePlayPause() {
        player.togglePlayPause()
        player.refresh()
        _state.value = _state.value.copy(player = player.state.value)
    }

    fun setSpeed(speed: Float) {
        Breadcrumbs.leave("playback speed changed to ${speed}x")
        player.setSpeed(speed)
        _state.value = _state.value.copy(player = player.state.value)
    }

    /**
     * Play from the start of a sentence, e.g. because the reader tapped it.
     *
     * Explicitly requests the page move there (`_navigateTo.value =
     * chunkIndex`, the same thing `resumeFollowing()` does) rather than
     * leaving it to the poll loop's own "did the sentence change" check. That
     * check compares real playback position against `_state.value.currentIndex`
     * -- which this function itself sets to `chunkIndex` immediately, before
     * `player.seekTo()` has actually landed there (ExoPlayer's seek completes
     * asynchronously). The first poll tick after this runs would then see
     * *stale, pre-seek* audio position against an *already-updated*
     * currentIndex, "detect" a change to the old position, and send the page
     * there instead -- and once the seek genuinely completes and playback
     * position catches up to the real target, currentIndex already matches it,
     * so the poll loop never corrects it either. Confirmed real, not
     * theoretical: this was the actual cause behind BUG-17's repeated
     * "still broken" reports, none of which were actually in `ChunkIndex` at
     * all despite three rounds of fixes there.
     */
    fun playFrom(chunkIndex: Int) {
        val target = index.seekPositionFor(chunkIndex) ?: return
        follower.onSeekRequested()
        player.seekTo(target)
        player.play()
        _state.value = _state.value.copy(currentIndex = chunkIndex, following = true)
        _navigateTo.value = chunkIndex
    }

    // -- word tap / drag-selection ---------------------------------------------
    // Thin delegates to [selection]; see [WordSelectionController]'s own doc
    // for what each of these actually does.

    fun onWordTapped(word: String, resourceHref: String?, blockText: String, offset: Int, readingProgression: Double?) =
        selection.onWordTapped(word, resourceHref, blockText, offset, readingProgression)

    fun armHandles(word: String, left: Float, top: Float, right: Float, bottom: Float, resourceHref: String, progression: Double?) =
        selection.armHandles(word, left, top, right, bottom, resourceHref, progression)

    fun onHandleDragStart() = selection.onHandleDragStart()

    fun onHandleMoved(isStart: Boolean, dxCss: Float, dyCss: Float) = selection.onHandleMoved(isStart, dxCss, dyCss)

    fun onExtendResolved(result: ExtendedSelection) = selection.onExtendResolved(result)

    fun onHandleDragEnd() = selection.onHandleDragEnd()

    /** Play from the beginning of the sentence the tapped word/selection sits in. */
    fun readFromTappedWord() {
        val target = tappedWord.value?.sentenceIndex ?: return
        dismissWordMenu()
        playFrom(target)
    }

    /**
     * Look the tapped word (or selection) up in the bundled dictionary.
     *
     * Playback stops first: reading a definition and listening at the same time is
     * not something anyone is doing on purpose.
     */
    fun defineTappedWord() {
        val target = tappedWord.value ?: return
        dismissWordMenu()
        player.pause()
        _definition.value = DefinitionState(word = target.quotedText, loading = true)
        viewModelScope.launch {
            val found = runCatching { dictionary.lookup(target.quotedText) }.getOrNull()
            _definition.value = DefinitionState(
                word = target.quotedText,
                loading = false,
                definition = found,
                notFound = found == null,
            )
        }
    }

    /** Also clears [selectionHandles] -- once the menu is gone, its handles
     *  (if any were armed) have nothing left to extend. */
    fun dismissWordMenu() = selection.dismiss()

    /** The menu's Notes row: close the menu, keep what it was about. */
    fun openNoteEditor() {
        val target = tappedWord.value ?: return
        dismissWordMenu()
        _pendingNoteTarget.value = target
    }

    fun dismissNoteEditor() {
        _pendingNoteTarget.value = null
    }

    fun dismissDefinition() {
        _definition.value = null
    }

    fun nextSentence() {
        index.nextIndex(player.currentPositionMs())?.let(::playFrom)
    }

    fun previousSentence() {
        index.previousIndex(player.currentPositionMs())?.let(::playFrom)
    }

    fun seekTo(positionMs: Long) {
        follower.onSeekRequested()
        player.seekTo(positionMs)
    }

    /** The reader dragged the page: keep playing, stop moving it for them. */
    fun onUserDragged() {
        if (!follower.isFollowing) return
        follower.onUserDragged()
        _state.value = _state.value.copy(following = false)
    }

    fun resumeFollowing() {
        follower.resume()
        _state.value = _state.value.copy(following = true)
        val current = _state.value.currentIndex
        if (current >= 0) _navigateTo.value = current
    }

    fun toggleFollowing() {
        if (follower.isFollowing) {
            follower.stop()
            _state.value = _state.value.copy(following = false)
        } else {
            resumeFollowing()
        }
    }

    /** Consumed by the reader once it has moved the page. */
    fun onNavigationHandled(chunkIndex: Int) {
        follower.onNavigated(chunkIndex)
        _navigateTo.value = null
    }

    fun setSyncOffset(offsetMs: Long) {
        viewModelScope.launch {
            repository.updateSyncOffset(bookId, offsetMs)
            index = index.withOffset(offsetMs)
            _state.value = _state.value.copy(syncOffsetMs = offsetMs)
        }
    }

    // No onCleared override: player is shared app-wide now (see AppContainer.playerConnection),
    // so leaving the reader must not release it -- Library's "now playing" bar and
    // the next book's own guard against auto-starting both depend on the
    // connection, and its state, still being there. viewModelScope itself is still
    // cancelled automatically, which is what actually stops this book's own
    // position poll.

    companion object {
        private const val PLAYING_TICK_MS = 100L
        private const val IDLE_TICK_MS = 400L

        /** How far playback must move before the bookmark is rewritten. */
        private const val SAVE_INTERVAL_MS = 5_000L

        const val OFFSET_STEP_MS = 25L
        const val OFFSET_LIMIT_MS = 2_000L

        fun factory(container: AppContainer, bookId: String, autoPlay: Boolean = false) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReadAlongViewModel(
                bookId,
                container.repository,
                container.readAlongAligner,
                container.dictionary,
                container.playerConnection,
                autoPlay,
            ) as T
        }
    }
}
