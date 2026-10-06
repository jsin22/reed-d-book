package dev.reedd.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.reedd.data.remote.GuideDto
import dev.reedd.domain.BookGuideView

/**
 * The book guide, spoiler-safe: recaps of the chapters already finished and
 * the characters as of the end of the previous chapter -- never the chapter in
 * progress or anything after it (see [BookGuideView]). Works offline from the
 * copy saved on the phone.
 *
 * @param currentHref the chapter the reader has reached: the later of the page
 *   shown and the sentence being read aloud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideSheet(
    guide: GuideDto?,
    loading: Boolean,
    currentHref: String?,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val currentIndex = guide?.let { BookGuideView.chapterIndexOf(it, currentHref) } ?: 0

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Story so far") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Characters") })
            }
            when {
                guide == null && loading -> CircularProgressIndicator(Modifier.padding(32.dp))
                guide == null -> Message(
                    "This book's guide isn't ready yet. It's prepared on the server after a book is " +
                        "added; it will appear here once you're online and it's done."
                )
                tab == 0 -> StorySoFar(guide, currentIndex)
                else -> Characters(guide, currentIndex)
            }
        }
    }
}

@Composable
private fun StorySoFar(guide: GuideDto, currentIndex: Int) {
    val recaps = BookGuideView.storySoFar(guide, currentIndex)
    val current = guide.chapters.getOrNull(currentIndex)
    val stillBuilding = guide.chapters.take(currentIndex).any { it.status != "done" }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (stillBuilding) {
            item { Note("Some earlier chapters are still being prepared on the server.") }
        }
        if (recaps.isEmpty()) {
            item { Note("Recaps appear here as you finish chapters.") }
        }
        items(recaps, key = { it.index }) { chapter ->
            Column {
                Text(chapter.title.ifBlank { "Chapter ${chapter.index + 1}" },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(chapter.summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (current != null) {
            item {
                Note("You're in “${current.title.ifBlank { "Chapter ${current.index + 1}" }}”. " +
                    "Its recap appears once you've finished it.")
            }
        }
    }
}

@Composable
private fun Characters(guide: GuideDto, currentIndex: Int) {
    val characters = BookGuideView.charactersSoFar(guide, currentIndex)
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (characters.isEmpty()) {
            item { Note("Characters appear here as you meet them, once you've finished their chapter.") }
        } else {
            item { Note("As of the end of the previous chapter.") }
        }
        items(characters, key = { it.name }) { c ->
            Column {
                Text(c.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (c.aliases.isNotEmpty()) {
                    Text("also: ${c.aliases.joinToString()}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(c.description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
