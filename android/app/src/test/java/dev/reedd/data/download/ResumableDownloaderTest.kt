package dev.reedd.data.download

import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ErrorInterceptor
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

/**
 * The resume path, which is the whole reason this class exists: an `.m4b` can be
 * hundreds of megabytes, and a phone that walks out of wifi range must not start
 * the transfer again from zero.
 */
class ResumableDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val content = Random(42).nextBytes(50_000)

    /** Range requests seen, in order, as raw header values (null = no Range). */
    private val ranges = mutableListOf<String?>()

    private val client = OkHttpClient.Builder()
        .addInterceptor(ErrorInterceptor())
        .build()

    private val downloader by lazy { ResumableDownloader(client) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("dl").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    /** Serves [content] with real `Range` support, like FastAPI's FileResponse. */
    private fun serveWithRanges(honourRange: Boolean = true) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.headers["Range"]
                ranges += range
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0

                if (!honourRange || range == null) {
                    return MockResponse.Builder()
                        .code(200)
                        .body(Buffer().write(content))
                        .build()
                }
                if (start >= content.size) {
                    return MockResponse.Builder().code(416).body("""{"detail":"range not satisfiable"}""").build()
                }
                val slice = content.copyOfRange(start, content.size)
                return MockResponse.Builder()
                    .code(206)
                    .setHeader("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                    .body(Buffer().write(slice))
                    .build()
            }
        }
    }

    private fun target() = File(dir, "book.m4b")
    private fun partOf(target: File) = File(dir, "${target.name}.part")

    @Test
    fun `a fresh download writes the file and cleans up the part file`() = runBlocking {
        serveWithRanges()
        val target = target()

        val result = downloader.download(server.url("/audiobook"), target, expectedBytes = content.size.toLong())

        assertEquals(target, result)
        assertTrue(target.readBytes().contentEquals(content))
        assertFalse("the .part file must not be left behind", partOf(target).exists())
        assertEquals(listOf<String?>(null), ranges)
    }

    @Test
    fun `an existing part file resumes with a Range header and appends`() = runBlocking {
        serveWithRanges()
        val target = target()
        // Pretend a previous attempt got 20000 bytes in before wifi dropped.
        partOf(target).writeBytes(content.copyOfRange(0, 20_000))

        downloader.download(server.url("/audiobook"), target, expectedBytes = content.size.toLong())

        assertEquals(listOf<String?>("bytes=20000-"), ranges)
        assertTrue("the resumed file must equal the original", target.readBytes().contentEquals(content))
    }

    @Test
    fun `a server that ignores Range restarts the file rather than corrupting it`() = runBlocking {
        // Answering a Range request with 200 and the whole body is legal. Appending
        // that to what we already had would silently produce a broken audiobook.
        serveWithRanges(honourRange = false)
        val target = target()
        partOf(target).writeBytes(content.copyOfRange(0, 20_000))

        downloader.download(server.url("/audiobook"), target, expectedBytes = content.size.toLong())

        assertEquals(content.size.toLong(), target.length())
        assertTrue(target.readBytes().contentEquals(content))
    }

    @Test
    fun `a stale part file longer than the resource is discarded after a 416`() = runBlocking {
        serveWithRanges()
        val target = target()
        // The book was re-converted server-side and is now shorter.
        partOf(target).writeBytes(Random(1).nextBytes(content.size + 5_000))

        downloader.download(server.url("/audiobook"), target, expectedBytes = content.size.toLong())

        assertEquals(listOf<String?>("bytes=55000-", null), ranges)
        assertTrue(target.readBytes().contentEquals(content))
    }

    @Test
    fun `an interrupted transfer keeps its part file, and the next attempt finishes it`() = runBlocking {
        val target = target()
        // First attempt: declare the full length but hang up halfway through.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Length", content.size.toString())
                .body(Buffer().write(content.copyOfRange(0, 20_000)))
                .onResponseBody(SocketEffect.CloseSocket())
                .build()
        )
        try {
            downloader.download(server.url("/audiobook"), target, expectedBytes = content.size.toLong())
            fail("expected the truncated transfer to fail")
        } catch (_: IOException) {
        }

        assertFalse("a truncated file must never be renamed into place", target.exists())
        val salvaged = partOf(target).length()
        assertTrue("partial bytes must be kept for the resume, got $salvaged", salvaged > 0)

        // Second attempt against a working server: it resumes from the .part file.
        serveWithRanges()
        downloader.download(server.url("/audiobook"), target, expectedBytes = content.size.toLong())

        assertTrue(target.readBytes().contentEquals(content))
        assertEquals("bytes=$salvaged-", ranges.last())
    }

    @Test
    fun `a size that disagrees with the manifest fails and keeps the part file`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(content)).build())
        val target = target()

        try {
            // The manifest said the audiobook was bigger than what arrived.
            downloader.download(server.url("/audiobook"), target, expectedBytes = content.size + 999L)
            fail("expected IncompleteDownload")
        } catch (e: IncompleteDownload) {
            assertTrue(e.message!!.contains("expected ${content.size + 999}"))
        }

        assertFalse(target.exists())
        assertTrue("the .part file is the basis of the next attempt", partOf(target).exists())
        Unit
    }

    @Test
    fun `progress runs from zero to the total and never goes backwards`() = runBlocking {
        serveWithRanges()
        val seen = mutableListOf<Pair<Long, Long>>()

        downloader.download(server.url("/audiobook"), target(), expectedBytes = content.size.toLong()) { done, total ->
            seen += done to total
        }

        assertEquals(0L, seen.first().first)
        assertEquals(content.size.toLong(), seen.last().first)
        assertTrue("total must be reported", seen.all { it.second == content.size.toLong() })
        assertTrue(seen.map { it.first }.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `progress on a resumed transfer counts the bytes already on disk`() = runBlocking {
        serveWithRanges()
        val target = target()
        partOf(target).writeBytes(content.copyOfRange(0, 20_000))
        val seen = mutableListOf<Pair<Long, Long>>()

        downloader.download(server.url("/audiobook"), target, expectedBytes = content.size.toLong()) { done, total ->
            seen += done to total
        }

        // Starting the bar at 0% for a transfer that is already 40% done would be
        // a lie; the Content-Range total is what makes this work.
        assertEquals(20_000L, seen.first().first)
        assertEquals(content.size.toLong(), seen.first().second)
        assertEquals(content.size.toLong(), seen.last().first)
    }

    @Test
    fun `an existing file is replaced`() = runBlocking {
        serveWithRanges()
        val target = target()
        target.writeBytes("stale".toByteArray())

        downloader.download(server.url("/audiobook"), target, expectedBytes = content.size.toLong())

        assertTrue(target.readBytes().contentEquals(content))
    }

    @Test
    fun `a 409 from the server surfaces as a classified error, not a corrupt file`() = runBlocking {
        // The job is not finished; the download endpoint returns 409.
        server.enqueue(MockResponse.Builder().code(409).body("""{"detail":"job is running"}""").build())
        val target = target()

        try {
            downloader.download(server.url("/audiobook"), target)
            fail("expected an ApiException")
        } catch (e: ApiException) {
            assertTrue(e.isNotReady)
            assertEquals("job is running", e.detail)
        }
        assertFalse(target.exists())
        Unit
    }

    @Test
    fun `a small file with no expected size still lands`() = runBlocking {
        // The sync .json is fetched without a size from the manifest.
        val json = """{"version":1,"chunks":[]}"""
        server.enqueue(MockResponse.Builder().code(200).body(json).build())
        val target = File(dir, "book.json")

        downloader.download(server.url("/sync"), target)

        assertEquals(json, target.readText())
        assertNull(server.takeRequest().headers["Range"])
    }
}
