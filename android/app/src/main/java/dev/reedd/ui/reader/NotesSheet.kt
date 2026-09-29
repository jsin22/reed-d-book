package dev.reedd.ui.reader

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.reedd.data.db.NoteEntity
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/**
 * The book's notes and bookmarks -- one merged, reading-order-sorted list
 * (already sorted by [dev.reedd.data.db.NoteDao.observe]'s own query; see
 * [dev.reedd.data.db.NoteEntity]'s own doc for why there is no longer a
 * separate bookmark table to sort). Mirrors the reader's Contents sheet
 * almost exactly -- a [ModalBottomSheet] listing tappable rows -- rather
 * than a new `NavHost` route, so "go to this spot" is just
 * `fragment.go(locator, ...)` and a dismiss, with no navigation argument to
 * invent for carrying a locator into [ReaderRoute][dev.reedd.ReeddNavHost]
 * (which only takes a `bookId` today).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesSheet(
    viewModel: NotesViewModel,
    readAlongViewModel: ReadAlongViewModel,
    onEdit: (NoteEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val labels by viewModel.labels.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as FragmentActivity

    // Same pattern as goTo below: acting on a row closes the sheet, rather
    // than leaving it open behind the editor dialog.
    fun edit(note: NoteEntity) {
        onEdit(note)
        onDismiss()
    }

    fun goTo(note: NoteEntity) {
        val locator = runCatching { Locator.fromJSON(JSONObject(note.locatorJson)) }.getOrNull()
        val fragment = activity.supportFragmentManager.fragments
            .filterIsInstance<EpubNavigatorFragment>()
            .firstOrNull()
        locator?.let {
            // Otherwise read-along follow (still on, since audio is still
            // playing) drags the page right back to the currently-playing
            // sentence the moment the poll loop next ticks -- confirmed
            // live as "the page goes to the bookmark, then snaps straight
            // back". Same reasoning as defineTappedWord's own player.pause():
            // looking at a passage and having the page yanked out from under
            // you a moment later is not something anyone wants.
            readAlongViewModel.player.pause()
            fragment?.go(it, animated = true)
            // Highlighted (no handles, no menu) once the navigator has
            // actually landed on this resource -- ReaderScreen's
            // EpubNavigator watches ReadAlongViewModel.pendingHighlight for
            // exactly this. Falls back to the stored quoted text if a
            // locator somehow lacks one (shouldn't happen for a note's own
            // locator, but before/after are only ever cosmetic
            // disambiguation, not required for the highlight search
            // itself), and paints in this entry's own color.
            readAlongViewModel.requestHighlight(
                resourceHref = it.href.toString(),
                text = it.text.highlight ?: note.quotedText,
                before = it.text.before ?: "",
                after = it.text.after ?: "",
                type = note.type,
            )
        }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (notes.isEmpty()) {
            Text(
                "No notes or bookmarks yet. Tap or select some text and choose Bookmark.",
                Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn {
                items(notes, key = { it.id }) { note ->
                    NoteRow(
                        note = note,
                        label = labels[note.type].orEmpty(),
                        onEdit = { edit(note) },
                        onGoTo = { goTo(note) },
                        onDelete = { viewModel.deleteNote(note.id) },
                    )
                }
            }
        }
    }
}

/**
 * One row -- a note, a plain colored bookmark, or both at once (a color
 * with a note attached). [label] is blank until the reader names this
 * color on the Bookmark colors settings screen; the leading swatch is what
 * tells rows apart until then. The headline falls back to the color's
 * label (or "Bookmark") when there is no quoted passage -- a bookmark
 * carried over from before the merge (see [dev.reedd.data.db.NoteEntity]'s
 * own doc) never had one.
 *
 * Tapping the row itself reopens the same editor a new note is created in
 * ([BookmarkEditorDialog], via [onEdit]) prefilled with this note's own
 * text and color -- explicit request: "when I click on a note it should
 * bring up the original screen... this will allow me to update or edit."
 * That dialog already shows the full note text in an editable field, which
 * is also what used to make an expand/collapse toggle here worth having --
 * removed as redundant once editing does the same job better.
 */
@Composable
private fun NoteRow(
    note: NoteEntity,
    label: String,
    onEdit: () -> Unit,
    onGoTo: () -> Unit,
    onDelete: () -> Unit,
) {
    val relativeDate = remember(note.createdAt) {
        DateUtils.getRelativeTimeSpanString(note.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    val headline = note.quotedText.ifBlank { label.ifBlank { "Bookmark" } }
    ListItem(
        leadingContent = {
            Box(Modifier.size(20.dp).clip(CircleShape).background(note.type.color()))
        },
        headlineContent = {
            Text(if (note.quotedText.isBlank()) headline else "“$headline”", maxLines = 1)
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(label.takeIf { it.isNotBlank() && note.quotedText.isNotBlank() }, relativeDate)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (note.noteText.isNotBlank()) {
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
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
    )
}
