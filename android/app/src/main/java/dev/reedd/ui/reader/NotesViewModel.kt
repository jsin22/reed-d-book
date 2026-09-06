package dev.reedd.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.db.BookmarkType
import dev.reedd.data.db.NoteDao
import dev.reedd.data.db.NoteEntity
import dev.reedd.data.settings.SettingsStore
import dev.reedd.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A menu target ([WordMenuTarget]) turned into something ready to become a
 * [NoteEntity] -- built in [ReaderScreen] itself (not here, and not in
 * [ReadAlongViewModel]), the one place with both the target *and* the open
 * [org.readium.r2.shared.publication.Publication] a [WordMenuTarget.Tap]
 * needs to resolve a real [org.readium.r2.shared.publication.Locator] from
 * (see [dev.reedd.domain.NoteLocators.tapLocator]); a selection's own
 * locator needs no such resolution.
 */
data class PendingNote(
    val quotedText: String,
    val resourceHref: String,
    val locatorJson: String,
    val progression: Double?,
)

/**
 * The persisted notes for one book -- and, since a bookmark is just a note
 * with a color and no text (see [dev.reedd.data.db.NoteEntity]'s own doc for
 * why the two merged), bookmarks too. There is no separate "bookmark"
 * concept anywhere above [NoteDao] any more.
 *
 * Deliberately separate from [ReadAlongViewModel] (scoped to the audio, per
 * its own docstring) and from [ReaderViewModel] (owns the open publication):
 * a note is neither -- it is book-scoped data with its own lifetime, read
 * and written through [NoteDao] directly, the same way [dev.reedd.di.
 * AppContainer.syncStore] is exposed straight to whatever needs it rather
 * than wrapped in a repository that only ever touches the `books` table.
 */
class NotesViewModel(
    private val bookId: String,
    private val noteDao: NoteDao,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val notes: StateFlow<List<NoteEntity>> =
        noteDao.observe(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What the reader named each [BookmarkType] color on the Bookmark
     *  colors settings screen -- shown in the editor's color picker and in
     *  the notes list. Eagerly started: both consumers show it the instant
     *  the reader opens, not only after their own first recomposition
     *  happens to subscribe. */
    val labels: StateFlow<Map<BookmarkType, String>> =
        settingsStore.bookmarkLabels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** The color last chosen in the editor -- what a fresh editor starts
     *  preselected to, so picking the same color repeatedly (the common
     *  case) does not cost a tap every time. */
    val lastUsedType: StateFlow<BookmarkType> =
        settingsStore.lastBookmarkType.stateIn(viewModelScope, SharingStarted.Eagerly, BookmarkType.DEFAULT)

    suspend fun saveNote(pending: PendingNote, noteText: String, type: BookmarkType, spineIndex: Int) {
        noteDao.insert(
            NoteEntity(
                bookId = bookId,
                noteText = noteText,
                quotedText = pending.quotedText,
                locatorJson = pending.locatorJson,
                resourceHref = pending.resourceHref,
                spineIndex = spineIndex,
                progression = pending.progression,
                createdAt = System.currentTimeMillis(),
                type = type,
            )
        )
        settingsStore.setLastBookmarkType(type)
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch { noteDao.delete(id) }
    }

    companion object {
        fun factory(container: AppContainer, bookId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotesViewModel(bookId, container.noteStore, container.settings) as T
        }
    }
}
