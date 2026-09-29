package dev.reedd.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.reedd.diagnostics.FeedbackReporter
import kotlinx.coroutines.launch

/**
 * "Send feedback" -- a bug report or feature request, reachable from both
 * the general Settings screen ([bookContext] null, no book open) and a
 * book's own reader settings sheet ([bookContext] filled in automatically
 * by the caller -- see [FeedbackReporter.submit]'s own doc for why the
 * reader never has to type which book or mode they were in).
 *
 * Unlike `BookmarkEditorDialog` (a purely local write that closes the
 * instant Save is tapped), this one waits for the server to actually
 * accept the submission before closing: a reader who just wrote up a bug
 * report should know whether it went anywhere, not just that a button was
 * tapped -- feedback lost silently to a bad connection is worse than a
 * bookmark that saves a moment later than it looked like it did.
 */
@Composable
fun FeedbackDialog(
    bookContext: String?,
    onSubmit: suspend (type: FeedbackReporter.Type, message: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(FeedbackReporter.Type.BUG) }
    var message by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun send() {
        if (message.isBlank() || submitting) return
        submitting = true
        error = null
        scope.launch {
            val ok = runCatching { onSubmit(type, message) }.getOrDefault(false)
            submitting = false
            if (ok) onDismiss() else error = "Could not send -- check your connection and try again."
        }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Send feedback") },
        text = {
            Column {
                bookContext?.let {
                    Text(
                        "About: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FeedbackReporter.Type.entries.forEach { entry ->
                        FilterChip(
                            selected = type == entry,
                            onClick = { type = entry },
                            label = { Text(entry.label) },
                            enabled = !submitting,
                        )
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("What's on your mind?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    enabled = !submitting,
                )
                error?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (submitting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(4.dp))
            } else {
                TextButton(onClick = ::send, enabled = message.isNotBlank()) { Text("Send") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") }
        },
    )
}
