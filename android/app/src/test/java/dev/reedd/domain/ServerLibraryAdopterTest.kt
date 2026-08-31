package dev.reedd.domain

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.reedd.data.BookRepository
import dev.reedd.data.db.ReeddDatabase
import dev.reedd.data.db.inMemoryDb
import dev.reedd.data.local.BookFiles
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.JobDto
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adoption is a pure local insert now -- title/author come straight off the
 * job's own manifest, not from downloading and Readium-parsing the epub (see
 * the class's own docstring for why that changed) -- so, unlike before, this
 * gets full coverage of [adopt] itself, not just [toAdopt].
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
        db = inMemoryDb()
        server = MockWebServer()
        server.start()
        val api = ApiProvider(baseUrl = { server.url("/").toString() }, token = { null })
        adopter = ServerLibraryAdopter(
            repository = BookRepository(db.books(), db.sync(), api),
            api = api,
            files = BookFiles(context),
        )
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun job(
        id: String,
        status: String = "done",
        filename: String = "$id.epub",
        title: String? = null,
        author: String? = null,
        category: String? = null,
        genres: List<String> = emptyList(),
    ) = JobDto(
        jobId = id,
        status = status,
        filename = filename,
        title = title,
        author = author,
        voice = "af_heart",
        speed = 1.0,
        createdAt = "2026-08-17T00:00:00+00:00",
        category = category,
        genres = genres,
    )

    @Test
    fun `an empty job list adopts nothing and makes no requests`() = runTest {
        adopter.adopt(jobs = emptyList(), known = emptySet())
        // Purely local now -- nothing here should ever reach the server.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a new job is adopted with the manifest's own title and author, no network calls`() = runTest {
        adopter.adopt(jobs = listOf(job("j1", title = "Real Title", author = "Real Author")), known = emptySet())

        val book = db.books().all().single()
        assertEquals("j1", book.jobId)
        assertEquals("Real Title", book.title)
        assertEquals("Real Author", book.author)
        // The whole point: no epub download, no Readium parse, so no request
        // of any kind -- not even one to the server.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a job with no title falls back to the filename`() = runTest {
        adopter.adopt(jobs = listOf(job("j1", filename = "some-book.epub", title = null)), known = emptySet())

        assertEquals("some-book", db.books().all().single().title)
    }

    @Test
    fun `a job with no author leaves author null rather than a blank string`() = runTest {
        adopter.adopt(jobs = listOf(job("j1", title = "Title", author = null)), known = emptySet())

        assertNull(db.books().all().single().author)
    }

    @Test
    fun `category and genres come along with the rest of the job state`() = runTest {
        adopter.adopt(
            jobs = listOf(job("j1", title = "Title", category = "Fiction", genres = listOf("Horror"))),
            known = emptySet(),
        )

        val book = db.books().all().single()
        assertEquals("Fiction", book.category)
        assertEquals(listOf("Horror"), book.genres)
    }

    @Test
    fun `a job already known locally is not adopted again`() = runTest {
        adopter.adopt(jobs = listOf(job("already-here")), known = setOf("already-here"))

        assertTrue(db.books().all().isEmpty())
    }

    @Test
    fun `only the new ones survive out of a mixed listing`() = runTest {
        adopter.adopt(
            jobs = listOf(job("known-done"), job("new-done", title = "New"), job("still-running", status = "running")),
            known = setOf("known-done"),
        )

        assertEquals(listOf("New"), db.books().all().map { it.title })
    }

    @Test
    fun `a finished job not already known is selected by toAdopt`() {
        val done = job("new-done")
        assertEquals(listOf(done), ServerLibraryAdopter.toAdopt(jobs = listOf(done), known = emptySet()))
    }

    @Test
    fun `a job already backed by a local row is not re-selected`() {
        val done = job("already-here")
        val result = ServerLibraryAdopter.toAdopt(jobs = listOf(done), known = setOf("already-here"))
        assertEquals(emptyList<JobDto>(), result)
    }

    @Test
    fun `a job still converting is not selected early`() {
        val running = job("in-progress", status = "running")
        val result = ServerLibraryAdopter.toAdopt(jobs = listOf(running), known = emptySet())
        assertEquals(emptyList<JobDto>(), result)
    }

    @Test
    fun `a failed job is not selected`() {
        val error = job("broken", status = "error")
        val result = ServerLibraryAdopter.toAdopt(jobs = listOf(error), known = emptySet())
        assertEquals(emptyList<JobDto>(), result)
    }
}
