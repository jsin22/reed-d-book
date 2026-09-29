package dev.reedd.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** What the UI needs to know about the player. */
data class PlayerState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val bookId: String? = null,
    val error: String? = null,
)

/**
 * The app's one handle on [PlaybackService] -- a single instance shared by every
 * screen (`AppContainer.playerConnection`), not built fresh per reader.
 *
 * That is deliberate, not incidental: [state] is the only place "which book is
 * playing" lives, and it has to be one answer the whole app agrees on. A library
 * screen showing a "now playing" bar needs it to still be there after the reader
 * that started playback has closed; [prepare] needs the controller's *actual*
 * current item, not a copy that resets to nothing every time a new instance was
 * constructed, or reopening the book already playing would reload and restart it.
 * `connect()` is idempotent for the same reason -- whichever screen asks first
 * builds the one controller everyone then shares.
 *
 * Everything here must run on the main thread: a Media3 `MediaController` requires
 * it and throws otherwise, so callers stay on the main dispatcher rather than this
 * class hopping threads and hiding the requirement.
 *
 * Position is **polled**, as the plan specifies, rather than pushed: a player emits
 * no event as time passes, so there is nothing to subscribe to. Polling is cheap
 * because it reads an in-memory field, and pairing it with an in-memory
 * [dev.reedd.domain.ChunkIndex] keeps the whole per-tick cost to a binary search.
 */
class PlayerConnection(private val context: Context) {

    private var controller: MediaController? = null

    // Metadata for a live queue's own MediaItems (see prepareLive/appendLiveChunk) --
    // Media3 attaches metadata per item, not once for a whole queue, so every
    // chunk appended after prepareLive needs the same title/author/cover
    // reapplied for the "now playing" notification to stay correct no matter
    // which sentence happens to be current.
    private var liveTitle: String = ""
    private var liveAuthor: String? = null
    private var liveCoverUri: android.net.Uri? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
        override fun onPlaybackStateChanged(playbackState: Int) = refresh()
        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) = refresh()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = refresh()

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.value = _state.value.copy(error = error.errorCodeName)
        }
    }

    suspend fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()

        val result = suspendCancellableCoroutine { continuation ->
            future.addListener(
                { continuation.resume(runCatching { future.get() }.getOrNull()) },
                ContextCompat.getMainExecutor(context),
            )
            continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
        }

        controller = result?.also { it.addListener(listener) }
        _state.value = _state.value.copy(connected = result != null)
        refresh()
    }

    /**
     * Point the player at a book.
     *
     * Reopening the book already loaded is a no-op -- checked against the
     * controller's *actual* current item, not a locally cached id, since this
     * connection is shared app-wide (`AppContainer.playerConnection`) and has to
     * agree with whatever any other screen last did to it. Skipping the reload is
     * what lets returning to the reader of a book that is already playing not
     * restart it from the saved position.
     *
     * Switching to a genuinely *different* book always pauses first. Without that,
     * `playWhenReady` is a player-level flag that survives `setMediaItem` -- it does
     * not belong to the outgoing item -- so it would carry over and auto-start the
     * new book the instant it finished buffering. That is both halves of the same
     * bug: the old book audibly keeps going after the reader moves on, and the new
     * book starts itself before anyone asked it to.
     */
    fun prepare(bookId: String, audiobook: File, title: String, author: String?, coverPath: String?, startMs: Long) {
        val controller = controller ?: return
        // Not a safe no-op check on its own while a *live* queue for this
        // same book could be loaded: every one of its chunks shares this
        // exact bookId as its own MediaItem.mediaId too (see prepareLive's
        // own doc), which would make this wrongly believe the offline file
        // is already playing and skip loading it entirely. Confirmed live
        // as a real bug switching a book from live back to offline
        // mid-session: nothing happened, the live audio just kept going.
        // Anything switching away from a live queue calls [clearQueue]
        // first (see ReadAlongViewModel.switchToOffline), which drops the
        // current item and makes this check safe again.
        if (controller.currentMediaItem?.mediaId == bookId) return
        controller.pause()

        val item = MediaItem.Builder()
            .setMediaId(bookId)
            .setUri(audiobook.toUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(author)
                    .setArtworkUri(coverPath?.let { File(it).toUri() })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

        controller.setMediaItem(item, startMs)
        controller.prepare()
        _state.value = _state.value.copy(bookId = bookId, error = null)
        refresh()
    }

    /**
     * Drops whatever is currently loaded/queued, with nothing queued to
     * replace it -- the shared first step of [prepareLive] (a fresh live
     * queue) and, on its own, what a caller needs before [prepare] when
     * that call's own no-op check could otherwise be fooled by a live
     * queue's shared `bookId` (see [prepare]'s own doc).
     */
    fun clearQueue() {
        val controller = controller ?: return
        controller.pause()
        controller.stop()
        controller.clearMediaItems()
    }

    /**
     * Points the player at a live-only book's queue instead of one static
     * file -- see CPU_LIVE_READING_PLAN Phase 4. Unlike [prepare], this
     * always resets the queue rather than no-op'ing when the same book is
     * already loaded: an in-flight live session's `ChunkIndex`-in-progress
     * lives only in the `ReadAlongViewModel`/`LiveChunkSource` that built
     * it, and is lost the moment that ViewModel is (the reader was left
     * and reopened, say) -- reusing a queue that instance no longer has
     * any bookkeeping for would desync position tracking. A deliberate,
     * documented simplification for this phase, not an oversight: the
     * caller (`ReadAlongViewModel.start`) always re-synthesizes from its
     * last saved sentence position instead, accepting a brief restart
     * rather than a seamless handoff.
     *
     * Every appended chunk shares `bookId` as its own `MediaItem.mediaId`
     * (Media3 does not require unique ids within a playlist) specifically
     * so [clear] and [refresh]'s existing "which book is this" comparisons
     * keep working unmodified for live queues too.
     */
    fun prepareLive(bookId: String, title: String, author: String?, coverPath: String?) {
        clearQueue()
        val controller = controller ?: return
        liveTitle = title
        liveAuthor = author
        liveCoverUri = coverPath?.let { File(it).toUri() }
        controller.prepare()
        _state.value = _state.value.copy(bookId = bookId, error = null, positionMs = 0, durationMs = 0)
        refresh()
    }

    /**
     * Appends one more sentence's audio to the live queue -- gapless
     * continuation of whatever is already playing/queued, unlike
     * [prepare]'s single `setMediaItem`. The caller (`LiveChunkSource`) is
     * what tracks how this queue index maps back to a sentence index and
     * a "global" cross-chapter ms position for `ChunkIndex`; this class
     * only ever plays what it is handed.
     */
    fun appendLiveChunk(bookId: String, file: File) {
        val controller = controller ?: return
        val item = MediaItem.Builder()
            .setMediaId(bookId)
            .setUri(file.toUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(liveTitle)
                    .setArtist(liveAuthor)
                    .setArtworkUri(liveCoverUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
        controller.addMediaItem(item)
        refresh()
    }

    /** Which queue item is current -- always 0 for a plain [prepare]d
     *  single-file book. [currentPositionMs] is *within* this item for a
     *  live queue (Media3's own per-item semantics), not summed across
     *  the whole queue; combining the two into a "global" position is
     *  `LiveChunkSource`'s job, since it is the one tracking each item's
     *  own duration already (needed for `ChunkIndex` regardless). */
    fun currentMediaItemIndex(): Int = controller?.currentMediaItemIndex ?: 0

    fun play() = controller?.play()

    fun pause() = controller?.pause()

    /**
     * Stops and unloads the player, but only if it currently has this exact
     * book loaded -- a no-op for any other book, since this exists for one
     * caller: deleting a book's local content. The audio file the
     * controller has open is about to disappear from disk, and Media3 has
     * no way to notice that on its own -- without this, [state]'s `bookId`
     * (and so the library's "now playing" bar and the card's own playing/
     * paused chip) would keep pointing at a book with nothing left behind
     * it until the process happened to be killed and restarted.
     */
    fun clear(bookId: String) {
        val controller = controller ?: return
        if (controller.currentMediaItem?.mediaId != bookId) return
        controller.stop()
        controller.clearMediaItems()
        _state.value = _state.value.copy(
            isPlaying = false,
            positionMs = 0,
            durationMs = 0,
            bookId = null,
            error = null,
        )
    }

    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
        refresh()
    }

    /** Jumps to a specific live-queue item's own start -- [seekTo]'s plain
     *  ms seek only ever addresses the *current* item (Media3's own
     *  window-relative semantics), which cannot reach anywhere else in a
     *  multi-item live queue. */
    fun seekToQueueItem(index: Int) {
        controller?.seekTo(index, 0)
        refresh()
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed.coerceIn(MIN_SPEED, MAX_SPEED))
        refresh()
    }

    /** Reads the live position; called on every poll tick. */
    fun currentPositionMs(): Long = controller?.currentPosition ?: 0

    fun refresh() {
        val controller = controller
        _state.value = _state.value.copy(
            connected = controller != null,
            isPlaying = controller?.isPlaying == true,
            positionMs = controller?.currentPosition ?: 0,
            // An unprepared player reports TIME_UNSET, which is negative.
            durationMs = controller?.duration?.takeIf { it > 0 } ?: 0,
            speed = controller?.playbackParameters?.speed ?: 1f,
            // Read from the controller rather than only ever set by `prepare`, so a
            // screen that never called `prepare` itself -- the library, observing
            // this same shared connection -- still finds out which book is playing
            // as soon as it connects.
            bookId = controller?.currentMediaItem?.mediaId ?: _state.value.bookId,
        )
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    }
}
