package dev.reedd.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.reedd.data.align.ChunkAligner
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
    // jobId unique (not just indexed): ServerLibraryAdopter.adopt() decides
    // which server jobs to pull in from a "known jobIds" snapshot taken
    // before any of them are inserted -- two overlapping reconcile() calls
    // (confirmed happening in practice: the Library screen's own launch-time
    // reconcile racing the Admin screen's "Re-check all book metadata")
    // could each decide the same job was new and insert it twice. SQLite's
    // UNIQUE index treats every NULL as distinct from every other NULL, so
    // any number of not-yet-uploaded local books (jobId still null) stays
    // fine -- only two rows sharing the same *non-null* jobId is rejected.
    // See MIGRATION_4_5.
    indices = [Index("jobId", unique = true), Index("jobStatus")],
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
    /** Voice, speed and engine the job was submitted with, so a retry can reuse them. */
    val voice: String? = null,
    val speed: Double? = null,
    val engine: String? = null,
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
    /**
     * Fetch the audiobook the moment this job finishes, no Download tap needed.
     *
     * Set when the app itself submitted the job (`LibraryViewModel.importAndUpload`)
     * and cleared once [dev.reedd.domain.ConversionWatcher] acts on it -- a book
     * adopted from server sync, or one whose conversion was already running before
     * this device knew about it, never gets this set, so downloading stays a user
     * action for those (see [ConversionWatcher.applyJobUpdate]'s own docstring).
     */
    @ColumnInfo(defaultValue = "0") val autoDownload: Boolean = false,

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

    // -- read-along playback -------------------------------------------------
    /** Where the audiobook resumes, in milliseconds. */
    @ColumnInfo(defaultValue = "0") val playbackPositionMs: Long = 0,
    /**
     * Added to the player's position before looking up a sentence.
     *
     * `audiblez/SYNC.md` notes the `.m4b` carries roughly 40-90 ms of AAC priming
     * that the timestamps do not describe, so highlighting can run consistently
     * early. Its own advice is to correct in the player rather than in the file.
     * Default 0: ExoPlayer honours ffmpeg's gapless metadata, so this may be
     * unnecessary, and it is worth measuring before compensating.
     */
    @ColumnInfo(defaultValue = "0") val syncOffsetMs: Long = 0,
    /**
     * How many chunks the aligner could locate on the page, and how many there
     * are in total. Surfaced on the detail screen so a badly-aligned book is
     * visible rather than just mysteriously not highlighting.
     */
    @ColumnInfo(defaultValue = "0") val alignedChunks: Int = 0,
    @ColumnInfo(defaultValue = "0") val totalChunks: Int = 0,
    /**
     * Which [dev.reedd.data.align.ChunkAligner.ALIGNMENT_VERSION] produced
     * [alignedChunks]. A row written before this column existed reads 0,
     * always below the current constant, so [needsAlignment] treats it the
     * same as a book that has never been aligned at all -- see
     * [dev.reedd.data.align.ChunkAligner]'s own doc on why that matters: an
     * aligner improvement must reach books that already ran an older pass,
     * not just ones downloaded from now on.
     */
    @ColumnInfo(defaultValue = "0") val alignmentVersion: Int = 0,

    // -- sort/filter (see SORT_GROUP_LIBRARY.md) ------------------------------
    /**
     * "Fiction" / "Non-fiction", from the server's best-effort lookup keyed on
     * title+author (`server/app/book_metadata.py`). Null means "not looked up
     * yet, or nothing usable was found" -- both read as Unknown; the app never
     * has enough information itself to tell those two apart.
     */
    val category: String? = null,
    /** Zero or more of a fixed tag set (Horror, Romance, ...), same lookup as
     *  [category]. A book can carry several at once, unlike [category]. */
    @ColumnInfo(defaultValue = "[]") val genres: List<String> = emptyList(),
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

    /**
     * The book has audio and a mapping, but nothing has been aligned to the page.
     *
     * Playback still works; only the highlighting is missing. Drives the lazy
     * re-run for books downloaded before the aligner existed.
     */
    val needsAlignment: Boolean
        get() = isPlayable && totalChunks > 0 &&
            (alignedChunks == 0 || alignmentVersion < ChunkAligner.ALIGNMENT_VERSION)
}

enum class DownloadState { NONE, QUEUED, RUNNING, DONE, FAILED }
