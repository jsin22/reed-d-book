package dev.reedd.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.reedd.MainActivity

/**
 * Holds the one ExoPlayer that plays audiobooks.
 *
 * A [MediaSessionService] rather than a player owned by the reader screen, because
 * the plan requires background playback: an audiobook has to keep going when the
 * screen is off or the app is backgrounded, and the system will kill a plain
 * background thread doing that. The service also gets the lockscreen and
 * notification transport controls, and headset and Bluetooth buttons, from the
 * media session with no extra code.
 *
 * There is deliberately no playlist. A book is one `.m4b`; the "chapters" a reader
 * navigates are positions inside that single file, resolved through the sync
 * mapping rather than through separate media items.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // Speech, not music: tells the system this is spoken word, which
                    // affects ducking and some devices' audio processing.
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pause when headphones are unplugged, rather than blaring out loud.
            .setHandleAudioBecomingNoisy(true)
            .build()

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openReaderIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Swiping the app away should not silently strand a playing audiobook, but it
     * should not leave a paused one running as a service either.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Tapping the notification returns to the app rather than starting a new task. */
    private fun openReaderIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
