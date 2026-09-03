package dev.reedd.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.dictionary.Dictionary
import dev.reedd.di.AppContainer
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

/**
 * What the word-tap context menu is about: a single tapped word, or a
 * selection extended by dragging a handle out from one -- unified so both
 * drive the same [WordMenuBar]. There is no long-press-to-select gesture: a
 * selection always starts as a [Tap] (see `ReadAlongViewModel.armHandles`),
 * because Readium's `InputListener` has no long-press timing signal to
 * build one from -- `onDrag` fires identically for the start of an ordinary
 * page-turn swipe and a deliberate hold, so extending is instead always an
 * explicit drag on a small handle (see [SelectionTextResolver]).
 *
 * No on-screen position is carried here (an earlier version did, so
 * [WordMenuBar] could float itself beside the word) -- [WordMenuBar] now
 * takes over the reader's own bottom bar, in place of the transport
 * controls, for as long as this is non-null, rather than floating anywhere
 * near the text at all. That sidesteps an entire class of real bugs several
 * rounds of floating-popup positioning ran into: the reader's bottom bar is
 * a fixed, already-reserved layout slot, so the menu can never end up on
 * top of the word, the highlight, or a [SelectionHandle] regardless of
 * where on the page the text is.
 *
 * @property sentenceIndex the sentence this word/selection belongs to, or
 *   null when unmapped to the audio -- "Read from here" is hidden in that case.
 */
sealed interface WordMenuTarget {
    val sentenceIndex: Int?
    /** The word, or the selected passage -- what a note quotes, and what
     *  Definition looks up. */
    val quotedText: String
    /** False only for a multi-word selection: looking up a whole passage in
     *  a dictionary is not a thing, so the row is omitted entirely rather
     *  than shown disabled -- same convention as "Read from here" already
     *  uses for [sentenceIndex]. */
    val canDefine: Boolean

    val canReadFromHere: Boolean get() = sentenceIndex != null

    data class Tap(
        val word: String,
        /** Kept (not discarded after resolving [sentenceIndex], the way this
         *  used to work) so a Notes tap can build a real [org.readium.r2.
         *  shared.publication.Locator] from it -- see [dev.reedd.domain.
         *  NoteLocators.tapLocator]. */
        val resourceHref: String?,
        val blockText: String,
        val offset: Int,
        val progression: Double?,
        override val sentenceIndex: Int?,
    ) : WordMenuTarget {
        override val quotedText: String get() = word
        override val canDefine: Boolean get() = true
    }

    /** Built once a handle-drag ends, from [SelectionHandles] -- see
     *  [dev.reedd.domain.NoteLocators.extendedLocator] for how a note's
     *  Locator is built from [text]/[before]/[after] rather than from a
     *  Readium-resolved one, since there is no such thing here any more. */
    data class ExtendedSelection(
        val text: String,
        val before: String,
        val after: String,
        val resourceHref: String,
        val progression: Double?,
        override val sentenceIndex: Int?,
    ) : WordMenuTarget {
        override val quotedText: String get() = text
        override val canDefine: Boolean get() = !text.any { it.isWhitespace() }
    }
}

/**
 * The live state of a selection's two drag handles, in CSS px -- see
 * [ReadAlongViewModel.selectionHandles]. Plain data: no Readium types, so
 * this stays buildable/testable without a fragment.
 */
/**
 * [startX]/[startY]/[endX]/[endY] (and their `Bottom` counterparts) are the
 * **raw query position** -- where the finger has actually dragged to,
 * accumulated purely from touch deltas by [ReadAlongViewModel.
 * onHandleMoved] and never touched by a JS result. [displayStartX]/
 * [displayStartY]/[displayEndX]/[displayEndY] (and their `Bottom`s) are the
 * separate, JS-*resolved* caret position from the last successful
 * [SelectionTextResolver.extend] call, used only for rendering the handle
 * precisely snapped to a character boundary.
 *
 * These used to be the same fields -- [ReadAlongViewModel.onExtendResolved]
 * overwrote the raw position with the resolved one, and the next
 * [ReadAlongViewModel.onHandleMoved] tick's delta then accumulated on top of
 * *that*. A real, confirmed-live bug: near a line wrap, `caretRangeFromPoint`
 * can resolve two very different lines for two query points only a pixel
 * apart, so a resolved snap could land 15-20px away (one line height) from
 * where the finger truly was -- silently relocating the accumulator's own
 * baseline. Every following delta then compounded against that wrong
 * baseline instead of the physical touch position, which is what made
 * dragging feel imprecise and, worse, made a selection fail to visibly
 * shrink when dragged back: the very next resolved snap could teleport the
 * query point forward again before the shrink ever got sent. Keeping the
 * two separate means the query position sent to [SelectionTextResolver.
 * extend] -- and the baseline every future delta accumulates against --
 * always tracks the real finger, regardless of how the JS chooses to
 * resolve any single query.
 */
data class SelectionHandles(
    val startX: Float,
    val startY: Float,
    val startBottom: Float,
    val endX: Float,
    val endY: Float,
    val endBottom: Float,
    val displayStartX: Float,
    val displayStartY: Float,
    val displayStartBottom: Float,
    val displayEndX: Float,
    val displayEndY: Float,
    val displayEndBottom: Float,
    val text: String,
    val before: String,
    val after: String,
    val resourceHref: String,
    val progression: Double?,
)

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
     * The word the reader tapped, if its menu is open.
     *
     * Separate from [state] because it is not a property of playback: it is a
     * transient selection that any subsequent tap replaces.
     */
    private val _tappedWord = MutableStateFlow<WordMenuTarget?>(null)
    val tappedWord: StateFlow<WordMenuTarget?> = _tappedWord.asStateFlow()

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

    /**
     * Where a selection's two drag handles are right now, in **CSS pixels**
     * (same unit [dev.reedd.ui.reader.TapTextResolver.TappedWord]'s rect
     * uses) -- armed from a tapped word's own rect ([armHandles]), then kept
     * live as the reader drags either end ([onHandleMoved]/[onExtendResolved]).
     * Non-null for exactly as long as a tap's menu could plausibly still be
     * extended into a selection, i.e. from the moment a word is tapped until
     * the next tap/dismiss -- not the same lifetime as [tappedWord], which a
     * handle-drag deliberately leaves untouched until [onHandleDragEnd].
     */
    private val _selectionHandles = MutableStateFlow<SelectionHandles?>(null)
    val selectionHandles: StateFlow<SelectionHandles?> = _selectionHandles.asStateFlow()

    /**
     * True from [onHandleDragStart] to [onHandleDragEnd] -- the window during
     * which a handle is actively under a finger. Readium's own
     * `InputListener.onDrag` (see `ReaderScreen`'s `input` object) fires for
     * *any* drag it sees, including one that starts on a
     * [dev.reedd.ui.reader.SelectionHandle] sitting on top of the WebView --
     * that callback calls [dismissWordMenu] unconditionally, which was
     * silently wiping [_selectionHandles] and [_tappedWord] out from under an
     * in-progress handle drag the instant it began (the drag then looked like
     * it "did nothing," because the state it was updating had already been
     * nulled). `ReaderScreen` checks this before calling [dismissWordMenu]
     * from that listener.
     */
    private var draggingHandle = false
    val isHandleDragActive: Boolean get() = draggingHandle

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

    /**
     * A word was tapped: open its menu.
     *
     * Deliberately does nothing else. Playback does not move, the page does not
     * scroll, no other state changes — a tap is cheap and easily accidental, and it
     * is also how pages are turned, so it only offers.
     *
     * The sentence is resolved now rather than when the menu item is chosen, so
     * "Read from here" can be hidden for a passage that has no audio mapped to it
     * instead of failing after the reader picks it.
     */
    fun onWordTapped(
        word: String,
        resourceHref: String?,
        blockText: String,
        offset: Int,
        readingProgression: Double?,
    ) {
        _tappedWord.value = WordMenuTarget.Tap(
            word = word,
            resourceHref = resourceHref,
            blockText = blockText,
            offset = offset,
            progression = readingProgression,
            // readingProgression: where the reader is looking on screen, not
            // audio playback position -- see ChunkIndex.indexOfTap for why that
            // distinction matters.
            sentenceIndex = index.indexOfTap(resourceHref, blockText, offset, readingProgression),
        )
    }

    /**
     * Seeds the drag handles from the just-tapped word's own rect (CSS px,
     * same as [dev.reedd.ui.reader.TapTextResolver.TappedWord]'s) -- no JS
     * needed, since a single word never wraps a line, so its own corners
     * already are the two handle positions. Called right after
     * [onWordTapped], from the same tap.
     */
    fun armHandles(word: String, left: Float, top: Float, right: Float, bottom: Float, resourceHref: String, progression: Double?) {
        _selectionHandles.value = SelectionHandles(
            startX = left, startY = top, startBottom = bottom,
            endX = right, endY = top, endBottom = bottom,
            displayStartX = left, displayStartY = top, displayStartBottom = bottom,
            displayEndX = right, displayEndY = top, displayEndBottom = bottom,
            text = word,
            before = "",
            after = "",
            resourceHref = resourceHref,
            progression = progression,
        )
    }

    /**
     * Optimistic, JS-free update of whichever handle is being dragged, for
     * instant visual feedback while [onExtendResolved]'s JS round trip is
     * still in flight. Both `y`/`bottom` are set to the same raw drag
     * position here -- a placeholder until the next resolved rect arrives.
     *
     * Takes a **delta**, not an absolute position, and accumulates it against
     * [_selectionHandles]' own current value -- not the caller's. A real
     * on-device bug: the caller (`ReaderScreen`'s `SelectionHandle.onDrag`)
     * used to compute the new absolute position itself, from a `handles`
     * value captured in its own Compose closure. During a fast, continuous
     * drag, touch events can arrive faster than Compose recomposes, so that
     * closure was often one or more ticks stale -- each tick then based its
     * "new" position on an old baseline, silently dropping every delta in
     * between. The visible result was a handle that twitched slightly but
     * never actually travelled the full distance dragged, so the extended
     * range it fed into `extend()` never meaningfully grew past the original
     * word. Reading and writing [_selectionHandles] here instead -- a plain
     * Kotlin property, not something recomposition-timing-dependent -- means
     * every tick accumulates against the true latest value regardless of
     * whether Compose has caught up yet.
     */
    /** A handle drag began: see [draggingHandle]'s own docstring for why
     *  [ReaderScreen] needs to know this before its own drag listener fires. */
    fun onHandleDragStart() {
        draggingHandle = true
    }

    fun onHandleMoved(isStart: Boolean, dxCss: Float, dyCss: Float) {
        val handles = _selectionHandles.value ?: return
        _selectionHandles.value = if (isStart) {
            val x = handles.startX + dxCss
            val y = handles.startY + dyCss
            handles.copy(startX = x, startY = y, startBottom = y)
        } else {
            val x = handles.endX + dxCss
            val y = handles.endY + dyCss
            handles.copy(endX = x, endY = y, endBottom = y)
        }
    }

    /**
     * The result of a throttled [SelectionTextResolver.extend] call: the
     * newly-extended text/context, and where to *render* each handle now.
     *
     * Deliberately only touches the `display*` fields, never [SelectionHandles.
     * startX]/[SelectionHandles.startY]/[SelectionHandles.endX]/
     * [SelectionHandles.endY] themselves -- see [SelectionHandles]' own
     * docstring for the real bug that came from this function overwriting the
     * raw query position with a JS-resolved one that could land a line away
     * from where the finger actually was.
     */
    fun onExtendResolved(result: ExtendedSelection) {
        val handles = _selectionHandles.value ?: return
        _selectionHandles.value = handles.copy(
            displayStartX = result.startX, displayStartY = result.startY, displayStartBottom = result.startBottom,
            displayEndX = result.endX, displayEndY = result.endY, displayEndBottom = result.endBottom,
            text = result.text, before = result.before, after = result.after,
        )
    }

    /** A handle drag ended: turn the current [selectionHandles] snapshot into
     *  a menu target, the same way [onWordTapped] does for a plain tap. */
    fun onHandleDragEnd() {
        draggingHandle = false
        val handles = _selectionHandles.value ?: return
        _tappedWord.value = WordMenuTarget.ExtendedSelection(
            text = handles.text,
            before = handles.before,
            after = handles.after,
            resourceHref = handles.resourceHref,
            progression = handles.progression,
            sentenceIndex = index.indexOfSelection(handles.resourceHref, handles.text),
        )
    }

    /** Play from the beginning of the sentence the tapped word/selection sits in. */
    fun readFromTappedWord() {
        val target = _tappedWord.value?.sentenceIndex ?: return
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
        val target = _tappedWord.value ?: return
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
    fun dismissWordMenu() {
        _tappedWord.value = null
        _selectionHandles.value = null
    }

    /** The menu's Notes row: close the menu, keep what it was about. */
    fun openNoteEditor() {
        val target = _tappedWord.value ?: return
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
