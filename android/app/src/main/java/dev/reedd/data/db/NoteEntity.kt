package dev.reedd.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A note the reader took against a word or a passage.
 *
 * [spineIndex]/[progression] are resolved once, at save time, from the
 * publication's own reading order -- not derived from [locatorJson] on every
 * read -- so the notes list can sort by "where in the book" with a plain SQL
 * `ORDER BY` instead of re-resolving a `Locator` against a live `Publication`
 * for every row, the same convention [SyncChunkEntity] already uses for its
 * own `resourceHref`/`progression` columns.
 */
@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["bookId", "spineIndex", "progression"])],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    /** What the reader typed. */
    val noteText: String,
    /** The tapped word, or the highlighted passage -- shown as context in the list. */
    val quotedText: String,
    /** `Locator.toJSON().toString()` -- same convention as [BookEntity.readingLocator]. */
    val locatorJson: String,
    /** Denormalized out of the locator for readability, mirrors [SyncChunkEntity.resourceHref]. */
    val resourceHref: String,
    /** Index into `publication.readingOrder` at save time -- primary, cross-chapter sort key. */
    val spineIndex: Int,
    /** Within-resource progression, 0..1 -- secondary sort key, same nullability as [SyncChunkEntity.progression]. */
    val progression: Double?,
    val createdAt: Long,
)
