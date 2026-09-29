package dev.reedd.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.reedd.data.db.BookmarkType
import org.readium.r2.shared.publication.Locator

/**
 * In-book full-text search -- a [ModalBottomSheet], same shape as
 * [NotesSheet] and the Contents sheet, backed by [ReaderViewModel.search]
 * (which wraps readium-shared's own `StringSearchService`, wired up in
 * [dev.reedd.data.readium.ReadiumComponents]).
 *
 * Each result offers both actions discussed with the reader up front:
 * "Go to this spot" ([ReaderViewModel.goTo], the same locator-based jump
 * [NotesSheet] already uses) and, for a book with audio at all,
 * "Play from here" ([ReadAlongViewModel.playFromSearchResult]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    viewModel: ReaderViewModel,
    readAlongViewModel: ReadAlongViewModel,
    canPlay: Boolean,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()

    fun goTo(locator: Locator) {
        // Same reasoning as NotesSheet's own goTo: otherwise read-along
        // follow (still on, since audio may still be playing) drags the
        // page right back to the currently-playing sentence the moment the
        // poll loop next ticks.
        readAlongViewModel.player.pause()
        viewModel.goTo(locator)
        readAlongViewModel.requestHighlight(
            resourceHref = locator.href.toString(),
            text = locator.text.highlight ?: "",
            before = locator.text.before ?: "",
            after = locator.text.after ?: "",
            // Reuses FAVORITE_QUOTE's yellow purely for its color -- this
            // highlight is never actually a bookmark (nothing is saved),
            // and PendingHighlight.type is only ever read for `.color()`
            // (ReaderScreen's own decoration LaunchedEffect), so nothing
            // downstream treats this as a real favorite-quote note. Grey
            // (BookmarkType.DEFAULT) barely stood out against the page for
            // "here's the match you just searched for" -- yellow is the
            // conventional find-highlight color for exactly this reason.
            type = BookmarkType.FAVORITE_QUOTE,
        )
        onDismiss()
    }

    fun playFrom(locator: Locator) {
        val text = locator.text.highlight ?: return
        val progression = locator.locations.progression ?: return
        readAlongViewModel.playFromSearchResult(locator.href.toString(), progression, text)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; viewModel.search(it) },
                label = { Text("Search this book") },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; viewModel.clearSearch() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            when (val current = searchState) {
                is ReaderViewModel.SearchUiState.Idle -> Text(
                    "Type to search the book's text.",
                    Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is ReaderViewModel.SearchUiState.Searching -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                is ReaderViewModel.SearchUiState.Failed -> Text(
                    current.message,
                    Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                is ReaderViewModel.SearchUiState.Results -> if (current.hits.isEmpty()) {
                    Text(
                        "No matches.",
                        Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(current.hits) { locator ->
                            SearchResultRow(
                                locator = locator,
                                canPlay = canPlay && locator.locations.progression != null,
                                onGoTo = { goTo(locator) },
                                onPlay = { playFrom(locator) },
                            )
                        }
                        if (current.truncated) {
                            item {
                                Text(
                                    "Showing the first 200 matches -- try a more specific search.",
                                    Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One hit: the chapter it's in (if known) plus a snippet with the matched
 *  text bolded -- [Locator.Text.before]/[Locator.Text.highlight]/
 *  [Locator.Text.after], already split out by the search service itself. */
@Composable
private fun SearchResultRow(
    locator: Locator,
    canPlay: Boolean,
    onGoTo: () -> Unit,
    onPlay: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                buildAnnotatedString {
                    append(locator.text.before.orEmpty().takeLast(SNIPPET_CONTEXT_CHARS))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(locator.text.highlight.orEmpty())
                    }
                    append(locator.text.after.orEmpty().take(SNIPPET_CONTEXT_CHARS))
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = locator.title?.let { title ->
            { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
        },
        trailingContent = {
            Row {
                if (canPlay) {
                    IconButton(onClick = onPlay) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play from here")
                    }
                }
                IconButton(onClick = onGoTo) {
                    Icon(Icons.Filled.Place, contentDescription = "Go to this spot in the book")
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onGoTo),
    )
}

private const val SNIPPET_CONTEXT_CHARS = 40
