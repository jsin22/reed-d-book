package dev.reedd.diagnostics

import dev.reedd.data.remote.ApiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A real crash on a new user's phone (2026-09-04) never reached the server:
 * the on-device banner was shown, the upload had not yet succeeded, and
 * dismissing it deleted the report anyway. This is the coverage that gap
 * never had -- see [CrashLog.dismiss]'s own doc for the fix.
 *
 * Robolectric, not a plain JUnit test: [CrashLog.upload] logs via
 * `android.util.Log`, which is unmocked (and throws) under a bare JVM unit
 * test -- swallowed by its own `runCatching`, which silently turned every
 * upload into a reported failure regardless of the real HTTP result.
 */
@RunWith(RobolectricTestRunner::class)
class CrashLogTest {

    private lateinit var server: MockWebServer
    private lateinit var tmpDir: File
    private var cleared = 0

    private fun file(name: String, text: String = "read-d-book crash report\n...") =
        File(tmpDir, name).apply { writeText(text) }

    private fun log(): CrashLog {
        val api = ApiProvider(baseUrl = { server.url("/").toString() }, token = { null })
        // Mirrors the real CrashReporter.clear(context): actually deletes
        // the files, so a test can tell "dismiss() didn't call clear()"
        // apart from "clear() ran but happened to leave the file alone".
        return CrashLog(reports = { tmpDir.listFiles()?.sortedBy { it.name } ?: emptyList() },
            api = api, clear = { cleared++; tmpDir.listFiles()?.forEach { it.delete() } })
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmpDir = File.createTempFile("crash-log-test", "").also { it.delete(); it.mkdirs() }
        cleared = 0
    }

    @After
    fun tearDown() {
        server.close()
        tmpDir.deleteRecursively()
    }

    /**
     * [CrashLog.start] launches on [kotlinx.coroutines.Dispatchers.IO]
     * internally (real work, real MockWebServer round trip), so a
     * `TestScope`/virtual-time dispatcher would not actually wait for it --
     * joining the child job it creates is what does.
     */
    private fun runStart(crashLog: CrashLog) {
        val job = Job()
        crashLog.start(CoroutineScope(job))
        runBlocking { job.children.toList().forEach { it.join() } }
    }

    @Test
    fun `no pending reports means nothing to show and nothing to clear`() {
        val crashLog = log()
        runStart(crashLog)
        assertNull(crashLog.lastReport.value)
        assertEquals(0, cleared)
    }

    @Test
    fun `a pending report that uploads successfully is shown and cleared`() {
        file("crash-1.txt", "the report text")
        server.enqueue(MockResponse.Builder().code(202).body("ok").build())
        val crashLog = log()

        runStart(crashLog)

        assertEquals("the report text", crashLog.lastReport.value)
        assertEquals(1, cleared)
    }

    @Test
    fun `a pending report that fails to upload is still shown, but not cleared`() {
        file("crash-1.txt", "the report text")
        server.enqueue(MockResponse.Builder().code(500).body("nope").build())
        val crashLog = log()

        runStart(crashLog)

        assertEquals("the report text", crashLog.lastReport.value)
        assertEquals(0, cleared)
    }

    @Test
    fun `dismiss hides the banner but never deletes an unconfirmed report`() {
        // The actual bug: the server never sees this crash. Simulates the
        // real scenario -- upload fails (server briefly unreachable), then
        // the user taps past the banner before a retry ever succeeds.
        file("crash-1.txt", "the report text")
        server.enqueue(MockResponse.Builder().code(500).body("nope").build())
        val crashLog = log()
        runStart(crashLog)
        assertEquals("the report text", crashLog.lastReport.value)

        crashLog.dismiss()

        assertNull("the banner is gone", crashLog.lastReport.value)
        assertEquals("dismiss() must never be what deletes an unsent report", 0, cleared)
        assertTrue("the file itself must still be on disk for the next launch to retry",
            File(tmpDir, "crash-1.txt").exists())
    }

    @Test
    fun `dismiss after a successful send is a no-op, not a double-clear`() {
        file("crash-1.txt", "the report text")
        server.enqueue(MockResponse.Builder().code(202).body("ok").build())
        val crashLog = log()
        runStart(crashLog)
        assertEquals(1, cleared)

        crashLog.dismiss()

        assertFalse("dismiss must not call clear a second time", cleared == 2)
        assertEquals(1, cleared)
    }
}
