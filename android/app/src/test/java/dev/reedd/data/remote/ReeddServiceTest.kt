package dev.reedd.data.remote

import dev.reedd.Fixtures
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The HTTP contract, exercised against a mock server rather than a live one.
 */
class ReeddServiceTest {

    private lateinit var server: MockWebServer
    private var token: String? = null

    private fun provider() = ApiProvider(
        baseUrl = { server.url("/").toString() },
        token = { token },
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        token = null
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build()
        )
    }

    @Test
    fun `polling a job hits the documented path and parses the manifest`() = runBlocking {
        enqueueJson(Fixtures.read("job_running.json"))

        val job = provider().service().job("fe6aefba-c4d8-445d-b826-f9607f8e3b96")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/jobs/fe6aefba-c4d8-445d-b826-f9607f8e3b96", request.url.encodedPath)
        assertEquals(32, job.progress)
        assertEquals("00d 00h 00m 11s", job.eta)
    }

    @Test
    fun `a 404 becomes an ApiException the reconciler can recognise`() = runBlocking {
        // This is the case that matters: the server was wiped while the app was
        // closed, and the book must be marked stale instead of polled forever.
        enqueueJson("""{"detail":"no such job: abc"}""", code = 404)

        try {
            provider().service().job("abc")
            fail("expected an ApiException")
        } catch (e: ApiException) {
            assertTrue(e.isNotFound)
            assertEquals("no such job: abc", e.detail)
        }
        Unit
    }

    @Test
    fun `a 401 is reported as an auth problem, not a generic failure`() = runBlocking {
        enqueueJson("""{"detail":"invalid or missing API token"}""", code = 401)
        try {
            provider().service().listJobs()
            fail("expected an ApiException")
        } catch (e: ApiException) {
            assertTrue(e.isUnauthorized)
            assertEquals("invalid or missing API token", e.detail)
        }
        Unit
    }

    @Test
    fun `the token is sent as a bearer header when one is configured`() = runBlocking {
        token = "s3cret"
        enqueueJson("""{"jobs":[]}""")

        provider().service().listJobs()

        assertEquals("Bearer s3cret", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `health is sent without the token, so a host can be checked before credentials`() = runBlocking {
        token = "s3cret"
        enqueueJson("""{"status":"ok","data_dir":"/srv/data","broker":"redis://127.0.0.1:6379/0"}""")

        val health = provider().service().health()

        assertEquals("ok", health.status)
        // The server exempts /api/health from auth; the client mirrors that so a
        // wrong token still produces a useful "found the server" result.
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `no header is sent when no token is configured`() = runBlocking {
        enqueueJson("""{"jobs":[]}""")
        provider().service().listJobs()
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `listing jobs parses a mixed set`() = runBlocking {
        enqueueJson(
            """{"jobs":[${Fixtures.read("job_done.json")},${Fixtures.read("job_error.json")}]}"""
        )

        val jobs = provider().service().listJobs(limit = 10).jobs

        assertEquals(2, jobs.size)
        assertEquals(JobStatus.DONE, JobStatus.fromWire(jobs[0].status))
        assertEquals(JobStatus.ERROR, JobStatus.fromWire(jobs[1].status))
        assertEquals("limit=10", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun `upload posts the epub plus voice and speed as plain form parts`() = runBlocking {
        val epub = Files.createTempFile("book", ".epub").toFile()
        epub.writeBytes("PK pretend epub".toByteArray())
        enqueueJson(Fixtures.read("job_queued.json"), code = 202)

        val part = MultipartBody.Part.createFormData(
            "file", "My Book.epub",
            ProgressRequestBody(epub, "application/epub+zip".toMediaType()) { _, _ -> },
        )
        val job = provider().service().createJob(
            file = part,
            voice = "af_heart".toRequestBody("text/plain".toMediaType()),
            speed = "1.0".toRequestBody("text/plain".toMediaType()),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/jobs", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val body = request.body!!.utf8()
        assertTrue("file part is named 'file'", body.contains("""name="file""""))
        assertTrue("original filename is sent", body.contains("""filename="My Book.epub""""))
        assertTrue("epub bytes are included", body.contains("pretend epub"))
        // Must not be JSON-quoted: the server reads these with FastAPI Form(...).
        assertTrue("voice is a bare form value", body.contains("\r\n\r\naf_heart\r\n"))
        assertTrue("speed is a bare form value", body.contains("\r\n\r\n1.0\r\n"))
        assertEquals(JobStatus.QUEUED, JobStatus.fromWire(job.status))
        epub.delete()
        Unit
    }

    @Test
    fun `delete returns the confirmation`() = runBlocking {
        enqueueJson("""{"job_id":"abc","deleted":true}""")
        val result = provider().service().deleteJob("abc")
        assertTrue(result.deleted)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `the worker log comes back as plain text, not json`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .body("Creating M4B file...\nffmpeg version 7.1\n")
                .build()
        )

        val log = provider().service().log("abc").string()

        assertTrue(log.contains("ffmpeg version 7.1"))
    }

    @Test
    fun `a service is reused while the address is unchanged and rebuilt when it moves`() {
        val provider = provider()
        assertTrue(provider.service() === provider.service())

        var url = "http://first.example:8000"
        val moving = ApiProvider(baseUrl = { url }, token = { null })
        val first = moving.service()
        url = "http://second.example:8000"
        assertTrue("changing the address must not keep the old base URL", first !== moving.service())
    }

    @Test
    fun `an unconfigured server is a typed failure, not a crash`() {
        val provider = ApiProvider(baseUrl = { null }, token = { null })
        try {
            provider.service()
            fail("expected ServerNotConfigured")
        } catch (_: ServerNotConfigured) {
        }
        try {
            provider.url("api/health")
            fail("expected ServerNotConfigured")
        } catch (_: ServerNotConfigured) {
        }
    }

    @Test
    fun `url() joins paths against the configured base without doubling slashes`() {
        val provider = ApiProvider(baseUrl = { "http://host:8000" }, token = { null })
        assertEquals(
            "http://host:8000/api/jobs/abc/audiobook",
            provider.url("api/jobs/abc/audiobook").toString(),
        )
        assertEquals(
            "http://host:8000/api/jobs/abc/sync",
            provider.url("/api/jobs/abc/sync").toString(),
        )
    }

}
