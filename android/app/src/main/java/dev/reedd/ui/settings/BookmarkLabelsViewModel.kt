package dev.reedd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.db.BookmarkType
import dev.reedd.data.settings.SettingsStore
import dev.reedd.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Bookmark colors settings screen: what each of the four bookmark
 * colors is named, if anything -- see [SettingsStore.bookmarkLabels]' own
 * doc for why a fresh install has none. Global, not per-book, same as
 * [SettingsStore.lastBookmarkType]: the four colors mean the same thing in
 * every book.
 */
class BookmarkLabelsViewModel(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val labels: StateFlow<Map<BookmarkType, String>> =
        settingsStore.bookmarkLabels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setLabel(type: BookmarkType, label: String) {
        viewModelScope.launch { settingsStore.setBookmarkLabel(type, label) }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BookmarkLabelsViewModel(container.settings) as T
        }
    }
}
