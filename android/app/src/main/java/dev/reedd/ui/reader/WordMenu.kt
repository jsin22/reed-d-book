package dev.reedd.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * The two-item menu that appears beside a tapped word.
 *
 * A [Popup] anchored to the word rather than a bar at the edge of the screen: the
 * menu is about *that word*, and putting it anywhere else would make the reader look
 * away from what they just touched.
 *
 * **Read from here** is hidden when the word sits in a passage with no audio mapped
 * to it — offering an action that cannot work is worse than not offering it.
 */
@Composable
fun WordMenu(
    target: TappedWordTarget,
    onReadFromHere: () -> Unit,
    onDefine: () -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        offset = IntOffset(target.anchorX, target.anchorY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.width(IntrinsicMenuWidth)) {
                if (target.sentenceIndex != null) {
                    MenuRow(
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = "Read from here",
                        onClick = onReadFromHere,
                    )
                    HorizontalDivider()
                }
                MenuRow(
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                    label = "Definition",
                    onClick = onDefine,
                )
            }
        }
    }
}

private val IntrinsicMenuWidth = 196.dp

@Composable
private fun MenuRow(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        icon()
        Text(
            label,
            modifier = Modifier.padding(start = 12.dp).fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
        )
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
            Text(
                state.definition?.word ?: state.word,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
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
                    }
                }
            }

            Text(
                "WordNet 3.0, Princeton University",
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
