package dev.reedd.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
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
    chat: List<GuideViewModel.ChatTurn>,
    onAsk: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val currentIndex = guide?.let { BookGuideView.chapterIndexOf(it, currentHref) } ?: 0

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Ask") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Story so far") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Characters") })
            }
            when {
                tab == 0 -> Ask(chat, onAsk)
                guide == null && loading -> CircularProgressIndicator(Modifier.padding(32.dp))
                guide == null -> Message(
                    "This book's guide isn't ready yet. It's prepared on the server after a book is " +
                        "added; it will appear here once you're online and it's done."
                )
                tab == 1 -> StorySoFar(guide, currentIndex)
                else -> Characters(guide, currentIndex)
            }
        }
    }
}

private val SUGGESTIONS = listOf("What just happened?", "Recap this chapter so far", "Who are the main characters so far?")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Ask(chat: List<GuideViewModel.ChatTurn>, onAsk: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val busy = chat.any { it.pending }
    val listState = rememberLazyListState()
    LaunchedEffect(chat.size, chat.lastOrNull()?.pending) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (chat.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Ask about characters or what's happening. Answers only use the book " +
                                "up to where you are, so nothing ahead is spoiled.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SUGGESTIONS.forEach { s -> SuggestionChip(onClick = { onAsk(s) }, label = { Text(s) }) }
                        }
                    }
                }
            }
            items(chat.size) { i ->
                val turn = chat[i]
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.align(Alignment.End).widthIn(max = 300.dp),
                    ) { Text(turn.question, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium) }
                    when {
                        turn.pending -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        turn.answer != null -> Text(turn.answer, style = MaterialTheme.typography.bodyMedium)
                        else -> Text(turn.error.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Ask about the book…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            IconButton(
                onClick = { onAsk(draft); draft = "" },
                enabled = draft.isNotBlank() && !busy,
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask") }
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
