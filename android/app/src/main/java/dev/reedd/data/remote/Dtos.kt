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
    /** Absent on a job from before per-user accounts existed; treat as private. */
    val public: Boolean = false,
    /** Only ever present from `GET /api/admin/jobs` -- null everywhere else. */
    @SerialName("owner_email") val ownerEmail: String? = null,
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
