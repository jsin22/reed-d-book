package dev.reedd.domain

import android.util.Log
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.local.BookFiles
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.JobDto
import dev.reedd.data.remote.JobStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

/**
 * Pulls in books the server has already converted that this device does not have
 * a row for yet -- from another device, a reinstall, or wiped app storage.
 *
 * `deleteJobAfterDownload` (`data/settings/SettingsStore.kt`) defaults to off now
 * precisely so this can work: a finished job is a complete, standalone record --
 * the epub that went in, the audiobook and sync file that came out -- and the
 * server keeps it until something explicitly deletes it. `GET /api/jobs` is
 * therefore also the library listing. A book converted once is available to
 * every device that ever points at that server, without re-uploading or waiting
 * through TTS a second time. Nothing about how a conversion happens changes to
 * make this work: audiblez, the queue and the job.json lifecycle (`server/`) are
 * exactly as they were, and this class never touches them -- it only decides
 * which already-finished jobs the device has not seen the row for yet.
 *
 * Adoption itself is now a pure local insert -- title/author come straight off
 * the job's own manifest ([JobDto.title]/[JobDto.author], sent by whichever
 * device originally uploaded it), not from downloading and Readium-parsing the
 * epub. That used to happen here, which meant every book on the server had to
 * be fully fetched and parsed just to show a card for it -- confirmed live to
 * be genuinely slow (a real download plus a real parse, one book at a time,
 * for a whole library on every fresh reconcile) for something that only ever
 * needed a title and an author. The epub itself -- and with it, the *precise*
 * Readium-derived title/author/cover, since the manifest's are only ever
 * best-effort -- is fetched by [dev.reedd.work.DownloadWorker] the moment the
 * user actually taps Download, same as the audiobook/sync files always were.
 */
class ServerLibraryAdopter(
    private val repository: BookRepository,
    private val api: ApiProvider,
    private val files: BookFiles,
) {
    /**
     * @param jobs every job visible to this user, already fetched by the
     *   caller ([ConversionWatcher.reconcile]) -- adoption and the rest of a
     *   reconcile need the exact same list, so it is fetched once, not twice.
     * @param known job ids already backed by a local row; left alone, not
     *   re-adopted.
     */
    suspend fun adopt(jobs: List<JobDto>, known: Set<String>) {
        for (job in toAdopt(jobs, known)) {
            // One bad row must not stop the rest of the server's library from
            // coming in. Purely local now (no network, no Readium), so a
            // failure here would mean something is genuinely wrong with the
            // device's own storage -- worth knowing about, not worth losing
            // the rest of the batch over.
            runCatching { adoptOne(job) }
                .onFailure { e -> reportFailure(job, e) }
        }
    }

    private suspend fun adoptOne(job: JobDto) {
        val bookId = UUID.randomUUID().toString()
        repository.insert(
            BookEntity(
                id = bookId,
                // Deterministic, not yet backed by a real file -- DownloadWorker
                // writes the epub here the first time this book is actually
                // downloaded. Every existing reader of BookEntity.epubPath
                // already has to tolerate a book that has not finished
                // downloading yet (see DownloadState), so nothing new needs to
                // check for this specially; it is just one more reason the
                // file might not be there yet.
                epubPath = files.epub(bookId).absolutePath,
                originalFilename = job.filename,
                title = job.title?.takeIf { it.isNotBlank() } ?: job.filename.removeSuffix(".epub"),
                author = job.author?.takeIf { it.isNotBlank() },
            )
        )
        repository.attachJob(bookId, job)
        repository.applyJobState(bookId, job)
    }

    private suspend fun reportFailure(job: JobDto, e: Throwable) {
        Log.w(TAG, "could not adopt ${job.jobId} (${job.filename})", e)
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
        val report = buildString {
            appendLine("read-d-book: ServerLibraryAdopter could not adopt a job")
            appendLine("job_id:    ${job.jobId}")
            appendLine("filename:  ${job.filename}")
            appendLine("exception: ${e.javaClass.name}: ${e.message}")
            appendLine()
            append(trace.toString())
        }
        // Same server endpoint CrashLog posts uncaught crashes to; this is a
        // caught failure, not a crash, but it needs the same visibility.
        runCatching { api.service().reportCrash(report.toRequestBody(TEXT_PLAIN)) }
            .onFailure { Log.i(TAG, "could not send adopt-failure report: ${it.message}") }
    }

    companion object {
        private const val TAG = "ReeddAdopt"
        private val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()

        /**
         * Finished jobs the device does not already have a row for. Pulled out
         * as a pure function so the selection logic is testable without a
         * server or a database.
         */
        internal fun toAdopt(jobs: List<JobDto>, known: Set<String>): List<JobDto> =
            jobs.filter { JobStatus.fromWire(it.status) == JobStatus.DONE && it.jobId !in known }
    }
}
