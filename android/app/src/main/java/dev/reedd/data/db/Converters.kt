package dev.reedd.data.db

import androidx.room.TypeConverter
import dev.reedd.data.remote.JobStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    /**
     * JSON rather than a delimiter-joined string: a genre tag comes from the
     * server's own keyword list (`_GENRE_KEYWORDS` in `book_metadata.py`), all
     * plain words today, but nothing enforces that stays true, and a stray
     * comma silently corrupting a stored list is worse than the few extra
     * bytes JSON costs.
     */
    @TypeConverter
    fun genresToString(genres: List<String>): String = Json.encodeToString(genres)

    @TypeConverter
    fun stringToGenres(value: String): List<String> =
        runCatching { Json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
}
