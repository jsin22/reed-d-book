package dev.reedd.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.reedd.data.remote.JobStatus

/**
 * One imported book, and everything known about its conversion.
 *
 * The primary key is a locally-minted id, **not** the server's `job_id`: a book
 * exists before it is ever uploaded, may be re-uploaded after a failure (a new
 * job id for the same book), and stays in the library after the job is deleted
 * to reclaim server disk. `jobId` is therefore nullable and replaceable.
 *
 * This row is the single thing the UI observes. The polling code and the
 * download workers only write here, which is what makes the library screen look
 * identical whether a live poll is running or the app was just reopened.
 */
@Entity(
    tableName = "books",
    indices = [Index("jobId"), Index("jobStatus")],
)
data class BookEntity(
    @PrimaryKey val id: String,

    // -- the local file, copied out of the SAF picker at import time ----------
    /** Absolute path inside app-private storage. */
    val epubPath: String,
    /** The name the user picked, for display. The server sanitises its own copy. */
    val originalFilename: String,
    val title: String,
    val author: String? = null,
    /** Extracted cover, written next to the epub. Null when the book has none. */
    val coverPath: String? = null,
    val sizeBytes: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),

    // -- the conversion job --------------------------------------------------
    val jobId: String? = null,
    val jobStatus: JobStatus? = null,
    @ColumnInfo(defaultValue = "0") val jobProgress: Int = 0,
    /** Server-formatted, e.g. `"00d 00h 00m 11s"`. Displayed verbatim. */
    val jobEta: String? = null,
    @ColumnInfo(defaultValue = "0") val jobChaptersDone: Int = 0,
    val jobError: String? = null,
    val jobStartedAt: String? = null,
    val jobFinishedAt: String? = null,
    /** Voice and speed the job was submitted with, so a retry can reuse them. */
    val voice: String? = null,
    val speed: Double? = null,
    /**
     * The server no longer holds this job.
     *
     * Two ways to get here: it returned 404 for the id (its data directory was
     * wiped while the app was closed), or the app deleted the job itself after
     * downloading both files. Either way, stop polling -- without this the app
     * would sit at "queued" forever for a job nobody will ever run.
     *
     * It is *not* on its own a reason to warn the user: combined with
     * [isPlayable] it means "cleaned up", and only without it does it mean
     * "lost, offer a re-upload".
     */
    @ColumnInfo(defaultValue = "0") val jobMissing: Boolean = false,

    // -- upload --------------------------------------------------------------
    @ColumnInfo(defaultValue = "0") val uploadedBytes: Long = 0,
    /**
     * Why the upload failed, when it failed before a job existed.
     *
     * Kept apart from [jobError], which is the server's own traceback: at this
     * point there is no job to have an error, and conflating the two would make
     * "the network dropped" look like "the conversion crashed".
     */
    val uploadError: String? = null,

    // -- the two deliverables ------------------------------------------------
    /**
     * Filenames as the *server* named them, from the job manifest.
     *
     * Worth storing rather than deriving: audiblez builds every output path from
     * the sanitised upload name, and the sync file's `audio_file` field refers to
     * the `.m4b` by that name. Guessing locally would eventually disagree.
     */
    val audiobookRemoteName: String? = null,
    val syncRemoteName: String? = null,
    val audiobookPath: String? = null,
    val audiobookBytes: Long? = null,
    val syncPath: String? = null,
    @ColumnInfo(defaultValue = "'NONE'") val downloadState: DownloadState = DownloadState.NONE,
    @ColumnInfo(defaultValue = "0") val downloadedBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val downloadTotalBytes: Long = 0,
    val downloadError: String? = null,

    // -- reading / playback --------------------------------------------------
    /** Readium `Locator`, serialised as JSON. Where the reader reopens. */
    val readingLocator: String? = null,
    /** Total audio length from the sync file, in milliseconds. */
    val audioDurationMs: Long? = null,
    val lastOpenedAt: Long? = null,
) {
    /** Both deliverables are on disk, so the book can be read along with. */
    val isPlayable: Boolean
        get() = audiobookPath != null && syncPath != null && downloadState == DownloadState.DONE

    /** The server still owes us something, so it is worth polling. */
    val needsPolling: Boolean
        get() = jobId != null && !jobMissing && jobStatus?.isTerminal != true

    /** The job was lost server-side and the app has nothing to show for it. */
    val needsReupload: Boolean
        get() = jobMissing && !isPlayable
}

enum class DownloadState { NONE, QUEUED, RUNNING, DONE, FAILED }
