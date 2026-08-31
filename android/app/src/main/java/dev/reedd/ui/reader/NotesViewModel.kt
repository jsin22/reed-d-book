package dev.reedd.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.db.NoteDao
import dev.reedd.data.db.NoteEntity
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
 * The persisted notes for one book.
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
) : ViewModel() {

    val notes: StateFlow<List<NoteEntity>> =
        noteDao.observe(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun saveNote(pending: PendingNote, noteText: String, spineIndex: Int) {
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
            )
        )
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch { noteDao.delete(id) }
    }

    companion object {
        fun factory(container: AppContainer, bookId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotesViewModel(bookId, container.noteStore) as T
        }
    }
}
