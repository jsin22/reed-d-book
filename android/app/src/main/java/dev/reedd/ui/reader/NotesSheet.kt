package dev.reedd.ui.reader

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.reedd.data.db.NoteEntity
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/**
 * The book's notes, in reading order (already sorted by [dev.reedd.data.
 * db.NoteDao.observe]'s query). Mirrors the reader's Contents sheet almost
 * exactly -- a [ModalBottomSheet] listing tappable rows -- rather than a new
 * `NavHost` route, so "go to this note" is just `fragment.go(locator, ...)`
 * and a dismiss, with no navigation argument to invent for carrying a
 * locator into [ReaderRoute][dev.reedd.ReeddNavHost] (which only takes a
 * `bookId` today).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesSheet(viewModel: NotesViewModel, readAlongViewModel: ReadAlongViewModel, onDismiss: () -> Unit) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val activity = context as FragmentActivity

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (notes.isEmpty()) {
            Text(
                "No notes yet. Tap or select some text and choose Notes.",
                Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn {
                items(notes, key = { it.id }) { note ->
                    NoteRow(
                        note = note,
                        expanded = expandedId == note.id,
                        onToggleExpand = { expandedId = if (expandedId == note.id) null else note.id },
                        onGoTo = {
                            val locator = runCatching { Locator.fromJSON(JSONObject(note.locatorJson)) }.getOrNull()
                            val fragment = activity.supportFragmentManager.fragments
                                .filterIsInstance<EpubNavigatorFragment>()
                                .firstOrNull()
                            locator?.let {
                                fragment?.go(it, animated = true)
                                // Highlighted (no handles, no menu) once the
                                // navigator has actually landed on this
                                // resource -- ReaderScreen's EpubNavigator
                                // watches ReadAlongViewModel.pendingHighlight
                                // for exactly this. highlight falls back to
                                // the stored quoted text if a locator somehow
                                // lacks one (shouldn't happen for a note's
                                // own locator, but before/after are only ever
                                // cosmetic disambiguation, not required for
                                // the highlight search itself).
                                readAlongViewModel.requestHighlight(
                                    resourceHref = it.href.toString(),
                                    text = it.text.highlight ?: note.quotedText,
                                    before = it.text.before ?: "",
                                    after = it.text.after ?: "",
                                )
                            }
                            onDismiss()
                        },
                        onDelete = { viewModel.deleteNote(note.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteRow(
    note: NoteEntity,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onGoTo: () -> Unit,
    onDelete: () -> Unit,
) {
    val relativeDate = remember(note.createdAt) {
        DateUtils.getRelativeTimeSpanString(note.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    ListItem(
        headlineContent = {
            Text("“${note.quotedText}”", maxLines = if (expanded) Int.MAX_VALUE else 1)
        },
        supportingContent = {
            Column {
                Text(relativeDate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expanded) {
                    Text(note.noteText, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(note.noteText, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onGoTo) {
                    Icon(Icons.Filled.Place, contentDescription = "Go to this spot in the book")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete note")
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpand),
    )
}
