package dev.reedd.domain

import dev.reedd.data.BookRepository
import dev.reedd.data.download.ResumableDownloader
import dev.reedd.data.local.BookFiles
import dev.reedd.data.local.EpubImporter
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.JobDto
import dev.reedd.data.remote.JobStatus
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
 * which already-finished jobs the device has not seen the row for yet, and
 * fetches enough (the epub) to show a card for it. The audiobook/sync files are
 * left for the user's own Download tap, same as any other finished job.
 */
class ServerLibraryAdopter(
    private val repository: BookRepository,
    private val api: ApiProvider,
    private val files: BookFiles,
    private val importer: EpubImporter,
    private val downloader: ResumableDownloader,
) {
    /**
     * @param known job ids already backed by a local row; left alone, not
     *   re-adopted. A failed download or a listing the server could not answer
     *   is not fatal -- there is always a later reconcile to try again.
     */
    suspend fun adopt(known: Set<String>) {
        val jobs = runCatching { api.service().listJobs(limit = LIST_LIMIT).jobs }.getOrNull() ?: return
        for (job in toAdopt(jobs, known)) {
            // One book's corrupt epub or a connection dropped mid-download must
            // not stop the rest of the server's library from coming in.
            runCatching { adoptOne(job) }
        }
    }

    private suspend fun adoptOne(job: JobDto) {
        val bookId = UUID.randomUUID().toString()
        // Only the epub -- small, and needed for the card itself (title, author,
        // cover) plus the reader's text. The audiobook/sync files are the
        // hundreds-of-megabytes part, and are not fetched until the user taps
        // Download on the card, same as a conversion this device started itself.
        downloader.download(url = api.url("api/jobs/${job.jobId}/epub"), target = files.epub(bookId))
        val book = importer.fromServerCopy(bookId, job.filename)
        repository.insert(book)
        repository.attachJob(bookId, job)
        repository.applyJobState(bookId, job)
    }

    companion object {
        private const val LIST_LIMIT = 500 // the server's own cap; see server/app/main.py

        /**
         * Finished jobs the device does not already have a row for. Pulled out
         * as a pure function so the selection logic is testable without a
         * server, a database, or Readium.
         */
        internal fun toAdopt(jobs: List<JobDto>, known: Set<String>): List<JobDto> =
            jobs.filter { JobStatus.fromWire(it.status) == JobStatus.DONE && it.jobId !in known }
    }
}
