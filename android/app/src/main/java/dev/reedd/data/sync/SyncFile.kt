package dev.reedd.data.sync

import dev.reedd.data.db.SyncChapterEntity
import dev.reedd.data.db.SyncChunkEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToLong

/**
 * The text-to-timestamp mapping audiblez emits next to the `.m4b`.
 *
 * Format and caveats: `audiblez/SYNC.md`. Times in the file are **seconds from
 * the start of the whole audiobook** as floats.
 */
@Serializable
data class SyncFileDto(
    val version: Int = 1,
    val title: String? = null,
    val author: String? = null,
    @SerialName("audio_file") val audioFile: String? = null,
    @SerialName("sample_rate") val sampleRate: Int = 24_000,
    val duration: Double = 0.0,
    val chapters: List<SyncChapterDto> = emptyList(),
    val chunks: List<SyncChunkDto> = emptyList(),
)

@Serializable
data class SyncChapterDto(
    val index: Int,
    val title: String? = null,
    val source: String? = null,
    val start: Double,
    val end: Double,
)

@Serializable
data class SyncChunkDto(
    val text: String,
    val start: Double,
    val end: Double,
    val chapter: Int = 1,
)

/** A sync file that cannot be used to highlight anything. */
class SyncFileError(message: String) : Exception(message)

/**
 * Reads a downloaded sync file into rows for the database.
 *
 * Seconds become milliseconds here, once, rather than at every playback tick.
 * Rounding is applied to each boundary independently, which preserves the file's
 * guarantee that `chunks[n].end == chunks[n+1].start` -- the same input value
 * rounds to the same output value, so no gap or overlap can appear.
 */
object SyncFileParser {

    /** The format this app understands. A newer file is read, not rejected. */
    const val SUPPORTED_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun parse(file: File, bookId: String): ParsedSync = parse(file.readText(), bookId)

    fun parse(text: String, bookId: String): ParsedSync {
        val dto = try {
            json.decodeFromString<SyncFileDto>(text)
        } catch (e: Exception) {
            throw SyncFileError("the sync file is not valid JSON: ${e.message}")
        }
        if (dto.chunks.isEmpty()) {
            throw SyncFileError("the sync file contains no chunks, so nothing could be highlighted")
        }

        val chunks = dto.chunks.mapIndexed { ordinal, chunk ->
            SyncChunkEntity(
                bookId = bookId,
                ordinal = ordinal,
                text = chunk.text,
                startMs = chunk.start.toMillis(),
                endMs = chunk.end.toMillis(),
                chapter = chunk.chapter,
            )
        }
        val chapters = dto.chapters.map { chapter ->
            SyncChapterEntity(
                bookId = bookId,
                chapterIndex = chapter.index,
                title = chapter.title,
                source = chapter.source,
                startMs = chapter.start.toMillis(),
                endMs = chapter.end.toMillis(),
            )
        }
        return ParsedSync(
            durationMs = dto.duration.toMillis(),
            audioFile = dto.audioFile,
            chunks = chunks,
            chapters = chapters,
            isNewerFormat = dto.version > SUPPORTED_VERSION,
        )
    }

    private fun Double.toMillis(): Long = (this * 1000).roundToLong()
}

data class ParsedSync(
    val durationMs: Long,
    val audioFile: String?,
    val chunks: List<SyncChunkEntity>,
    val chapters: List<SyncChapterEntity>,
    /**
     * The file declares a version this build predates. It is still used -- the
     * fields read here are additive -- but it is worth surfacing if sync looks
     * wrong.
     */
    val isNewerFormat: Boolean,
)
