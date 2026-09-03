package dev.reedd.domain

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * [AuthStatusMonitor.check] against a server that never answers in time.
 *
 * Confirmed live: with Tailscale connected right as the app started, resolving
 * the server's MagicDNS name could hang well past OkHttpClient's own
 * connectTimeout, leaving this stuck reporting [AuthStatus.Unknown] forever --
 * the Settings screen's "Checking" state with no way out short of restarting
 * the app. [MockResponse.Builder.headersDelay] can't reproduce the DNS hang
 * itself, but it exercises the same code path a real one would: a request that
 * simply never completes in time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AuthStatusMonitorTest {

    private lateinit var server: MockWebServer
    private lateinit var settingsStore: SettingsStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context: Application = ApplicationProvider.getApplicationContext()
        settingsStore = SettingsStore(context, CoroutineScope(SupervisorJob()))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a request that never answers reports Unreachable instead of hanging forever`() = runTest {
        settingsStore.setServer(server.url("/").toString(), "a-token")
        server.enqueue(MockResponse.Builder().headersDelay(10, TimeUnit.SECONDS).code(200).build())
        val api = ApiProvider(baseUrl = { server.url("/").toString() }, token = { "a-token" })
        val monitor = AuthStatusMonitor(settingsStore, api, checkTimeoutMs = 100)

        monitor.check()

        assertTrue(
            "expected Unreachable, got ${monitor.status.value}",
            monitor.status.value is AuthStatus.Unreachable,
        )
        assertEquals(false, monitor.isAdmin.value)
    }
}
