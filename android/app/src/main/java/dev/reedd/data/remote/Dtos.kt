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
     *  server's default engine (kokoro unless configured otherwise). */
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

/** The server's status strings, plus a bucket for anything newer than this app. */
enum class JobStatus {
    QUEUED, RUNNING, DONE, ERROR, UNKNOWN;

    val isTerminal: Boolean get() = this == DONE || this == ERROR

    companion object {
        fun fromWire(value: String?): JobStatus = when (value?.lowercase()) {
            "queued" -> QUEUED
            "running" -> RUNNING
            "done" -> DONE
            "error" -> ERROR
            else -> UNKNOWN
        }
    }
}
