package dev.reedd.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.reedd.data.db.ReeddDatabase
import dev.reedd.data.db.book
import dev.reedd.data.db.inMemoryDb
import dev.reedd.data.remote.ApiProvider
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

/**
 * `retryConversion`/`cancelConversion` in `LibraryViewModel` and
 * `retryUpload`/`cancel` in `BookDetailViewModel` both used to hand-mirror
 * this exact logic -- this is the coverage neither hand-mirrored copy ever
 * had, now that both delegate here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConversionActionsTest {

    private lateinit var db: ReeddDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: BookRepository
    private lateinit var actions: ConversionActions
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
        actions = ConversionActions(context as Application, repository, api)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun workEnqueued(name: String): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
            .any { !it.state.isFinished }

    // -- retry -----------------------------------------------------------------

    @Test
    fun `retry clears the job and enqueues a fresh upload`() = runTest {
        db.books().insert(book("b1", jobId = "job-1"))

        actions.retry("b1")

        assertNull(repository.get("b1")!!.jobId)
        assertTrue(workEnqueued("upload-b1"))
    }

    // -- changeVoiceAndReconvert --------------------------------------------------

    @Test
    fun `changeVoiceAndReconvert persists the new voice, wipes the old audio, and re-arms autoDownload`() = runTest {
        db.books().insert(
            book(
                "b1", jobId = "job-1", downloadState = dev.reedd.data.db.DownloadState.DONE,
                audiobookPath = "/data/b1/book.m4b", syncPath = "/data/b1/book.json", voice = "alba",
            )
        )

        actions.changeVoiceAndReconvert("b1", "giovanni")

        val book = repository.get("b1")!!
        assertEquals("giovanni", book.voice)
        // The old job is gone -- retry re-sends the epub as a fresh one.
        assertNull(book.jobId)
        assertTrue(workEnqueued("upload-b1"))
        // This device's own stale copy of the old voice is cleared out...
        assertEquals(dev.reedd.data.db.DownloadState.NONE, book.downloadState)
        assertNull(book.audiobookPath)
        assertNull(book.syncPath)
        // ...and the new one will fetch itself the moment it's ready.
        assertTrue(book.autoDownload)
        // The old job is stashed, not deleted yet -- ConversionWatcher
        // deletes it only once the *new* job is confirmed done.
        assertEquals("job-1", book.previousJobId)
    }

    @Test
    fun `changeVoiceAndReconvert best-effort cleans up an earlier reconvert's own still-pending old job`() = runTest {
        // The reader changed voice twice before the first reconvert's own
        // new job ever finished -- previousJobId from the first change
        // would otherwise be silently overwritten and never cleaned up.
        db.books().insert(book("b1", jobId = "job-2", voice = "giovanni", previousJobId = "job-1"))
        server.enqueue(MockResponse.Builder().code(200)
            .setHeader("Content-Type", "application/json")
            .body("""{"job_id":"job-1","deleted":true}""").build())

        actions.changeVoiceAndReconvert("b1", "eponine")

        val book = repository.get("b1")!!
        assertEquals("eponine", book.voice)
        // job-2 (this book's own current job when the call started) is now
        // the one waiting for its own eventual cleanup.
        assertEquals("job-2", book.previousJobId)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/jobs/job-1", request.url.encodedPath)
    }

    // -- cancel ------------------------------------------------------------------

    @Test
    fun `cancel deletes the server job and clears it locally`() = runTest {
        db.books().insert(book("b1", jobId = "job-1"))
        server.enqueue(MockResponse.Builder().code(200)
            .setHeader("Content-Type", "application/json")
            .body("""{"job_id":"job-1","deleted":true}""").build())

        val message = actions.cancel("b1")

        assertNull(message)
        assertNull(repository.get("b1")!!.jobId)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancel treats a 404 as success, not a message to surface`() = runTest {
        db.books().insert(book("b1", jobId = "job-1"))
        server.enqueue(MockResponse.Builder().code(404)
            .setHeader("Content-Type", "application/json")
            .body("""{"detail":"not found"}""").build())

        val message = actions.cancel("b1")

        assertNull(message)
        assertNull(repository.get("b1")!!.jobId)
    }

    @Test
    fun `cancel surfaces a real server failure but still clears the job locally`() = runTest {
        db.books().insert(book("b1", jobId = "job-1"))
        server.enqueue(MockResponse.Builder().code(500)
            .setHeader("Content-Type", "application/json")
            .body("""{"detail":"disk full"}""").build())

        val message = actions.cancel("b1")

        assertEquals("disk full", message)
        assertNull("the local cancel must proceed even when the server call fails",
            repository.get("b1")!!.jobId)
    }

    @Test
    fun `cancel with no job on the server never calls the API`() = runTest {
        db.books().insert(book("b1", jobId = null))

        val message = actions.cancel("b1")

        assertNull(message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancel skips the API call once the job is already known missing`() = runTest {
        db.books().insert(book("b1", jobId = "job-1", jobMissing = true))

        val message = actions.cancel("b1")

        assertNull(message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancel always cancels both workers, even with no server job`() = runTest {
        db.books().insert(book("b1", jobId = null))
        WorkManager.getInstance(context).enqueueUniqueWork(
            "upload-b1", androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<dev.reedd.work.UploadWorker>()
                .setInputData(androidx.work.workDataOf("bookId" to "b1")).build(),
        )

        actions.cancel("b1")

        assertFalse(workEnqueued("upload-b1"))
    }
}
