package dev.reedd.data.remote

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.buffer
import okio.source
import java.io.File

/**
 * Streams a file as a request body, reporting bytes written.
 *
 * Streaming rather than `File.asRequestBody()` plus a wrapper is the point: a
 * 200 MB epub must never be materialised in memory on a phone. Because the
 * source is a real file and not a one-shot stream, OkHttp is free to retry the
 * request; [onProgress] can therefore be called more than once from zero.
 *
 * [onProgress] fires per 64 KB segment, which is thousands of calls for a large
 * book -- callers are expected to throttle before touching the database or UI.
 */
class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (bytesSent: Long, totalBytes: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var sent = 0L
        file.source().buffer().use { source ->
            val chunk = Buffer()
            while (true) {
                val read = source.read(chunk, SEGMENT_SIZE)
                if (read == -1L) break
                sink.write(chunk, read)
                sent += read
                onProgress(sent, total)
            }
        }
    }

    private companion object {
        const val SEGMENT_SIZE = 64L * 1024
    }
}
