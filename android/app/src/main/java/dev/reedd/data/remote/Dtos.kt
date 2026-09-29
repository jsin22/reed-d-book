package dev.reedd.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the conversion server. These mirror `job.json` field for
 * field (see `server/app/store.py`); anything the app derives lives in the Room
 * entity instead, so a server-side change shows up here as a compile error
 * rather than as a silently-missing value.
 */
@Serializable
data class JobDto(
    @SerialName("job_id") val jobId: String,
    val status: String,
    val filename: String,
    /** Sent by the uploading device from what it already extracted at import
     *  time (see UploadWorker) -- used both for the server's category/genre
     *  lookup and, since a job's own manifest already has it, so a *different*
     *  device adopting this job can show a real title/author immediately,
     *  without downloading the epub just to find out what it's called. */
    val title: String? = null,
    val author: String? = null,
    val voice: String,
    val speed: Double,
    /** Absent on a job created before the server tracked this; treat as the
     *  server's default engine (pocket_tts unless configured otherwise --
     *  and, as of this session, the only engine the server accepts at all;
     *  see server/app/audiblez_meta.py). */
    val engine: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val progress: Int = 0,
    /** Server-formatted, e.g. `"00d 00h 00m 11s"`. Not parsed; shown as given. */
    val eta: String? = null,
    @SerialName("chapters_done") val chaptersDone: Int = 0,
    val error: String? = null,
    @SerialName("celery_task_id") val celeryTaskId: String? = null,
    val audiobook: FileRefDto? = null,
    val sync: FileRefDto? = null,
    /** Absent on a job whose epub had no cover and nothing was ever found for
     *  it at `GET /api/jobs/{id}/cover` -- see that endpoint's own doc for
     *  why polling alone never fills this in: the lookup only runs when a
     *  device actually downloads the cover, not on every poll. */
    val cover: FileRefDto? = null,
    /** Absent on a job from before per-user accounts existed; treat as private. */
    val public: Boolean = false,
    /** 'offline' (default), 'live', or 'live_offline' -- see server/app/main.py's
     *  UPLOAD_MODES. Absent on a job created before this field existed; treat as
     *  'offline', the only behavior that existed then. Purely descriptive of what
     *  the uploader chose; never changes after creation, unlike [status]. */
    val mode: String? = null,
    /** Only ever present from `GET /api/admin/jobs` -- null everywhere else. */
    @SerialName("owner_email") val ownerEmail: String? = null,
    /**
     * Filled in asynchronously by a background lookup keyed on the title/author
     * sent at upload (see `server/app/book_metadata.py`). Null/empty on a fresh
     * job, and stays that way if the book could not be identified -- a poll
     * simply never learns anything new for it, same as any other still-blank
     * field on a job that will never change again.
     */
    val category: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
data class FileRefDto(
    val file: String,
    val bytes: Long,
)

@Serializable
data class JobListDto(val jobs: List<JobDto>)

// -- live reading (CPU_LIVE_READING_PLAN Phase 4) ----------------------------

@Serializable
data class LiveStartBody(
    /** The epub-internal resource path, e.g. "OEBPS/xhtml/chapter1.xhtml"
     *  -- resolved server-side by bare filename, not exact path (see
     *  server/app/live_reading.py's own doc on why). */
    @SerialName("resource_href") val resourceHref: String,
    @SerialName("from_sentence_index") val fromSentenceIndex: Int = 0,
    /** 0..1, "how far into this resource" -- tried only when [anchorText]
     *  finds nothing. Overrides [fromSentenceIndex] server-side when
     *  present: only the server can turn a proportion into a real sentence
     *  index, since it has the real sentence list (see live_reading.start's
     *  own doc). */
    @SerialName("from_fraction") val fromFraction: Double? = null,
    /** A window of text (this device's own epub extraction) centered on
     *  the actual tap point -- tried before [fromFraction], since it is an
     *  exact match immune to the server's sentence count disagreeing with
     *  this device's own (see `LiveChunkSource.startFromTap`'s own doc). */
    @SerialName("anchor_text") val anchorText: String? = null,
    /** Where the tap actually landed within [anchorText] (0-based char
     *  offset), when known -- the window is not always centered on the tap
     *  (clamped at either edge of the resource's own text), so the server
     *  must not assume the tap sits at the window's midpoint. See
     *  `LiveChunkSource.preciseLocation`'s own doc for the real, confirmed-
     *  live bug this fixes: a tap on the very first line landed the anchor
     *  window at [0, tap+300), whose midpoint sits well past the actual
     *  tap, resolving to a later sentence than the one tapped. */
    @SerialName("anchor_offset") val anchorOffset: Int? = null,
    val voice: String,
)

@Serializable
data class LiveSessionDto(
    @SerialName("session_id") val sessionId: String,
    /** The sentence index the server actually started from -- exact when
     *  [LiveStartBody.fromSentenceIndex] was given, resolved from
     *  [LiveStartBody.fromFraction] otherwise. The caller needs this back
     *  (not just what it originally asked for) to keep its own chunk-
     *  ordinal bookkeeping correct. */
    @SerialName("from_sentence_index") val fromSentenceIndex: Int = 0,
)

@Serializable
data class LiveAdvanceBody(@SerialName("now_at_sentence_index") val nowAtSentenceIndex: Int)

@Serializable
data class LiveChunkInfoDto(
    val index: Int,
    @SerialName("duration_s") val durationS: Double,
    val chars: Int,
    val text: String,
)

@Serializable
data class LiveStatusDto(
    /** "running" | "done" | "error" | "stopped" -- plain String, not an
     *  enum: this is polled every few hundred ms and only ever compared
     *  against a couple of literal values, not branched on exhaustively
     *  the way [JobStatus] is. */
    val status: String,
    val error: String? = null,
    @SerialName("resource_href") val resourceHref: String,
    val cursor: Int,
    @SerialName("total_sentences") val totalSentences: Int,
    val chunks: List<LiveChunkInfoDto>,
)

@Serializable
data class StoppedDto(val stopped: Boolean)

@Serializable
data class VoicesDto(
    /** Empty when the server has no TTS stack installed; it then skips validation. */
    val voices: List<String> = emptyList(),
    val default: String? = null,
)

/** One TTS backend and the voices it accepts, from `GET /api/engines`. */
@Serializable
data class EngineDto(
    val id: String,
    val voices: List<String> = emptyList(),
    @SerialName("default_voice") val defaultVoice: String? = null,
)

@Serializable
data class EnginesDto(
    /** Empty when the server has no TTS stack installed; same fallback-to-free-text
     *  case as [VoicesDto.voices] being empty. */
    val engines: List<EngineDto> = emptyList(),
    val default: String? = null,
)

@Serializable
data class HealthDto(
    val status: String,
    @SerialName("data_dir") val dataDir: String? = null,
    val broker: String? = null,
)

@Serializable
data class DeleteDto(
    @SerialName("job_id") val jobId: String,
    val deleted: Boolean,
)

/** `GET /api/me` -- who the current token belongs to. */
@Serializable
data class MeDto(
    @SerialName("user_id") val userId: String,
    val email: String,
    @SerialName("is_admin") val isAdmin: Boolean,
)

/** One row of `GET /api/admin/users`. Never carries a token -- the server
 *  only ever returns one, once, from [InviteResultDto]. */
@Serializable
data class UserDto(
    @SerialName("user_id") val userId: String,
    val email: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class UserListDto(val users: List<UserDto>)

@Serializable
data class AdminJobListDto(val jobs: List<JobDto>)

@Serializable
data class InviteRequestDto(val email: String)

/** The plaintext token is only ever seen here, once, right after an invite. */
@Serializable
data class InviteResultDto(
    val user: UserDto,
    val token: String? = null,
    @SerialName("email_sent") val emailSent: Boolean = false,
)

@Serializable
data class PublicUpdateDto(val public: Boolean)

/** `DELETE /api/admin/users/{user_id}`'s response. */
@Serializable
data class UserDeleteDto(
    @SerialName("user_id") val userId: String,
    val deleted: Boolean,
)

/**
 * `GET /api/admin/metadata-health` -- whether the category/genre lookup
 * (Gemini, the sole source since Open Library/Google Books were removed;
 * see `LLM_GENRE_ENRICHMENT.md`) is currently working. There is no second
 * source to quietly fall back to any more, so the admin screen surfaces
 * this directly rather than letting "books never get tagged" happen with
 * no visible reason why.
 */
@Serializable
data class MetadataHealthDto(
    val ok: Boolean,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_error_at") val lastErrorAt: String? = null,
    @SerialName("last_success_at") val lastSuccessAt: String? = null,
)

/** One `.apk` file the server knows about -- either what `GET /download/app`
 *  currently serves, or what a rebuild left waiting to be pushed there. */
@Serializable
data class ApkInfoDto(
    val filename: String,
    val bytes: Long,
    @SerialName("built_at") val builtAt: String,
)

/** `GET /api/admin/apk` -- what invitees get right now vs. what a "Push
 *  Update" would replace it with, if anything. Either can be null: `live`
 *  when `REEDD_APK_PATH` is unconfigured or the file is missing, `pending`
 *  when no build is sitting in `REEDD_APK_BUILD_DIR`. */
@Serializable
data class ApkStatusDto(
    val live: ApkInfoDto? = null,
    val pending: ApkInfoDto? = null,
)

/** `POST /api/admin/push-apk` -- what is live immediately after the push. */
@Serializable
data class PushApkResultDto(
    val pushed: ApkInfoDto,
)

/** The server's status strings, plus a bucket for anything newer than this app. */
enum class JobStatus {
    QUEUED, RUNNING, DONE, ERROR,
    /** A `mode=live` upload's own terminal status -- see UploadMode. Never
     *  transitions to RUNNING/DONE: no Celery task exists for it at all. */
    LIVE_ONLY,
    UNKNOWN;

    val isTerminal: Boolean get() = this == DONE || this == ERROR || this == LIVE_ONLY

    companion object {
        fun fromWire(value: String?): JobStatus = when (value?.lowercase()) {
            "queued" -> QUEUED
            "running" -> RUNNING
            "done" -> DONE
            "error" -> ERROR
            "live_only" -> LIVE_ONLY
            else -> UNKNOWN
        }
    }
}

/**
 * What POST /api/jobs' `mode` field accepts (see server/app/main.py's
 * UPLOAD_MODES) -- chosen once at upload time (ImportSheet.kt) and never
 * changed after, unlike [JobStatus]. `OFFLINE` is what every upload meant
 * before this existed, so it is both the default and the fallback for a
 * job created before the server tracked this at all.
 */
enum class UploadMode {
    OFFLINE, LIVE, LIVE_OFFLINE;

    /** Whether a book in this mode can be read live at all, regardless of
     *  whether a background conversion has also finished. */
    val canReadLive: Boolean get() = this == LIVE || this == LIVE_OFFLINE

    companion object {
        fun fromWire(value: String?): UploadMode = when (value?.lowercase()) {
            "live" -> LIVE
            "live_offline" -> LIVE_OFFLINE
            else -> OFFLINE
        }
    }
}
