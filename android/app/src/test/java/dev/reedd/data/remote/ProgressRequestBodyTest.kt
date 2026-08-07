package dev.reedd.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

class ProgressRequestBodyTest {

    private fun tempFile(size: Int): java.io.File =
        Files.createTempFile("upload", ".epub").toFile().apply {
            writeBytes(Random(7).nextBytes(size))
            deleteOnExit()
        }

    @Test
    fun `content length is the file size and the type is passed through`() {
        val file = tempFile(1234)
        val body = ProgressRequestBody(file, "application/epub+zip".toMediaType()) { _, _ -> }
        assertEquals(1234L, body.contentLength())
        assertEquals("application/epub+zip", body.contentType().toString())
    }

    @Test
    fun `every byte is written exactly once`() {
        // Deliberately spans several 64 KB segments.
        val file = tempFile(200_000)
        val sink = Buffer()

        ProgressRequestBody(file, null) { _, _ -> }.writeTo(sink)

        assertEquals(200_000L, sink.size)
        assertTrue(sink.readByteArray().contentEquals(file.readBytes()))
    }

    @Test
    fun `progress is monotonic and ends exactly at the total`() {
        val file = tempFile(200_000)
        val seen = mutableListOf<Long>()
        var reportedTotal = -1L

        ProgressRequestBody(file, null) { sent, total ->
            seen += sent
            reportedTotal = total
        }.writeTo(Buffer())

        assertEquals(200_000L, reportedTotal)
        assertEquals(200_000L, seen.last())
        assertTrue("progress must never go backwards", seen.zipWithNext().all { (a, b) -> b > a })
        // Segment-sized callbacks: the caller is told to throttle, so confirm
        // there really are many of them rather than one per file.
        assertTrue("expected several callbacks, got ${seen.size}", seen.size > 2)
    }

    @Test
    fun `an empty file writes nothing and reports no progress`() {
        val file = tempFile(0)
        val seen = mutableListOf<Long>()
        val sink = Buffer()

        ProgressRequestBody(file, null) { sent, _ -> seen += sent }.writeTo(sink)

        assertEquals(0L, sink.size)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `the body is re-readable, so OkHttp may retry the request`() {
        val file = tempFile(100_000)
        val body = ProgressRequestBody(file, null) { _, _ -> }

        val first = Buffer().also { body.writeTo(it) }.readByteArray()
        val second = Buffer().also { body.writeTo(it) }.readByteArray()

        assertTrue(first.contentEquals(second))
    }
}
