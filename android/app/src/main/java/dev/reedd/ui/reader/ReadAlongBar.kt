package dev.reedd.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
 * The transport controls in the reader.
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
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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

            if (!state.following) {
                Text(
                    "Not following the audio. Tap the crosshair to jump back to it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.partiallyAligned) {
                Text(
                    "${state.alignedChunks} of ${state.totalChunks} sentences could be matched to the page; the rest will not highlight.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
