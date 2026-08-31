package dev.reedd.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.reedd.data.settings.LibraryViewSettings
import dev.reedd.domain.LibrarySort

private fun LibrarySort.label(): String = when (this) {
    LibrarySort.TITLE_ASC -> "Title (A-Z)"
    LibrarySort.AUTHOR_ASC -> "Author (A-Z)"
    LibrarySort.RECENTLY_ADDED -> "Recently added"
    LibrarySort.RECENTLY_OPENED -> "Recently opened"
}

/**
 * The library's Sort + Filter controls, opened from the TopAppBar icon.
 *
 * Filter options ([categories]/[genres]) are every tag seen anywhere in the
 * library, not just in whatever is currently showing -- see
 * [LibraryViewModel.availableCategories]/[availableGenres] -- so narrowing a
 * filter down to one match does not also delete the other options out from
 * under the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySortFilterSheet(
    view: LibraryViewSettings,
    categories: List<String>,
    genres: List<String>,
    onDismiss: () -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onGenresChange: (Set<String>) -> Unit,
    onClearFilters: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Sort", style = MaterialTheme.typography.titleLarge)
            LibrarySort.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = view.sort == option, onClick = { onSortChange(option) })
                    Text(option.label(), style = MaterialTheme.typography.bodyLarge)
                }
            }

            HorizontalDivider()

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Filter", style = MaterialTheme.typography.titleLarge)
                if (view.filterCategory != null || view.filterGenres.isNotEmpty()) {
                    TextButton(onClick = onClearFilters) { Text("Clear") }
                }
            }

            if (categories.isEmpty() && genres.isEmpty()) {
                Text(
                    "No categories or genres have been found for your books yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (categories.isNotEmpty()) {
                Text("Category", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { category ->
                        FilterChip(
                            selected = view.filterCategory == category,
                            onClick = {
                                onCategoryChange(if (view.filterCategory == category) null else category)
                            },
                            label = { Text(category) },
                        )
                    }
                }
            }

            if (genres.isNotEmpty()) {
                Text("Genre", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    genres.forEach { genre ->
                        FilterChip(
                            selected = genre in view.filterGenres,
                            onClick = {
                                onGenresChange(
                                    if (genre in view.filterGenres) view.filterGenres - genre
                                    else view.filterGenres + genre
                                )
                            },
                            label = { Text(genre) },
                        )
                    }
                }
            }
        }
    }
}
