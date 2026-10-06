package dev.reedd.domain

import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class LibraryGroupingTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val zone = ZoneOffset.UTC
    /** 2026-10-03 15:00 UTC. */
    private val now = LocalDateTime.of(2026, 10, 3, 15, 0).toInstant(zone).toEpochMilli()

    private fun book(
        id: String,
        lastOpenedAt: Long? = null,
        progression: Double? = null,
        durationMs: Long? = null,
        downloaded: Boolean = true,
    ) = BookEntity(
        id = id, epubPath = "/tmp/$id.epub", originalFilename = "$id.epub", title = "Book $id",
        lastOpenedAt = lastOpenedAt, audioDurationMs = durationMs,
        readingLocator = progression?.let {
            """{"href":"c1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5,"totalProgression":$it}}"""
        },
    ).let {
        if (downloaded) it.copy(audiobookPath = "/tmp/$id.m4b", syncPath = "/tmp/$id.json", downloadState = DownloadState.DONE)
        else it
    }

    private fun List<BookEntity>.labelsBy(grouping: LibraryGrouping) =
        grouped(grouping, now, zone).associate { group -> group.label to group.books.map { it.id } }

    // -- reading status ----------------------------------------------------------

    @Test
    fun `reading status sorts books into now, on hold, not started, finished`() {
        val books = listOf(
            book("reading", lastOpenedAt = now - 3 * day, progression = 0.4),
            book("paused", lastOpenedAt = now - 20 * day, progression = 0.4),
            book("new"),
            book("done", lastOpenedAt = now - 1 * day, progression = 0.97),
            book("remote", downloaded = false),
        )
        val groups = books.grouped(LibraryGrouping.READING_STATUS, now, zone)
        assertEquals(
            listOf(ReadingStatus.READING_NOW.label, ReadingStatus.ON_HOLD.label, ReadingStatus.NOT_STARTED.label, ReadingStatus.FINISHED.label, STATUS_NOT_DOWNLOADED),
            groups.map { it.label },
        )
        assertEquals(listOf("reading", "paused", "new", "done", "remote"), groups.map { it.books.single().id })
    }

    @Test
    fun `not downloaded takes priority over how far the book has been read`() {
        val books = listOf(
            book("read-live", lastOpenedAt = now - 1 * day, progression = 0.5, downloaded = false),
            book("finished-elsewhere", lastOpenedAt = now - 1 * day, progression = 0.99, downloaded = false),
        )
        assertEquals(
            mapOf(STATUS_NOT_DOWNLOADED to listOf("read-live", "finished-elsewhere")),
            books.labelsBy(LibraryGrouping.READING_STATUS),
        )
    }

    @Test
    fun `finished wins even for a book long untouched, and just under is not finished`() {
        val books = listOf(
            book("old-done", lastOpenedAt = now - 200 * day, progression = 0.99),
            book("almost", lastOpenedAt = now - 1 * day, progression = 0.94),
        )
        val labels = books.labelsBy(LibraryGrouping.READING_STATUS)
        assertEquals(listOf("old-done"), labels[ReadingStatus.FINISHED.label])
        assertEquals(listOf("almost"), labels[ReadingStatus.READING_NOW.label])
    }

    @Test
    fun `a book heard to the end is finished even if its page position lags behind`() {
        // Listening with the phone locked: the audio position keeps saving, the
        // page does not follow.
        val heard = book("heard", lastOpenedAt = now - 1 * day, progression = 0.30, durationMs = 10 * hour)
            .copy(playbackPositionMs = (9.6 * hour).toLong())
        val halfway = book("halfway", lastOpenedAt = now - 1 * day, progression = 0.30, durationMs = 10 * hour)
            .copy(playbackPositionMs = 5 * hour)
        val labels = listOf(heard, halfway).labelsBy(LibraryGrouping.READING_STATUS)
        assertEquals(listOf("heard"), labels[ReadingStatus.FINISHED.label])
        assertEquals(listOf("halfway"), labels[ReadingStatus.READING_NOW.label])
    }

    @Test
    fun `a manual status beats the automatic rules, in any direction`() {
        val shelved = book("shelved", lastOpenedAt = now - 1 * day, progression = 0.3).copy(statusOverride = "ON_HOLD")
        val unread = book("unread", lastOpenedAt = now - 1 * day, progression = 0.1).copy(statusOverride = "FINISHED")
        val skimmed = book("skimmed", lastOpenedAt = now - 1 * day, progression = 0.99).copy(statusOverride = "READING_NOW")
        val labels = listOf(shelved, unread, skimmed).labelsBy(LibraryGrouping.READING_STATUS)
        assertEquals(listOf("shelved"), labels[ReadingStatus.ON_HOLD.label])
        assertEquals(listOf("unread"), labels[ReadingStatus.FINISHED.label])
        assertEquals(listOf("skimmed"), labels[ReadingStatus.READING_NOW.label])
    }

    @Test
    fun `a manual status moves even a not-downloaded book, but the automatic rules do not`() {
        val marked = book("marked", downloaded = false).copy(statusOverride = "FINISHED")
        val autoOnly = book("auto", lastOpenedAt = now - 1 * day, progression = 0.99, downloaded = false)
        val labels = listOf(marked, autoOnly).labelsBy(LibraryGrouping.READING_STATUS)
        assertEquals(listOf("marked"), labels[ReadingStatus.FINISHED.label])
        assertEquals(listOf("auto"), labels[STATUS_NOT_DOWNLOADED])
    }

    @Test
    fun `an unknown saved status is ignored rather than trusted`() {
        val odd = book("odd", lastOpenedAt = now - 1 * day).copy(statusOverride = "ABANDONED")
        assertEquals(ReadingStatus.READING_NOW, readingStatus(odd, now))
    }

    // -- swipe actions -----------------------------------------------------------

    @Test
    fun `swipe offers every status except the current one`() {
        val reading = book("r", lastOpenedAt = now - 1 * day)
        assertEquals(
            listOf(ReadingStatus.ON_HOLD, ReadingStatus.NOT_STARTED, ReadingStatus.FINISHED).map { StatusAction.MoveTo(it) },
            statusActions(reading, now),
        )
    }

    @Test
    fun `swipe adds Auto once a status was set by hand`() {
        val manual = book("m", lastOpenedAt = now - 1 * day).copy(statusOverride = "ON_HOLD")
        val actions = statusActions(manual, now)
        assertEquals(StatusAction.Auto, actions.last())
        assertTrue(StatusAction.MoveTo(ReadingStatus.ON_HOLD) !in actions)
        assertEquals(4, actions.size)
    }

    @Test
    fun `a not-downloaded book can be swiped to any status`() {
        assertEquals(ReadingStatus.entries.map { StatusAction.MoveTo(it) }, statusActions(book("x", downloaded = false), now))
    }

    @Test
    fun `progression comes from the saved locator, and is null when absent or malformed`() {
        assertEquals(0.4, readingProgression(book("a", progression = 0.4))!!, 1e-9)
        assertNull(readingProgression(book("b")))
        assertNull(readingProgression(book("c").copy(readingLocator = "not json")))
    }

    // -- length --------------------------------------------------------------------

    @Test
    fun `length buckets at 3, 8 and 15 hours, unknown last`() {
        val books = listOf(
            book("tale", durationMs = (0.3 * hour).toLong()),
            book("novella", durationMs = 3 * hour),
            book("novel", durationMs = 8 * hour),
            book("epic", durationMs = 39 * hour),
            book("undownloaded"),
        )
        val groups = books.grouped(LibraryGrouping.LENGTH, now, zone)
        assertEquals(
            listOf(LENGTH_SHORT, LENGTH_MEDIUM, LENGTH_LONG, LENGTH_VERY_LONG, LENGTH_UNKNOWN),
            groups.map { it.label },
        )
        assertEquals(listOf("tale", "novella", "novel", "epic", "undownloaded"), groups.map { it.books.single().id })
    }

    // -- last opened ---------------------------------------------------------------

    @Test
    fun `last opened uses the calendar day for today, then 7 and 30 day windows`() {
        val startOfToday = LocalDateTime.of(2026, 10, 3, 0, 5).toInstant(zone).toEpochMilli()
        val books = listOf(
            book("early-today", lastOpenedAt = startOfToday),
            book("yesterday", lastOpenedAt = now - 16 * hour),
            book("two-weeks", lastOpenedAt = now - 14 * day),
            book("ancient", lastOpenedAt = now - 90 * day),
            book("never"),
        )
        val labels = books.labelsBy(LibraryGrouping.LAST_OPENED)
        assertEquals(listOf("early-today"), labels[OPENED_TODAY])
        assertEquals(listOf("yesterday"), labels[OPENED_THIS_WEEK])
        assertEquals(listOf("two-weeks"), labels[OPENED_THIS_MONTH])
        assertEquals(listOf("ancient"), labels[OPENED_EARLIER])
        assertEquals(listOf("never"), labels[OPENED_NEVER])
    }

    // -- general -------------------------------------------------------------------

    @Test
    fun `books keep their sorted order within a group`() {
        val books = listOf(book("b", durationMs = hour), book("a", durationMs = 2 * hour))
        assertEquals(listOf("b", "a"), books.grouped(LibraryGrouping.LENGTH, now, zone).single().books.map { it.id })
    }

    @Test
    fun `empty groups are left out and none means no groups`() {
        val books = listOf(book("1"))
        assertEquals(listOf(OPENED_NEVER), books.grouped(LibraryGrouping.LAST_OPENED, now, zone).map { it.label })
        assertTrue(books.grouped(LibraryGrouping.NONE, now, zone).isEmpty())
    }

    @Test
    fun `group keys are namespaced by grouping`() {
        val group = listOf(book("1")).grouped(LibraryGrouping.READING_STATUS, now, zone).single()
        assertEquals("READING_STATUS:${ReadingStatus.NOT_STARTED.label}", group.key)
    }
}
