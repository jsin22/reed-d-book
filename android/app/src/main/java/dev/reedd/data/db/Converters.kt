package dev.reedd.data.db

import androidx.room.TypeConverter
import dev.reedd.data.remote.JobStatus

/**
 * Enums are stored as their names rather than ordinals: an ordinal silently
 * changes meaning if a value is ever inserted into the middle of the enum, and
 * a stored name is legible when reading the database by hand.
 */
class Converters {
    @TypeConverter
    fun jobStatusToString(status: JobStatus?): String? = status?.name

    @TypeConverter
    fun stringToJobStatus(value: String?): JobStatus? =
        value?.let { runCatching { JobStatus.valueOf(it) }.getOrDefault(JobStatus.UNKNOWN) }

    @TypeConverter
    fun downloadStateToString(state: DownloadState): String = state.name

    @TypeConverter
    fun stringToDownloadState(value: String): DownloadState =
        runCatching { DownloadState.valueOf(value) }.getOrDefault(DownloadState.NONE)
}
