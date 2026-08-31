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
import org.readium.r2.navigator.Selection
import org.readium.r2.shared.publication.Locator
import java.io.File

/**
 * What the word-tap context menu is about: a single tapped word, or a
 * long-press selection -- unified so both drive the same [WordMenu] rather
 * than a tap's Compose popup and a selection's separate native toolbar
 * offering different actions (see [SelectionMenuAction] for how a selection
 * ends up here at all).
 *
 * @property sentenceIndex the sentence this word/selection belongs to, or
 *   null when unmapped to the audio -- "Read from here" is hidden in that case.
 * @property anchorX/anchorY where the menu should appear, in **view pixels**.
 */
sealed interface WordMenuTarget {
    val anchorX: Int
    val anchorY: Int
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
        override val anchorX: Int,
        override val anchorY: Int,
    ) : WordMenuTarget {
        override val quotedText: String get() = word
        override val canDefine: Boolean get() = true
    }

    data class Selection(
        /** Resolved by Readium itself ([Selection.locator]) -- a note from a
         *  selection needs no separate locator-building step, unlike [Tap]. */
        val locator: Locator,
        override val sentenceIndex: Int?,
        val isMultiWord: Boolean,
        override val anchorX: Int,
        override val anchorY: Int,
    ) : WordMenuTarget {
        override val quotedText: String get() = locator.text.highlight.orEmpty().trim()
        override val canDefine: Boolean get() = !isMultiWord
    }
}

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

        player.connect()
        player.prepare(
            bookId = bookId,
            audiobook = File(book.audiobookPath!!),
            title = book.title,
            author = book.author,
            coverPath = book.coverPath,
            startMs = book.playbackPositionMs,
        )

        // Seed from the position just handed to the player, not a live read of
        // it, for the same reason `firstTick` exists: this value is known good,
        // a poll tick's read of the player this soon after `prepare()` might not
        // be yet. `onNavigated` records it without emitting a navigation, so the
        // reader is not asked to move from wherever it already opened.
        index.indexAt(book.playbackPositionMs).takeIf { it >= 0 }?.let { seedIndex ->
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
        anchorX: Int,
        anchorY: Int,
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
            anchorX = anchorX,
            anchorY = anchorY,
        )
    }

    /**
     * A long-press selection was made: open the same menu a tap would.
     *
     * Called after Android's native selection toolbar has been suppressed
     * (see [SelectionMenuAction]) -- this is what replaces it. A selection
     * that resolves to exactly one word behaves just like a tap on that
     * word: [WordMenuTarget.Selection.canDefine] is only false when the
     * selected text spans more than one word.
     */
    fun onSelectionMade(selection: Selection) {
        val text = selection.locator.text.highlight?.trim().orEmpty()
        if (text.isEmpty()) return
        val href = selection.locator.href.toString()
        // rect is nullable -- Readium could not resolve on-screen bounds for
        // this selection. Falls back to the top-left corner rather than not
        // showing the menu at all; a rare case, and a mispositioned menu is
        // still usable.
        val rect = selection.rect
        _tappedWord.value = WordMenuTarget.Selection(
            locator = selection.locator,
            sentenceIndex = index.indexOfSelection(href, text),
            isMultiWord = text.any { it.isWhitespace() },
            anchorX = rect?.left?.toInt() ?: 0,
            anchorY = rect?.bottom?.toInt() ?: 0,
        )
    }

    /** Play from the beginning of the sentence the tapped word/selection sits in. */
    fun readFromTappedWord() {
        val target = _tappedWord.value?.sentenceIndex ?: return
        _tappedWord.value = null
        playFrom(target)
    }

    /**
     * Look the tapped word (or single-word selection) up in the bundled
     * dictionary.
     *
     * Playback stops first: reading a definition and listening at the same time is
     * not something anyone is doing on purpose.
     */
    fun defineTappedWord() {
        val target = _tappedWord.value ?: return
        _tappedWord.value = null
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

    fun dismissWordMenu() {
        _tappedWord.value = null
    }

    /** The menu's Notes row: close the menu, keep what it was about. */
    fun openNoteEditor() {
        val target = _tappedWord.value ?: return
        _tappedWord.value = null
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

        fun factory(container: AppContainer, bookId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReadAlongViewModel(
                bookId,
                container.repository,
                container.readAlongAligner,
                container.dictionary,
                container.playerConnection,
            ) as T
        }
    }
}
