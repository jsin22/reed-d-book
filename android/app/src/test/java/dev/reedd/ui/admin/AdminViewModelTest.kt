package dev.reedd.ui.admin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.reedd.data.BookRepository
import dev.reedd.data.db.inMemoryDb
import dev.reedd.data.remote.ApiProvider
import dev.reedd.domain.ConversionWatcher
import dev.reedd.notify.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AdminViewModel.pushApk]/[AdminViewModel.apkStatus] -- "Push Update" on
 * the Admin screen. `AdminViewModel` had no test coverage at all before
 * this; scoped here to just the new behavior rather than backfilling
 * everything else in the same class.
 *
 * `Dispatchers.setMain(UnconfinedTestDispatcher())`: `refresh`/`pushApk`
 * are fire-and-forget on `viewModelScope` (`Dispatchers.Main.immediate`),
 * which under Robolectric resolves to a real Android `Looper` that needs
 * pumping to resume a coroutine after a real (MockWebServer) network call
 * -- a real, confirmed-live trap: `kotlinx.coroutines.test.runTest`'s
 * virtual-time scheduler does not correctly await this kind of fire-and-
 * forget launch, so `runBlocking` is used for the test bodies instead.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AdminViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: AdminViewModel

    private fun json(body: String) =
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()

    /** [AdminViewModel.refresh] fires four sequential GETs in this exact
     *  order; every call to it (including the automatic one in `init`)
     *  needs all four queued or the next request just hangs waiting. */
    private fun enqueueRefreshCycle(apkStatusBody: String = """{"live":null,"pending":null}""") {
        server.enqueue(json("""{"jobs":[]}"""))
        server.enqueue(json("""{"users":[]}"""))
        server.enqueue(json("""{"ok":true}"""))
        server.enqueue(json(apkStatusBody))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context: Application = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        val api = ApiProvider(baseUrl = { server.url("/").toString() }, token = { null })
        val db = inMemoryDb()
        val repository = BookRepository(db.books(), db.sync(), api)
        val watcher = ConversionWatcher(context, repository, api, Notifications(context))

        enqueueRefreshCycle()
        viewModel = AdminViewModel(api, watcher)
        // Drains init{}'s own refresh() before any test enqueues more
        // responses -- otherwise its four sequential requests and a
        // test's could interleave against the same FIFO queue.
        runBlocking { withTimeout(5_000) { viewModel.apkStatus.first { it != null } } }
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh populates apkStatus from the server`() = runBlocking {
        enqueueRefreshCycle(
            """{"live":{"filename":"reedd-debug.apk","bytes":123,"built_at":"2026-09-05T00:00:00Z"},"pending":null}"""
        )

        viewModel.refresh()

        val status = withTimeout(5_000) { viewModel.apkStatus.first { it?.live != null } }
        assertEquals("reedd-debug.apk", status?.live?.filename)
        assertEquals(123L, status?.live?.bytes)
    }

    @Test
    fun `pushApk calls the endpoint, then refreshes so the new live version shows`() = runBlocking {
        server.enqueue(
            json("""{"pushed":{"filename":"reedd-debug.apk","bytes":456,"built_at":"2026-09-05T00:01:00Z"}}""")
        )
        enqueueRefreshCycle(
            """{"live":{"filename":"reedd-debug.apk","bytes":456,"built_at":"2026-09-05T00:01:00Z"},"pending":null}"""
        )

        viewModel.pushApk()

        val status = withTimeout(5_000) { viewModel.apkStatus.first { it?.live?.bytes == 456L } }
        assertEquals("reedd-debug.apk", status?.live?.filename)
        assertFalse(viewModel.pushingApk.value)
    }

    @Test
    fun `pushApk surfaces a server error as a message rather than failing silently`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(404)
                .setHeader("Content-Type", "application/json")
                .body("""{"detail":"no .apk found in the build directory"}""")
                .build()
        )

        viewModel.pushApk()

        val message = withTimeout(5_000) { viewModel.message.first { it != null } }
        assertEquals("no .apk found in the build directory", message)
        assertFalse(viewModel.pushingApk.value)
    }
}
