package dev.reedd.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.reedd.playback.PlayerConnection

/**
 * Both [ReadAlongBar] and [WordMenuBar] occupy the same `bottomBar` slot in
 * `ReaderScreen.kt`'s `Scaffold` and are meant to be interchangeable without
 * the reading area ever resizing -- see [ReadAlongBar]'s own "deliberately a
 * fixed height" note. That guarantee held for *within* each composable, but
 * not *between* them: their natural content heights differed by a few
 * pixels (a `Slider` plus a `Row` of plain `IconButton`s versus a `Row` of
 * icon-and-label `Column`s is not the same height by construction, just
 * coincidentally close), which was enough to trip the fragment's own resize
 * detection every time a tap showed or hid the word menu -- confirmed live,
 * via `CrashReporter.reportDiagnostic` logging: `navigator height changed:
 * 2032 -> 2038` on the exact frame a word was tapped. That resize forced a
 * full repagination (`ReaderScreen.kt`'s `onSizeChanged` re-submits
 * preferences on any height change, by design, for a different bug --
 * BUG-13), which visibly reflowed the page -- the reported "first couple of
 * lines disappear" -- while `SelectionHandlesOverlay`'s own coordinates,
 * captured once at tap time, never got the chance to re-resolve against the
 * new layout -- the reported "handles land a few lines below the word".
 * Both bars now claim at least this much height explicitly, rather than
 * whatever their own content happens to need, so the fragment never sees a
 * height change from this at all.
 */
val BOTTOM_BAR_CONTENT_MIN_HEIGHT = 132.dp

/**
 * The transport controls in the reader.
 *
 * Deliberately a fixed height. Notes that appear and disappear here — "not following
 * the audio", how many sentences were matched — changed the bar's height and shifted
 * the whole page under the reader on every tap, which was far more distracting than
 * the information was useful. Follow state is shown by the crosshair's tint instead,
 * and alignment quality lives on the book's detail screen.
 *
 * Previous/next move by **sentence** rather than by a fixed number of seconds,
 * which is what the sync mapping makes possible and is far more useful in a book:
 * "play that line again" instead of "go back 15 seconds and hope".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAlongBar(
    state: ReadAlongState,
    onTogglePlay: () -> Unit,
    onPreviousSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var speedMenu by remember { mutableStateOf(false) }
    // While dragging, the slider follows the finger rather than the player, or it
    // would fight the position poll and jump back under the thumb.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }

    val duration = state.player.durationMs.takeIf { it > 0 } ?: 1L
    val position = if (scrubbing) scrubPosition.toLong() else state.player.positionMs

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // The activity is edge-to-edge, and Material 3's Scaffold insets its
            // *content* slot but places bottomBar flush against the bottom of the
            // window -- a bottom bar is expected to inset itself, the way
            // NavigationBar does. Without this the transport controls sit under the
            // system navigation bar and are partly untappable (BUGS.md, BUG-1).
            .navigationBarsPadding(),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.heightIn(min = BOTTOM_BAR_CONTENT_MIN_HEIGHT).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Slider(
                value = position.coerceIn(0, duration).toFloat(),
                onValueChange = {
                    scrubbing = true
                    scrubPosition = it
                },
                onValueChangeFinished = {
                    scrubbing = false
                    onSeek(scrubPosition.toLong())
                },
                valueRange = 0f..duration.toFloat(),
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${position.asClock()} / ${state.player.durationMs.asClock()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPreviousSentence) {
                        Icon(Icons.Filled.FastRewind, contentDescription = "Previous sentence")
                    }
                    FilledIconButton(onClick = onTogglePlay) {
                        Icon(
                            if (state.player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.player.isPlaying) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = onNextSentence) {
                        Icon(Icons.Filled.FastForward, contentDescription = "Next sentence")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Lights up only while following, so its state is obvious at a
                    // glance: the page stops moving the moment you scroll away.
                    IconButton(onClick = onToggleFollow) {
                        Icon(
                            Icons.Filled.MyLocation,
                            contentDescription = if (state.following) "Stop following the audio" else "Follow the audio",
                            tint = if (state.following) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                    IconButton(onClick = { speedMenu = true }) {
                        Icon(Icons.Filled.Speed, contentDescription = "Playback speed")
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        PlayerConnection.SPEEDS.forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("${speed}x") },
                                onClick = {
                                    onSpeed(speed)
                                    speedMenu = false
                                },
                                trailingIcon = {
                                    if (state.player.speed == speed) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(12.dp))
                                    }
                                },
                            )
                        }
                    }
                }
            }

        }
    }
}

/** `h:mm:ss`, dropping the hour for anything under one. */
internal fun Long.asClock(): String {
    if (this <= 0) return "0:00"
    val totalSeconds = this / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
