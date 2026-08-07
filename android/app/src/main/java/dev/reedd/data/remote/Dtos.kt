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
