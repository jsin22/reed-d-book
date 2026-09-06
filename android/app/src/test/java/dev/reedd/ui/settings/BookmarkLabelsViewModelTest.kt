package dev.reedd.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.reedd.data.db.BookmarkType
import dev.reedd.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [BookmarkLabelsViewModel] -- the Bookmark colors settings screen. `setLabel`
 * is fire-and-forget on `viewModelScope`: `kotlinx.coroutines.test.runTest`'s
 * virtual-time scheduler does not correctly await that kind of launch, so
 * this uses [UnconfinedTestDispatcher] plus [runBlocking] instead.
 *
 * [setUp] clears every label before each test -- see `SettingsStoreTest`'s
 * own doc on why a test class cannot assume a fresh install here: the
 * backing DataStore file is a real singleton Robolectric does not reset
 * between test classes sharing a JVM.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookmarkLabelsViewModelTest {

    private lateinit var settingsStore: SettingsStore
    private lateinit var viewModel: BookmarkLabelsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context: Application = ApplicationProvider.getApplicationContext()
        settingsStore = SettingsStore(context, CoroutineScope(SupervisorJob()))
        runBlocking { BookmarkType.entries.forEach { settingsStore.setBookmarkLabel(it, "") } }
        viewModel = BookmarkLabelsViewModel(settingsStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `labels starts with all four types mapped to an empty string`() = runBlocking {
        val labels = withTimeout(5_000) { viewModel.labels.first { it.size == BookmarkType.entries.size } }
        assertEquals(BookmarkType.entries.toSet(), labels.keys)
        assertEquals("", labels[BookmarkType.FAVORITE_QUOTE])
    }

    @Test
    fun `setLabel persists through SettingsStore and is reflected back in labels`() = runBlocking {
        viewModel.setLabel(BookmarkType.CRUCIAL_PLOT, "Twists")

        val labels = withTimeout(5_000) { viewModel.labels.first { it[BookmarkType.CRUCIAL_PLOT] == "Twists" } }
        assertEquals("Twists", labels[BookmarkType.CRUCIAL_PLOT])
        assertEquals("Twists", settingsStore.bookmarkLabels.first()[BookmarkType.CRUCIAL_PLOT])
    }
}
