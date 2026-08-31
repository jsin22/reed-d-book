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
import kotlinx.coroutines.launch
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

    /** A minimal `GET /api/jobs` list body with one DONE job, for reconcile()'s
     *  own batch fetch -- distinct from the single-job fixtures pollOnce's
     *  tests use, since reconcile now reads the list endpoint exclusively. */
    private fun jobsListJson(
        jobId: String = "job-1",
        status: String = "done",
        category: String? = null,
        genres: List<String> = emptyList(),
    ): String {
        val cat = category?.let { "\"$it\"" } ?: "null"
        val gen = genres.joinToString(",") { "\"$it\"" }
        return """{"jobs":[{"job_id":"$jobId","status":"$status","filename":"book.epub",
            "voice":"af_heart","speed":1.0,"created_at":"2026-01-01T00:00:00+00:00",
            "category":$cat,"genres":[$gen]}]}"""
    }

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
                category = "Fiction",
            )
        )
        enqueue(jobsListJson(category = "Fiction"))

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
                category = "Fiction",
            )
        )
        enqueue(jobsListJson(category = "Fiction"))

        watcher.reconcile()

        val book = db.books().get("b1")!!
        assertEquals(DownloadState.DONE, book.downloadState)
        assertTrue(book.isPlayable)
        assertNull(book.downloadError)
        dir.deleteRecursively()
    }

    @Test
    fun `reconcile does not throw when the job list cannot be fetched`() = runTest {
        // An unreachable server used to be ServerLibraryAdopter.adopt's own
        // concern; it no longer fetches anything itself, so this is
        // reconcile's job now -- degrade to "nothing updated this time,"
        // same as before, there is always a later reconcile to retry.
        db.books().insert(book("b1", jobId = "job-1", jobStatus = JobStatus.DONE, category = "Fiction"))
        enqueue("""{"detail":"boom"}""", code = 500)

        watcher.reconcile() // must not throw

        assertEquals("Fiction", db.books().get("b1")!!.category) // left untouched
    }

    @Test
    fun `reconcile picks up category and genre for a job that finished before this device asked`() = runTest {
        // Simulates a job that was DONE before this feature existed (or before
        // the server's category/genre backfill ran) -- pollAll's own loop
        // would never ask about it again, since awaitingConversion excludes
        // terminal jobs. reconcile's batch fetch is what re-checks it.
        db.books().insert(book("b1", jobId = "job-1", jobStatus = JobStatus.DONE))
        enqueue(jobsListJson(category = "Fiction", genres = listOf("Horror")))

        watcher.reconcile()

        val book = db.books().get("b1")!!
        assertEquals("Fiction", book.category)
        assertEquals(listOf("Horror"), book.genres)
    }

    @Test
    fun `reconcile refreshes category and genre even for a book already resolved`() = runTest {
        // The property that made the old "only re-check books never resolved"
        // carve-out (and the admin screen's separate clearAllMetadata step)
        // unnecessary: every reconcile now refreshes every book from whatever
        // the server currently has, not just ones that were never checked --
        // so a book resolved with an older/incomplete answer catches up too.
        db.books().insert(
            book("b1", jobId = "job-1", jobStatus = JobStatus.DONE, category = "Fiction", genres = listOf("Mystery"))
        )
        enqueue(jobsListJson(category = "Fiction", genres = listOf("Mystery", "Short Stories")))

        watcher.reconcile()

        assertEquals(listOf("Mystery", "Short Stories"), db.books().get("b1")!!.genres)
    }

    @Test
    fun `two concurrent reconciles never adopt at the same time`() = runTest {
        // Regression, reproduced on a real device: two overlapping
        // reconcile() calls (the Library screen's own launch-time one racing
        // the Admin screen's "Re-check all book metadata") each read "which
        // jobs are already local" before either had inserted anything, so
        // both decided the same finished job was new and both adopted it --
        // the same book shown twice with the same job id. The mutex in
        // ConversionWatcher.reconcile is what should now serialize this.
        enqueue("""{"jobs":[]}""")
        enqueue("""{"jobs":[]}""")
        var inFlight = 0
        var sawOverlap = false
        val watcherWithAdoption = ConversionWatcher(
            context, repository, ApiProvider(baseUrl = { server.url("/").toString() }, token = { null }),
            Notifications(context),
            adoptServerLibrary = { _, _ ->
                inFlight++
                if (inFlight > 1) sawOverlap = true
                // Yields control back to the scheduler -- exactly the window
                // a real race needs, and exactly what the mutex must close.
                kotlinx.coroutines.delay(10)
                inFlight--
            },
        )

        val first = launch { watcherWithAdoption.reconcile() }
        val second = launch { watcherWithAdoption.reconcile() }
        first.join()
        second.join()

        assertFalse("two reconciles ran their adopt step at the same time", sawOverlap)
    }

    @Test
    fun `reconcile tells the adopter which jobs are already local, and hands it the same list it fetched`() = runTest {
        // "known" has to be every jobId already backed by a row, or the adopter
        // (ServerLibraryAdopterTest, kept separate since it depends on Readium)
        // would re-adopt a book this device already has.
        db.books().insert(book("has-job", jobId = "job-1", jobStatus = JobStatus.DONE, category = "Fiction"))
        db.books().insert(book("no-job", jobId = null))
        enqueue(jobsListJson(category = "Fiction"))
        var seenKnown: Set<String>? = null
        var seenJobs: List<dev.reedd.data.remote.JobDto>? = null
        val watcherWithAdoption = ConversionWatcher(
            context, repository, ApiProvider(baseUrl = { server.url("/").toString() }, token = { null }),
            Notifications(context),
            adoptServerLibrary = { jobs, known -> seenJobs = jobs; seenKnown = known },
        )

        watcherWithAdoption.reconcile()

        assertEquals(setOf("job-1"), seenKnown)
        assertEquals(listOf("job-1"), seenJobs?.map { it.jobId })
        // Exactly one request: the same list serves both adoption and the
        // per-book state refresh, not two separate fetches.
        assertEquals(1, server.requestCount)
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
