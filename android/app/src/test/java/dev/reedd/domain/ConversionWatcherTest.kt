package dev.reedd.domain

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.reedd.Fixtures
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.DownloadState
import dev.reedd.data.db.ReeddDatabase
import dev.reedd.data.db.book
import dev.reedd.data.db.inMemoryDb
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.JobStatus
import dev.reedd.notify.Notifications
import kotlinx.coroutines.test.runTest
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
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The state machine that makes "close the app mid-conversion and come back" work.
 *
 * A plain [Application] rather than the real one: this exercises the watcher, not
 * the dependency graph, and building the real container would drag DataStore and
 * Readium into a unit test for no benefit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConversionWatcherTest {

    private lateinit var db: ReeddDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: BookRepository
    private lateinit var watcher: ConversionWatcher
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        db = inMemoryDb()
        server = MockWebServer()
        server.start()
        val api = ApiProvider(baseUrl = { server.url("/").toString() }, token = { null })
        repository = BookRepository(db.books(), db.sync(), api)
        watcher = ConversionWatcher(context, repository, api, Notifications(context))
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()
        )
    }

    private fun downloadEnqueued(bookId: String): Boolean =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("download-$bookId")
            .get()
            .isNotEmpty()

    @Test
    fun `a poll writes the server's progress into the row`() = runTest {
        db.books().insert(book("b1", jobId = "job-1", jobStatus = JobStatus.QUEUED))
        enqueue(Fixtures.read("job_running.json"))

        val status = watcher.pollOnce("b1")

        assertEquals(JobStatus.RUNNING, status)
        val book = db.books().get("b1")!!
        assertEquals(32, book.jobProgress)
        assertEquals("00d 00h 00m 11s", book.jobEta)
        assertTrue(book.needsPolling)
    }

    @Test
    fun `a 404 marks the job missing and stops the polling`() = runTest {
        // The whole point of this branch: the server's data dir was wiped while
        // the app was closed. Without it the book sits at "queued" forever.
        db.books().insert(book("b1", jobId = "job-1", jobStatus = JobStatus.QUEUED))
        enqueue("""{"detail":"no such job: job-1"}""", code = 404)

        watcher.pollOnce("b1")

        val book = db.books().get("b1")!!
        assertTrue(book.jobMissing)
        assertFalse(book.needsPolling)
        assertTrue(book.needsReupload)
        assertTrue(repository.awaitingConversion().isEmpty())
    }

    @Test
    fun `a job already known to be missing is not polled again`() = runTest {
        db.books().insert(book("b1", jobId = "job-1", jobStatus = JobStatus.QUEUED, jobMissing = true))

        watcher.pollOnce("b1")

        // No request was made at all.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a finished conversion enqueues the download`() = runTest {
        db.books().insert(book("b1", jobId = "job-1", jobStatus = JobStatus.RUNNING))
        enqueue(Fixtures.read("job_done.json"))

        val status = watcher.pollOnce("b1")

        assertEquals(JobStatus.DONE, status)
        assertTrue("finishing must start the download", downloadEnqueued("b1"))
        // The filenames from the manifest are kept for the download to use.
        assertEquals("A_Brief_Guide_to_Digital_Formats.m4b", db.books().get("b1")!!.audiobookRemoteName)
    }

    @Test
    fun `a failed conversion is recorded with the server's reason`() = runTest {
        db.books().insert(book("b1", jobId = "job-1", jobStatus = JobStatus.RUNNING))
        enqueue(Fixtures.read("job_error.json"))

        assertEquals(JobStatus.ERROR, watcher.pollOnce("b1"))

        val book = db.books().get("b1")!!
        assertEquals("ffmpeg not found on PATH; audiblez produced no .m4b", book.jobError)
        assertFalse(book.needsPolling)
        assertFalse("a failure is not a lost job", book.needsReupload)
    }

    @Test
    fun `one book failing to poll does not abandon the others`() = runTest {
        db.books().insert(book("bad", addedAt = 1, jobId = "job-bad", jobStatus = JobStatus.QUEUED))
        db.books().insert(book("good", addedAt = 2, jobId = "job-good", jobStatus = JobStatus.QUEUED))
        enqueue("""{"detail":"boom"}""", code = 500)      // "bad" is polled first
        enqueue(Fixtures.read("job_running.json"))          // "good" still gets through

        val remaining = watcher.pollAll()

        assertEquals(32, db.books().get("good")!!.jobProgress)
        // Both are still pending: the 500 left "bad" exactly as it was.
        assertEquals(2, remaining)
        assertEquals(JobStatus.QUEUED, db.books().get("bad")!!.jobStatus)
    }

    @Test
    fun `pollAll reports nothing pending once every job is terminal`() = runTest {
        db.books().insert(book("b1", jobId = "job-1", jobStatus = JobStatus.RUNNING))
        enqueue(Fixtures.read("job_done.json"))

        // Zero is what lets PollWorker cancel itself instead of waking the device
        // every 15 minutes forever.
        assertEquals(0, watcher.pollAll())
    }

    @Test
    fun `pollAll picks up a conversion that finished while the app was closed`() = runTest {
        // Nothing to poll: the row already says DONE, but no download ever ran
        // because the app was not running when the poll came back.
        db.books().insert(book("b1", jobId = "job-1", jobStatus = JobStatus.DONE))

        val remaining = watcher.pollAll()

        assertEquals(0, remaining)
        assertTrue(downloadEnqueued("b1"))
    }

    @Test
    fun `pollAll retries a download that failed`() = runTest {
        db.books().insert(
            book("b1", jobId = "job-1", jobStatus = JobStatus.DONE, downloadState = DownloadState.FAILED)
        )

        watcher.pollAll()

        assertTrue(downloadEnqueued("b1"))
    }

    @Test
    fun `reconcile repairs a row whose files vanished from disk`() = runTest {
        // The user cleared the app's storage, or a file was removed underneath us.
        // Trusting the database here would open the reader onto a missing file.
        db.books().insert(
            book(
                "b1", jobId = "job-1", jobStatus = JobStatus.DONE,
                downloadState = DownloadState.DONE,
                audiobookPath = "/definitely/not/here.m4b",
                syncPath = "/definitely/not/here.json",
            )
        )

        watcher.reconcile()

        val book = db.books().get("b1")!!
        assertEquals(DownloadState.NONE, book.downloadState)
        assertFalse(book.isPlayable)
        assertTrue(book.downloadError!!.contains("no longer on the device"))
        // And it is queued for downloading again.
        assertTrue(downloadEnqueued("b1"))
    }

    @Test
    fun `reconcile leaves a book alone when both files are really there`() = runTest {
        val dir = Files.createTempDirectory("files").toFile()
        val m4b = File(dir, "book.m4b").apply { writeBytes(ByteArray(10)) }
        val json = File(dir, "book.json").apply { writeText("{}") }
        db.books().insert(
            book(
                "b1", jobId = "job-1", jobStatus = JobStatus.DONE,
                downloadState = DownloadState.DONE,
                audiobookPath = m4b.absolutePath, syncPath = json.absolutePath,
            )
        )

        watcher.reconcile()

        val book = db.books().get("b1")!!
        assertEquals(DownloadState.DONE, book.downloadState)
        assertTrue(book.isPlayable)
        assertNull(book.downloadError)
        dir.deleteRecursively()
    }

    @Test
    fun `a book with no job is never polled`() = runTest {
        db.books().insert(book("b1"))

        assertNull(watcher.pollOnce("b1"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a book that no longer exists is not an error`() = runTest {
        assertNull(watcher.pollOnce("gone"))
    }

    @Test
    fun `transient failures are distinguished from permanent ones`() = runTest {
        // Drives whether PollWorker retries or gives up.
        assertTrue(watcher.isTransient(java.io.IOException("socket closed")))
        assertTrue(watcher.isTransient(dev.reedd.data.remote.ApiException(503, "unavailable")))
        assertFalse(watcher.isTransient(dev.reedd.data.remote.ApiException(400, "bad request")))
        assertFalse(watcher.isTransient(dev.reedd.data.remote.ServerNotConfigured()))
    }
}
