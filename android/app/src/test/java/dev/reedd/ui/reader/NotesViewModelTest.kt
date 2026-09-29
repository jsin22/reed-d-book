package dev.reedd.ui.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.reedd.data.db.BookmarkType
import dev.reedd.data.db.ReeddDatabase
import dev.reedd.data.db.book
import dev.reedd.data.db.inMemoryDb
import dev.reedd.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [NotesViewModel] -- the merged notes/bookmarks feature (see
 * [dev.reedd.data.db.NoteEntity]'s own doc for why there is only one of
 * these now, not a separate `BookmarksViewModel`). `saveNote`/`deleteNote`
 * are fire-and-forget on `viewModelScope`, the same trap
 * `BookmarkLabelsViewModelTest` documents: `kotlinx.coroutines.test.runTest`'s
 * virtual-time scheduler does not correctly await that kind of launch, so
 * this uses [UnconfinedTestDispatcher] plus [runBlocking] instead.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotesViewModelTest {

    private lateinit var db: ReeddDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var viewModel: NotesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = inMemoryDb()
        runBlocking { db.books().insert(book("b1")) }
        val context: Application = ApplicationProvider.getApplicationContext()
        settingsStore = SettingsStore(context, CoroutineScope(SupervisorJob()))
        // Same "cannot assume a fresh install" reasoning as
        // BookmarkLabelsViewModelTest: the Preferences DataStore file is a
        // real singleton Robolectric does not reset between test classes.
        runBlocking { BookmarkType.entries.forEach { settingsStore.setBookmarkLabel(it, "") } }
        viewModel = NotesViewModel("b1", db.notes(), settingsStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun pending() = PendingNote(
        quotedText = "a word",
        resourceHref = "c1.xhtml",
        locatorJson = """{"href":"c1.xhtml"}""",
        progression = 0.1,
    )

    @Test
    fun `saveNote persists the chosen color and remembers it as lastUsedType`() = runBlocking {
        viewModel.saveNote(pending(), "a note", BookmarkType.CRUCIAL_PLOT, spineIndex = 0)

        val saved = viewModel.notes.first { it.isNotEmpty() }.single()
        assertEquals(BookmarkType.CRUCIAL_PLOT, saved.type)
        assertEquals("a note", saved.noteText)
        assertEquals(BookmarkType.CRUCIAL_PLOT, viewModel.lastUsedType.first { it == BookmarkType.CRUCIAL_PLOT })
    }

    @Test
    fun `a bookmark with no note text saves fine, since the note is optional`() = runBlocking {
        viewModel.saveNote(pending(), "", BookmarkType.FAVORITE_QUOTE, spineIndex = 0)

        val saved = viewModel.notes.first { it.isNotEmpty() }.single()
        assertEquals("", saved.noteText)
        assertEquals(BookmarkType.FAVORITE_QUOTE, saved.type)
    }

    @Test
    fun `deleteNote removes it`() = runBlocking {
        viewModel.saveNote(pending(), "a note", BookmarkType.DEFAULT, spineIndex = 0)
        val id = viewModel.notes.first { it.isNotEmpty() }.single().id

        viewModel.deleteNote(id)

        assertTrue(viewModel.notes.first { it.isEmpty() }.isEmpty())
    }

    @Test
    fun `updateNote revises the text and color of an existing note in place`() = runBlocking {
        viewModel.saveNote(pending(), "first draft", BookmarkType.DEFAULT, spineIndex = 0)
        val saved = viewModel.notes.first { it.isNotEmpty() }.single()

        viewModel.updateNote(saved.id, "revised", BookmarkType.NEEDS_REVIEW)

        val updated = viewModel.notes.first { it.singleOrNull()?.noteText == "revised" }.single()
        assertEquals(saved.id, updated.id)
        assertEquals(BookmarkType.NEEDS_REVIEW, updated.type)
        // Same "remembers the chosen color" behavior saveNote already has.
        assertEquals(BookmarkType.NEEDS_REVIEW, viewModel.lastUsedType.first { it == BookmarkType.NEEDS_REVIEW })
    }

    @Test
    fun `startEditing and dismissEditing drive editingNote`() = runBlocking {
        viewModel.saveNote(pending(), "a note", BookmarkType.DEFAULT, spineIndex = 0)
        val saved = viewModel.notes.first { it.isNotEmpty() }.single()
        assertEquals(null, viewModel.editingNote.value)

        viewModel.startEditing(saved)
        assertEquals(saved.id, viewModel.editingNote.value?.id)

        viewModel.dismissEditing()
        assertEquals(null, viewModel.editingNote.value)
    }
}
