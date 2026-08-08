package dev.reedd.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
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
) {
    val hasAlignment: Boolean get() = alignedChunks > 0
    /** Some sentences could not be located, so highlighting will have gaps. */
    val partiallyAligned: Boolean get() = totalChunks > 0 && alignedChunks in 1 until totalChunks - 1
}

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

    private var index: ChunkIndex = ChunkIndex.EMPTY
    private var lastSavedPositionMs = 0L

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

            if (nextIndex != _state.value.currentIndex) {
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

    /** Play from the start of a sentence, e.g. because the reader tapped it. */
    fun playFrom(chunkIndex: Int) {
        val target = index.seekPositionFor(chunkIndex) ?: return
        follower.onSeekRequested()
        player.seekTo(target)
        player.play()
        _state.value = _state.value.copy(currentIndex = chunkIndex, following = true)
        follower.onNavigated(chunkIndex)
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

    override fun onCleared() {
        // The service keeps playing on purpose: leaving the reader should not stop
        // an audiobook. Only this connection to it goes away.
        player.release()
    }

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
                PlayerConnection(container.appContext),
            ) as T
        }
    }
}
