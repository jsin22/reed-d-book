package dev.reedd.data.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.reedd.data.db.BookmarkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SettingsStore.bookmarkLabels]/[SettingsStore.setBookmarkLabel] -- scoped
 * to this new behavior; `SettingsStore` as a whole has no broader test
 * coverage yet (a pre-existing gap, not backfilled here).
 *
 * `preferencesDataStore`'s backing file is a real singleton keyed by file
 * path, which Robolectric does not reset between different test *classes*
 * sharing the same JVM (confirmed live: a label set here leaked into
 * `BookmarkLabelsViewModelTest`) -- [setUp] clears every label explicitly
 * so this class is never order-dependent on what another test class wrote.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        val context: Application = ApplicationProvider.getApplicationContext()
        store = SettingsStore(context, CoroutineScope(SupervisorJob()))
        runBlocking { BookmarkType.entries.forEach { store.setBookmarkLabel(it, "") } }
    }

    @Test
    fun `a fresh install has no bookmark labels, but the map still names all four types`() = runTest {
        val labels = store.bookmarkLabels.first()
        assertEquals(BookmarkType.entries.toSet(), labels.keys)
        assertTrue(labels.values.all { it.isEmpty() })
    }

    @Test
    fun `setBookmarkLabel is readable back, and only changes its own type`() = runTest {
        store.setBookmarkLabel(BookmarkType.FAVORITE_QUOTE, "Favorite lines")

        val labels = store.bookmarkLabels.first()
        assertEquals("Favorite lines", labels[BookmarkType.FAVORITE_QUOTE])
        assertEquals("", labels[BookmarkType.CRUCIAL_PLOT])
    }

    @Test
    fun `a blank label clears a previously-set one back to empty`() = runTest {
        store.setBookmarkLabel(BookmarkType.DEFAULT, "Miscellaneous")
        assertEquals("Miscellaneous", store.bookmarkLabels.first()[BookmarkType.DEFAULT])

        store.setBookmarkLabel(BookmarkType.DEFAULT, "   ")

        assertEquals("", store.bookmarkLabels.first()[BookmarkType.DEFAULT])
    }

    @Test
    fun `a label is trimmed before being stored`() = runTest {
        store.setBookmarkLabel(BookmarkType.NEEDS_REVIEW, "  To re-read  ")
        assertEquals("To re-read", store.bookmarkLabels.first()[BookmarkType.NEEDS_REVIEW])
    }
}
