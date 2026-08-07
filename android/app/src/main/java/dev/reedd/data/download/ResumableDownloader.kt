package dev.reedd.data.download

import dev.reedd.data.remote.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** The transfer completed but the file is not the size the server said it was. */
class IncompleteDownload(message: String) : IOException(message)

/**
 * Downloads a file, resuming where a previous attempt stopped.
 *
 * An `.m4b` can be several hundred megabytes over wifi from a handheld server, so
 * restarting from zero because a phone changed rooms is not acceptable. Bytes are
 * appended to a `.part` file which is renamed only once the transfer is verified,
 * so a truncated file can never be mistaken for a finished one.
 *
 * The server side already supports this: both download endpoints use FastAPI's
 * `FileResponse`, which honours `Range` (see `server/app/main.py`).
 */
class ResumableDownloader(private val client: OkHttpClient) {

    /**
     * @param expectedBytes size from the job manifest, when known. A transfer that
     *   ends at a different size fails rather than being renamed into place.
     * @param onProgress called with (bytesOnDisk, totalBytes); totalBytes is 0 when
     *   the server did not say. Called often -- throttle before writing anywhere.
     */
    suspend fun download(
        url: HttpUrl,
        target: File,
        expectedBytes: Long? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")

        var alreadyHave = if (partial.isFile) partial.length() else 0L
        var response = open(url, alreadyHave)

        // A server that ignores Range answers 200 with the whole file. Honouring
        // that by appending would corrupt the result, so start the file over.
        if (alreadyHave > 0 && response.code != HTTP_PARTIAL) {
            partial.delete()
            alreadyHave = 0
        }

        val total = totalBytesOf(response, alreadyHave)

        try {
            response.body.byteStream().use { source ->
                RandomAccessFile(partial, "rw").use { sink ->
                    sink.seek(alreadyHave)
                    val buffer = ByteArray(BUFFER)
                    var written = alreadyHave
                    onProgress(written, total)
                    while (true) {
                        // Lets WorkManager's cancellation actually stop the
                        // transfer instead of draining the whole body first.
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
        } finally {
            response.close()
        }

        val finalSize = partial.length()
        val expected = expectedBytes ?: total.takeIf { it > 0 }
        if (expected != null && finalSize != expected) {
            // Keep the .part file: the next attempt resumes from here rather than
            // starting a fresh multi-hundred-megabyte transfer.
            throw IncompleteDownload("${target.name}: got $finalSize bytes, expected $expected")
        }

        if (target.exists() && !target.delete()) {
            throw IOException("cannot replace ${target.name}")
        }
        if (!partial.renameTo(target)) {
            throw IOException("cannot move ${partial.name} into place")
        }
        target
    }

    /**
     * A blocking `execute()` rather than the coroutine extension: this already
     * runs on [Dispatchers.IO], and the body-read loop is where cancellation
     * needs to take effect anyway.
     */
    private fun open(url: HttpUrl, from: Long): Response {
        val request = Request.Builder()
            .url(url)
            .apply { if (from > 0) header("Range", "bytes=$from-") }
            .build()
        return try {
            client.newCall(request).execute()
        } catch (e: ApiException) {
            // The .part file is at least as long as the resource, so it is stale
            // (the book was re-converted): throw it away and start over.
            if (e.code == HTTP_RANGE_NOT_SATISFIABLE && from > 0) {
                client.newCall(Request.Builder().url(url).build()).execute()
            } else {
                throw e
            }
        }
    }

    /**
     * Total size of the whole resource.
     *
     * On a 206 the body is only the remainder, so `Content-Length` is not the
     * file size; the total comes from `Content-Range: bytes 100-999/1000`.
     */
    private fun totalBytesOf(response: Response, alreadyHave: Long): Long {
        if (response.code == HTTP_PARTIAL) {
            val range = response.header("Content-Range")
            val declared = range?.substringAfter('/', "")?.toLongOrNull()
            if (declared != null) return declared
        }
        val length = response.body.contentLength()
        return if (length >= 0) length + alreadyHave else 0
    }

    private companion object {
        const val HTTP_PARTIAL = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val BUFFER = 64 * 1024
    }
}
