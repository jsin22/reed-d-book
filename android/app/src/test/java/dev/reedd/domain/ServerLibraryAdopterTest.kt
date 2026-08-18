package dev.reedd.domain

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.reedd.data.BookRepository
import dev.reedd.data.db.ReeddDatabase
import dev.reedd.data.db.inMemoryDb
import dev.reedd.data.download.ResumableDownloader
import dev.reedd.data.local.BookFiles
import dev.reedd.data.local.EpubImporter
import dev.reedd.data.readium.ReadiumComponents
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.JobDto
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [toAdopt] gets real coverage: it is a pure function and the interesting part of
 * this class, deciding which server jobs are worth pulling in. Everything past
 * that -- downloading an epub, opening it with Readium, building the row -- is
 * unverified here on purpose, the same boundary [EpubImporter] itself already has
 * no tests across. The two [adopt] tests deliberately never reach that code: a
 * server that cannot be reached, and a listing with nothing new in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ServerLibraryAdopterTest {

    private lateinit var db: ReeddDatabase
    private lateinit var server: MockWebServer
    private lateinit var adopter: ServerLibraryAdopter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        db = inMemoryDb()
        server = MockWebServer()
        server.start()
        val api = ApiProvider(baseUrl = { server.url("/").toString() }, token = { null })
        val files = BookFiles(context)
        adopter = ServerLibraryAdopter(
            context = context,
            repository = BookRepository(db.books(), db.sync(), api),
            api = api,
            files = files,
            importer = EpubImporter(context, files, ReadiumComponents(context)),
            downloader = ResumableDownloader(api.okHttp),
        )
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun `an unreachable server is not fatal`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())
        adopter.adopt(known = emptySet())
        // Just needs to not throw; there is always another reconcile to retry.
    }

    @Test
    fun `nothing is fetched when the listing has nothing new`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"jobs":[]}""")
                .build()
        )
        adopter.adopt(known = emptySet())
        // The listing itself is the only request; no epub download was attempted.
        assertEquals(1, server.requestCount)
    }

    private fun job(id: String, status: String) = JobDto(
        jobId = id,
        status = status,
        filename = "$id.epub",
        voice = "af_heart",
        speed = 1.0,
        createdAt = "2026-08-17T00:00:00+00:00",
    )

    @Test
    fun `a finished job not already known is adopted`() {
        val done = job("new-done", "done")
        assertEquals(listOf(done), ServerLibraryAdopter.toAdopt(jobs = listOf(done), known = emptySet()))
    }

    @Test
    fun `a job already backed by a local row is not re-adopted`() {
        val done = job("already-here", "done")
        val result = ServerLibraryAdopter.toAdopt(jobs = listOf(done), known = setOf("already-here"))
        assertEquals(emptyList<JobDto>(), result)
    }

    @Test
    fun `a job still converting is not adopted early`() {
        val running = job("in-progress", "running")
        val result = ServerLibraryAdopter.toAdopt(jobs = listOf(running), known = emptySet())
        assertEquals(emptyList<JobDto>(), result)
    }

    @Test
    fun `a failed job is not adopted`() {
        val error = job("broken", "error")
        val result = ServerLibraryAdopter.toAdopt(jobs = listOf(error), known = emptySet())
        assertEquals(emptyList<JobDto>(), result)
    }

    @Test
    fun `only the new ones survive out of a mixed listing`() {
        val jobs = listOf(
            job("known-done", "done"),
            job("new-done", "done"),
            job("still-running", "running"),
        )
        val result = ServerLibraryAdopter.toAdopt(jobs, known = setOf("known-done"))
        assertEquals(listOf(jobs[1]), result)
    }
}
