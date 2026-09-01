package dev.reedd.ui.reader

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Takes over the reader's own bottom bar -- in place of [ReadAlongBar], for
 * as long as [target] is non-null -- for a tapped word or a drag-extended
 * selection, rather than floating a menu anywhere near the text at all.
 *
 * Three earlier designs were tried and dropped, each solving one problem and
 * introducing another: a `Popup` anchored via its own convenience
 * `alignment`/`offset` parameters (ambiguous exactly where that put the
 * popup without a device to check it against -- several rounds of the menu
 * winding up on top of the very word/highlight it was meant to sit clear
 * of); a `Popup` positioned via a custom `PopupPositionProvider` fixed to
 * the bottom of the whole screen (unambiguous, but wrong for a word actually
 * near the bottom -- the menu landed on it anyway); the same, but flipping
 * above/below the word adaptively (closer, but still a `Popup` floating
 * near text whose own on-screen position turned out to need care to get
 * right -- see [SelectionHandle]'s docstring for the actual bug that kept
 * causing). Taking over an already-reserved, fixed-height layout slot the
 * reader already has sidesteps that whole class of bug outright: this can
 * never end up on top of the word, the highlight, or a [SelectionHandle],
 * regardless of where on the page the text is, because it never tries to be
 * near it in the first place. The same bar serves both a tap and a
 * selection ([WordMenuTarget]'s two variants) -- a single-word selection
 * renders identically to a tap on that word.
 *
 * **Read from here** is hidden when the target sits in a passage with no audio
 * mapped to it, and **Definition** is hidden for a multi-word selection —
 * offering an action that cannot work is worse than not offering it.
 */
@Composable
fun WordMenuBar(
    target: WordMenuTarget,
    onReadFromHere: () -> Unit,
    onDefine: () -> Unit,
    onNotes: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // Same Surface/tonalElevation/navigationBarsPadding shape as ReadAlongBar,
    // the bar this replaces -- keeping the same footprint is the whole point.
    // BOTTOM_BAR_CONTENT_MIN_HEIGHT (its own doc has the confirmed bug this
    // guards against): a Row of icon-and-label buttons is not naturally the
    // same height as ReadAlongBar's Slider/plain-IconButton rows, so both
    // now claim at least this much explicitly rather than relying on their
    // own content to happen to match.
    Surface(modifier = modifier.fillMaxWidth().navigationBarsPadding(), tonalElevation = 3.dp) {
        Column(Modifier.heightIn(min = BOTTOM_BAR_CONTENT_MIN_HEIGHT).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "“${target.quotedText}”",
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (target.canReadFromHere) {
                    MenuAction(
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = "Read from here",
                        onClick = onReadFromHere,
                    )
                }
                if (target.canDefine) {
                    MenuAction(
                        icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                        label = "Definition",
                        onClick = onDefine,
                    )
                }
                MenuAction(
                    icon = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                    label = "Notes",
                    onClick = onNotes,
                )
                MenuAction(
                    icon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    label = "Copy",
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("read-d-book", target.quotedText)))
                        }
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuAction(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The definition of a tapped word, from the dictionary bundled in the app.
 *
 * A sheet rather than another popup: definitions run to several senses and would not
 * fit beside a word, and playback has stopped anyway, so this is now the thing the
 * reader is doing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefinitionSheet(state: DefinitionState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    state.definition?.word ?: state.word,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                state.definition?.pronunciation?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Say so when the word was looked up under a different form, or the
            // headword looks like a mistake rather than a lemma.
            state.definition?.takeIf { it.word != it.queried }?.let {
                Text(
                    "shown for “${it.queried}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                state.loading -> CircularProgressIndicator(
                    Modifier.padding(top = 24.dp).align(Alignment.CenterHorizontally)
                )

                state.notFound || state.definition == null -> Text(
                    "Not in the dictionary.",
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> state.definition.senses.forEachIndexed { i, sense ->
                    Column(Modifier.padding(top = 16.dp)) {
                        Text(
                            sense.partOfSpeech,
                            style = MaterialTheme.typography.labelMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "${i + 1}. ${sense.definition}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (sense.synonyms.isNotEmpty()) {
                            Text(
                                "Synonyms: " + sense.synonyms.joinToString(", "),
                                modifier = Modifier.padding(top = 2.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Text(
                "Wiktionary contributors, CC BY-SA 4.0",
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
