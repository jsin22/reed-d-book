package dev.reedd.domain

import dev.reedd.data.db.BookEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId

/** How the library screen groups its cards. Persisted by name (see
 *  `SettingsStore.LibraryViewSettings`), like [LibrarySort]; a saved name that
 *  no longer exists reads back as [NONE]. */
enum class LibraryGrouping { NONE, READING_STATUS, LENGTH, LAST_OPENED }

/**
 * One collapsible section of the library.
 *
 * @property key stable identity for remembering collapsed state: the grouping
 *   plus the label, so a label shared by two groupings never collides.
 */
data class BookGroup(val key: String, val label: String, val books: List<BookEntity>)

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val HOUR_MS = 60L * 60 * 1000

/** Opened within this long (and not finished) counts as Reading now. */
const val READING_NOW_WINDOW_MS = 14 * DAY_MS

/** Read or listened at least this far through counts as Finished. */
const val FINISHED_PROGRESSION = 0.95

/**
 * A book's place in the Reading status grouping that the reader can also set
 * by hand (see [BookEntity.statusOverride]). Persisted by name.
 */
enum class ReadingStatus(val label: String) {
    READING_NOW("Reading now"),
    ON_HOLD("On hold"),
    NOT_STARTED("Not started"),
    FINISHED("Finished"),
}

/** The one Reading status group nobody can move a book into: it is a fact
 *  (no audiobook on this phone), not a status. */
const val STATUS_NOT_DOWNLOADED = "Not downloaded"

const val LENGTH_SHORT = "Short (under 3h)"
const val LENGTH_MEDIUM = "Medium (3–8h)"
const val LENGTH_LONG = "Long (8–15h)"
const val LENGTH_VERY_LONG = "Very long (15h+)"
const val LENGTH_UNKNOWN = "Unknown length"

const val OPENED_TODAY = "Today"
const val OPENED_THIS_WEEK = "This week"
const val OPENED_THIS_MONTH = "This month"
const val OPENED_EARLIER = "Earlier"
const val OPENED_NEVER = "Never opened"

/**
 * Splits an already sorted and filtered list into groups, keeping each book's
 * order within its group -- so the reader's chosen sort still applies inside
 * every group. Groups come in a fixed order; empty ones are left out.
 * [LibraryGrouping.NONE] returns no groups at all. Pure, unit-tested in
 * `LibraryGroupingTest.kt`.
 */
fun List<BookEntity>.grouped(
    by: LibraryGrouping,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<BookGroup> {
    val (labels, labelFor) = when (by) {
        LibraryGrouping.NONE -> return emptyList()
        LibraryGrouping.READING_STATUS ->
            ReadingStatus.entries.map { it.label } + STATUS_NOT_DOWNLOADED to
                { book: BookEntity -> readingStatus(book, now)?.label ?: STATUS_NOT_DOWNLOADED }
        LibraryGrouping.LENGTH ->
            listOf(LENGTH_SHORT, LENGTH_MEDIUM, LENGTH_LONG, LENGTH_VERY_LONG, LENGTH_UNKNOWN) to
                { book: BookEntity -> lengthBucket(book.audioDurationMs) }
        LibraryGrouping.LAST_OPENED ->
            listOf(OPENED_TODAY, OPENED_THIS_WEEK, OPENED_THIS_MONTH, OPENED_EARLIER, OPENED_NEVER) to
                { book: BookEntity -> openedBucket(book.lastOpenedAt, now, zone) }
    }
    val byLabel = groupBy(labelFor)
    return labels.mapNotNull { label -> byLabel[label]?.let { BookGroup("${by.name}:$label", label, it) } }
}

/**
 * How far through the book the reader is, 0.0-1.0: Readium's own
 * `totalProgression` from the saved reading position, or null if the book has
 * never been read (or the position does not say).
 */
fun readingProgression(book: BookEntity): Double? {
    val locator = book.readingLocator ?: return null
    return runCatching {
        Json.parseToJsonElement(locator).jsonObject["locations"]?.jsonObject
            ?.get("totalProgression")?.jsonPrimitive?.doubleOrNull
    }.getOrNull()
}

/**
 * Where the book sits in the Reading status grouping: the status the reader
 * set by hand if any (it wins outright, so a swipe always visibly moves the
 * book), otherwise null for a book with no audiobook on this phone (shown as
 * [STATUS_NOT_DOWNLOADED]), otherwise [autoStatus].
 */
fun readingStatus(book: BookEntity, now: Long = System.currentTimeMillis()): ReadingStatus? =
    manualStatus(book) ?: if (!book.isPlayable) null else autoStatus(book, now)

/** The reader's own status for this book, if they set one. */
fun manualStatus(book: BookEntity): ReadingStatus? =
    book.statusOverride?.let { name -> ReadingStatus.entries.firstOrNull { it.name == name } }

/** What the automatic rules say, ignoring any manual status. */
fun autoStatus(book: BookEntity, now: Long): ReadingStatus {
    val opened = book.lastOpenedAt
    return when {
        autoFinished(book) -> ReadingStatus.FINISHED
        opened == null -> ReadingStatus.NOT_STARTED
        now - opened <= READING_NOW_WINDOW_MS -> ReadingStatus.READING_NOW
        else -> ReadingStatus.ON_HOLD
    }
}

/**
 * Finished by either measure: the page shown ([readingProgression]) or the
 * audio heard. Both are needed -- listening with the phone locked keeps saving
 * the listening position, but the page only follows the audio while the reader
 * is on screen, so a book heard to the end can still have its page position
 * wherever the reader last looked.
 */
fun autoFinished(book: BookEntity): Boolean {
    val pageDone = (readingProgression(book) ?: 0.0) >= FINISHED_PROGRESSION
    val duration = book.audioDurationMs
    val audioDone = duration != null && duration > 0 &&
        book.playbackPositionMs >= FINISHED_PROGRESSION * duration
    return pageDone || audioDone
}

/** One button revealed by swiping a library card. */
sealed interface StatusAction {
    /** Move the book to [status]. */
    data class MoveTo(val status: ReadingStatus) : StatusAction
    /** Drop the reader's own status and let the automatic rules decide. */
    data object Auto : StatusAction
}

/**
 * The buttons a swipe reveals for [book]: every status it is not in, plus
 * [StatusAction.Auto] when the reader has set one by hand.
 */
fun statusActions(book: BookEntity, now: Long = System.currentTimeMillis()): List<StatusAction> {
    val current = readingStatus(book, now)
    val moves = ReadingStatus.entries.filter { it != current }.map { StatusAction.MoveTo(it) }
    return if (manualStatus(book) != null) moves + StatusAction.Auto else moves
}

private fun lengthBucket(durationMs: Long?): String = when {
    durationMs == null -> LENGTH_UNKNOWN
    durationMs < 3 * HOUR_MS -> LENGTH_SHORT
    durationMs < 8 * HOUR_MS -> LENGTH_MEDIUM
    durationMs < 15 * HOUR_MS -> LENGTH_LONG
    else -> LENGTH_VERY_LONG
}

/** Today is the calendar day in [zone]; week and month are the last 7 and 30 days. */
private fun openedBucket(openedAt: Long?, now: Long, zone: ZoneId): String {
    if (openedAt == null) return OPENED_NEVER
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val openedDay = Instant.ofEpochMilli(openedAt).atZone(zone).toLocalDate()
    return when {
        !openedDay.isBefore(today) -> OPENED_TODAY
        now - openedAt <= 7 * DAY_MS -> OPENED_THIS_WEEK
        now - openedAt <= 30 * DAY_MS -> OPENED_THIS_MONTH
        else -> OPENED_EARLIER
    }
}
