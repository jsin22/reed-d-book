package dev.reedd.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.reedd.data.db.BookmarkType

/**
 * The word menu's Bookmark row's popup: what was tapped/selected, a color to
 * mark it with, and an optional field for what the reader wants to say
 * about it -- a bookmark and a note used to be two separate features with
 * two separate popups; see [dev.reedd.data.db.NoteEntity]'s own doc for why
 * they are now the one thing this dialog produces.
 *
 * An [AlertDialog], not a [androidx.compose.material3.ModalBottomSheet] --
 * this is a small input the reader dismisses quickly, matching
 * [dev.reedd.ui.library.CrashReportDialog]'s pattern rather than
 * [DefinitionSheet]'s (browsable, possibly long, reference content).
 */
@Composable
fun BookmarkEditorDialog(
    quotedText: String,
    initialType: BookmarkType,
    labels: Map<BookmarkType, String>,
    onSave: (type: BookmarkType, noteText: String) -> Unit,
    onCancel: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(initialType) }
    var text by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add a bookmark") },
        text = {
            Column {
                Text(
                    "“$quotedText”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    BookmarkType.entries.forEach { entry ->
                        val label = labels[entry].orEmpty().ifBlank { entry.colorName() }
                        IconButton(onClick = { type = entry }) {
                            Icon(
                                if (type == entry) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = label,
                                tint = entry.color(),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(type, text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
