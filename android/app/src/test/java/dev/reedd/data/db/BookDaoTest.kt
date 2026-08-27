package dev.reedd.data.db

import dev.reedd.data.remote.JobStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookDaoTest {

    private lateinit var db: ReeddDatabase
    private lateinit var dao: BookDao

    @Before
    fun setUp() {
        db = inMemoryDb()
        dao = db.books()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `newest book comes first`() = runTest {
        dao.insert(book("old", addedAt = 100))
        dao.insert(book("new", addedAt = 200))

        assertEquals(listOf("new", "old"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun `attaching a job clears the previous attempt`() = runTest {
        dao.insert(book("b1"))
        dao.updateJobState(
            id = "b1", status = JobStatus.ERROR, progress = 40, eta = null, chaptersDone = 1,
            error = "ffmpeg exploded", startedAt = "t0", finishedAt = "t1", audiobookBytes = null,
            audiobookRemoteName = null, syncRemoteName = null,
        )
        dao.markJobMissing("b1")

        dao.attachJob("b1", jobId = "job-2", status = JobStatus.QUEUED, voice = "af_sky", speed = 1.25, engine = "kokoro")

        val book = dao.get("b1")!!
        assertEquals("job-2", book.jobId)
        assertEquals(JobStatus.QUEUED, book.jobStatus)
        assertEquals("af_sky", book.voice)
        assertEquals(1.25, book.speed!!, 0.0)
        // A retry must not inherit the old failure, or the UI would show an
        // error banner over a job that is running fine.
        assertNull(book.jobError)
        assertEquals(0, book.jobProgress)
        assertFalse(book.jobMissing)
    }

    @Test
    fun `a poll writes progress, eta and the expected audiobook size`() = runTest {
        dao.insert(book("b1", jobId = "job-1", jobStatus = JobStatus.QUEUED))

        dao.updateJobState(
            id = "b1", status = JobStatus.RUNNING, progress = 32, eta = "00d 00h 00m 11s",
            chaptersDone = 2, error = null, startedAt = "2026-08-07T16:36:19+00:00",
            finishedAt = null, audiobookBytes = 543210987,
            audiobookRemoteName = null, syncRemoteName = null,
        )

        val book = dao.get("b1")!!
        assertEquals(JobStatus.RUNNING, book.jobStatus)
        assertEquals(32, book.jobProgress)
        assertEquals("00d 00h 00m 11s", book.jobEta)
        assertEquals(2, book.jobChaptersDone)
        assertEquals(543210987L, book.audiobookBytes)
    }

    @Test
    fun `a poll does not clobber columns another writer owns`() = runTest {
        // The real reason the DAO uses narrow UPDATEs: the poller, the reader and
        // the download worker all write this row concurrently.
        dao.insert(book("b1", jobId = "job-1", jobStatus = JobStatus.RUNNING))
        dao.updateReadingPosition("b1", locator = """{"href":"chap1.xhtml"}""", openedAt = 555)
        dao.updateDownload("b1", DownloadState.RUNNING, downloadedBytes = 1024, totalBytes = 4096, error = null)

        dao.updateJobState(
            id = "b1", status = JobStatus.DONE, progress = 100, eta = null, chaptersDone = 3,
            error = null, startedAt = "t0", finishedAt = "t1", audiobookBytes = 4096,
            audiobookRemoteName = "Book.m4b", syncRemoteName = "Book.json",
        )

        val book = dao.get("b1")!!
        assertEquals("""{"href":"chap1.xhtml"}""", book.readingLocator)
        assertEquals(555L, book.lastOpenedAt)
        assertEquals(DownloadState.RUNNING, book.downloadState)
        assertEquals(1024L, book.downloadedBytes)
    }

    @Test
    fun `a later poll does not erase filenames an earlier one learned`() = runTest {
        dao.insert(book("b1", jobId = "job-1", jobStatus = JobStatus.DONE))
        dao.updateJobState(
            id = "b1", status = JobStatus.DONE, progress = 100, eta = null, chaptersDone = 3,
            error = null, startedAt = "t0", finishedAt = "t1", audiobookBytes = 4096,
            audiobookRemoteName = "Book.m4b", syncRemoteName = "Book.json",
        )

        // A poll of a running job carries no filenames. Without COALESCE this
        // would blank them and the download would guess a name instead.
        dao.updateJobState(
            id = "b1", status = JobStatus.RUNNING, progress = 50, eta = null, chaptersDone = 1,
            error = null, startedAt = "t0", finishedAt = null, audiobookBytes = null,
            audiobookRemoteName = null, syncRemoteName = null,
        )

        val book = dao.get("b1")!!
        assertEquals("Book.m4b", book.audiobookRemoteName)
        assertEquals("Book.json", book.syncRemoteName)
    }

    @Test
    fun `awaitingConversion selects exactly the jobs worth polling`() = runTest {
        dao.insert(book("no-job", addedAt = 1))
        dao.insert(book("queued", addedAt = 2, jobId = "j2", jobStatus = JobStatus.QUEUED))
        dao.insert(book("running", addedAt = 3, jobId = "j3", jobStatus = JobStatus.RUNNING))
        dao.insert(book("done", addedAt = 4, jobId = "j4", jobStatus = JobStatus.DONE))
        dao.insert(book("failed", addedAt = 5, jobId = "j5", jobStatus = JobStatus.ERROR))
        dao.insert(book("lost", addedAt = 6, jobId = "j6", jobStatus = JobStatus.QUEUED, jobMissing = true))
        // A job whose status the app has not learned yet still needs a poll.
        dao.insert(book("unpolled", addedAt = 7, jobId = "j7", jobStatus = null))

        assertEquals(listOf("queued", "running", "unpolled"), dao.awaitingConversion().map { it.id })
    }

    @Test
    fun `an unknown status is still polled rather than treated as finished`() = runTest {
        // A future server could add a status this build has never heard of.
        dao.insert(book("b1", jobId = "j1", jobStatus = JobStatus.UNKNOWN))
        assertEquals(listOf("b1"), dao.awaitingConversion().map { it.id })
    }

    @Test
    fun `awaitingDownload resumes downloads already started, not ones nobody asked for`() = runTest {
        // Never tapped "Download" -- this is the card's job, not the poller's.
        dao.insert(book("never-asked", addedAt = 1, jobId = "j1", jobStatus = JobStatus.DONE))
        dao.insert(
            book(
                "already-fetched", addedAt = 2, jobId = "j2", jobStatus = JobStatus.DONE,
                downloadState = DownloadState.DONE,
            )
        )
        dao.insert(book("still-converting", addedAt = 3, jobId = "j3", jobStatus = JobStatus.RUNNING))
        // A download the user started must be picked up again if it was
        // interrupted or failed, not abandoned.
        dao.insert(
            book(
                "interrupted", addedAt = 4, jobId = "j4", jobStatus = JobStatus.DONE,
                downloadState = DownloadState.RUNNING,
            )
        )
        dao.insert(
            book(
                "download-failed", addedAt = 5, jobId = "j5", jobStatus = JobStatus.DONE,
                downloadState = DownloadState.FAILED,
            )
        )

        assertEquals(
            listOf("interrupted", "download-failed"),
            dao.awaitingDownload().map { it.id },
        )
    }

    @Test
    fun `marking a job missing stops it being polled`() = runTest {
        dao.insert(book("b1", jobId = "j1", jobStatus = JobStatus.QUEUED))
        assertEquals(1, dao.awaitingConversion().size)

        dao.markJobMissing("b1")

        assertTrue(dao.awaitingConversion().isEmpty())
        assertTrue(dao.get("b1")!!.needsReupload)
    }

    @Test
    fun `a cleaned-up job is not mistaken for a lost one`() = runTest {
        dao.insert(
            book(
                "b1", jobId = "j1", jobStatus = JobStatus.DONE, jobMissing = true,
                downloadState = DownloadState.DONE,
                audiobookPath = "/data/x.m4b", syncPath = "/data/x.json",
            )
        )
        val book = dao.get("b1")!!
        assertTrue(book.isPlayable)
        // jobMissing is set because the app deleted the job on purpose.
        assertFalse(book.needsReupload)
    }

    @Test
    fun `clearJob forgets the server side entirely`() = runTest {
        dao.insert(book("b1", jobId = "j1", jobStatus = JobStatus.ERROR))
        dao.updateUploadedBytes("b1", 999)

        dao.clearJob("b1")

        val book = dao.get("b1")!!
        assertNull(book.jobId)
        assertNull(book.jobStatus)
        assertEquals(0L, book.uploadedBytes)
        assertFalse(book.needsPolling)
    }

    @Test
    fun `a job id maps back to its book`() = runTest {
        dao.insert(book("b1", jobId = "job-abc"))
        assertEquals("b1", dao.findByJobId("job-abc")?.id)
        assertNull(dao.findByJobId("nope"))
    }

    @Test
    fun `download progress and completion are recorded`() = runTest {
        dao.insert(book("b1"))

        dao.updateDownload("b1", DownloadState.RUNNING, downloadedBytes = 500, totalBytes = 1000, error = null)
        assertEquals(DownloadState.RUNNING, dao.get("b1")!!.downloadState)
        assertEquals(500L, dao.get("b1")!!.downloadedBytes)

        dao.updateDownloadState("b1", DownloadState.FAILED, error = "connection reset")
        assertEquals("connection reset", dao.get("b1")!!.downloadError)
        // The byte counters survive a failure, which is what lets the next
        // attempt resume with a Range header.
        assertEquals(500L, dao.get("b1")!!.downloadedBytes)
    }

    @Test
    fun `observing one book emits its updates`() = runTest {
        dao.insert(book("b1"))
        assertEquals("Book b1", dao.observe("b1").first()!!.title)

        dao.updateMetadata("b1", title = "Real Title", author = "A. Writer", coverPath = "/c.jpg")

        val book = dao.observe("b1").first()!!
        assertEquals("Real Title", book.title)
        assertEquals("A. Writer", book.author)
    }

    @Test
    fun `deleting a book removes it`() = runTest {
        dao.insert(book("b1"))
        dao.delete("b1")
        assertNull(dao.get("b1"))
    }
}
