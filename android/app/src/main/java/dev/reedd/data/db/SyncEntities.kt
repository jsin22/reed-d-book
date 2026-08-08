package dev.reedd.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The text-to-timestamp mapping from the downloaded sync `.json`, one row per
 * sentence, in playback order. Format documented in `audiblez/SYNC.md`.
 *
 * Times are stored as **milliseconds**, not the file's float seconds: the player
 * reports its position in milliseconds, so keeping the same unit makes the
 * Phase 4 lookup an integer range comparison the database can index rather than
 * a float conversion per frame.
 *
 * Times are also from the start of the whole audiobook, not the chapter, again
 * because that is what a player reports.
 */
@Entity(
    tableName = "sync_chunks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    // Composite index on (bookId, startMs): Phase 4 looks up "the chunk covering
    // position X in this book" on every playback tick.
    indices = [Index(value = ["bookId", "startMs"])],
)
data class SyncChunkEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val bookId: String,
    /** Zero-based position in playback order. */
    val ordinal: Int,
    /**
     * Verbatim from the sentence splitter, leading newlines included, so it can
     * be string-matched against the epub text.
     *
     * Chunk 0 of chapter 1 is audiblez' injected `"<title> - <author>."` and
     * matches nothing in the book.
     */
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** 1-based chapter number, matching [SyncChapterEntity.chapterIndex]. */
    val chapter: Int,

    // -- where this sentence sits on the page (filled in by the aligner) ------
    /**
     * The epub resource this sentence is in, e.g. `chap_01.xhtml`. Becomes the
     * `href` of the Readium `Locator` used to highlight it.
     */
    val resourceHref: String? = null,
    /**
     * The sentence **as it appears in the epub**, which is not always
     * [text]: audiblez appends a `.` to anything that does not end in one, and
     * normalises whitespace. Readium's JS resolves a locator by searching the
     * rendered DOM for this string, so it has to be the text really on the page.
     *
     * Null when the aligner could not find the sentence; that chunk simply is not
     * highlighted, and the audio still plays.
     */
    val textHighlight: String? = null,
    /**
     * Context either side, used as the text-quote anchor's prefix and suffix so a
     * sentence that occurs more than once in a chapter resolves to the right
     * occurrence rather than the first.
     */
    val textBefore: String? = null,
    val textAfter: String? = null,
    /**
     * Roughly how far through the resource this sentence is, 0..1.
     *
     * Belt and braces for navigation: Readium resolves a locator from the text
     * anchor, but `go()` can also work from a progression, so a sentence whose text
     * anchor fails can still be scrolled to approximately rather than not at all.
     */
    val progression: Double? = null,
) {
    /** True when this sentence can be located on the page. */
    val isAligned: Boolean get() = resourceHref != null && textHighlight != null
}

/**
 * Chapter boundaries from the same file, so the reader can flip pages on a
 * chapter change without re-deriving them from the epub.
 */
@Entity(
    tableName = "sync_chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["bookId", "chapterIndex"])],
)
data class SyncChapterEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val bookId: String,
    /** 1-based, as produced by audiblez. Not called `index`, which is SQL-reserved. */
    val chapterIndex: Int,
    val title: String?,
    /** The epub resource this chapter came from, e.g. `chap_01.xhtml`. */
    val source: String?,
    val startMs: Long,
    val endMs: Long,
)
