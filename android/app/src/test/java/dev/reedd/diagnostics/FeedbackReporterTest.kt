package dev.reedd.diagnostics

import dev.reedd.data.remote.ApiProvider
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeedbackReporterTest {

    private lateinit var server: MockWebServer

    private fun api() = ApiProvider(baseUrl = { server.url("/").toString() }, token = { null })

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse.Builder().code(200).body("").build())
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `posts the message and type to the feedback endpoint`() = runBlocking {
        FeedbackReporter.submit(api(), FeedbackReporter.Type.BUG, "the seek bar jumps back")

        val request = server.takeRequest()
        assertEquals("/api/feedback", request.url.encodedPath)
        assertEquals("bug", request.url.queryParameter("feedback_type"))
        val body = request.body!!.utf8()
        assertTrue(body.contains("type:      Bug"))
        assertTrue(body.contains("the seek bar jumps back"))
    }

    @Test
    fun `attaches the book context when given, omits it otherwise`() = runBlocking {
        FeedbackReporter.submit(api(), FeedbackReporter.Type.BUG, "a message", bookContext = "Five Survive (live mode)")
        assertTrue(server.takeRequest().body!!.utf8().contains("book:      Five Survive (live mode)"))

        server.enqueue(MockResponse.Builder().code(200).body("").build())
        FeedbackReporter.submit(api(), FeedbackReporter.Type.OTHER, "a message")
        assertFalse(server.takeRequest().body!!.utf8().contains("book:"))
    }

    @Test
    fun `attaches the breadcrumb trail`() = runBlocking {
        Breadcrumbs.clear()
        Breadcrumbs.leave("reader opened: 'Five Survive'")

        FeedbackReporter.submit(api(), FeedbackReporter.Type.FEATURE, "a request")

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("breadcrumbs:"))
        assertTrue(body.contains("reader opened: 'Five Survive'"))
    }

    @Test
    fun `an unreachable server throws rather than swallowing the failure`() {
        server.close() // nothing listening -- unlike CrashReporter's fire-and-forget, this must surface it
        var threw = false
        try {
            runBlocking { FeedbackReporter.submit(api(), FeedbackReporter.Type.OTHER, "a message") }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("expected submit() to propagate a network failure, not swallow it", threw)
    }
}
