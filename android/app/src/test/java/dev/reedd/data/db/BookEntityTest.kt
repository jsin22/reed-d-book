package dev.reedd.data.db

import dev.reedd.data.align.ChunkAligner
import dev.reedd.data.remote.UploadMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BookEntity.needsAlignment]'s alignmentVersion half.
 *
 * The alignedChunks == 0 half is already covered live by MigrationTest's
 * "an existing mapping is counted so it can be re-aligned lazily". This
 * covers the other, newer reason a book can need realignment: it was
 * aligned once, successfully, but under an older ChunkAligner than the one
 * installed now.
 */
class BookEntityTest {

    private fun playableBook(alignedChunks: Int, alignmentVersion: Int) = BookEntity(
        id = "b1",
        epubPath = "/tmp/b1.epub",
        originalFilename = "b1.epub",
        title = "A Real Book",
        audiobookPath = "/tmp/b1.m4b",
        syncPath = "/tmp/b1.json",
        downloadState = DownloadState.DONE,
        totalChunks = 10,
        alignedChunks = alignedChunks,
        alignmentVersion = alignmentVersion,
    )

    @Test
    fun `a book aligned under an older aligner version needs realignment even with a nonzero count`() {
        val book = playableBook(alignedChunks = 9, alignmentVersion = ChunkAligner.ALIGNMENT_VERSION - 1)
        assertTrue(book.needsAlignment)
    }

    @Test
    fun `a book aligned under the current aligner version does not need realignment again`() {
        val book = playableBook(alignedChunks = 9, alignmentVersion = ChunkAligner.ALIGNMENT_VERSION)
        assertFalse(book.needsAlignment)
    }

    @Test
    fun `a book never aligned needs alignment regardless of version`() {
        val book = playableBook(alignedChunks = 0, alignmentVersion = ChunkAligner.ALIGNMENT_VERSION)
        assertTrue(book.needsAlignment)
    }
}

/**
 * [BookEntity.canReadLive] -- not gated on [UploadMode] any more: the
 * server's own live-reading routes only ever need the job's epub, which every
 * job has regardless of what was requested at upload time. See this
 * memory's own doc / [BookEntity.canReadLive]'s doc for the real, reported
 * gap this fixes ("since conversion isn't needed to have live reading, it
 * should work for all existing books").
 */
class CanReadLiveTest {

    private fun book(jobId: String?, jobMissing: Boolean = false, uploadMode: UploadMode = UploadMode.OFFLINE) =
        BookEntity(
            id = "b1",
            epubPath = "/tmp/b1.epub",
            originalFilename = "b1.epub",
            title = "A Real Book",
            jobId = jobId,
            jobMissing = jobMissing,
            uploadMode = uploadMode,
        )

    @Test
    fun `a plain OFFLINE book with an uploaded job can read live`() {
        assertTrue(book(jobId = "job-1", uploadMode = UploadMode.OFFLINE).canReadLive)
    }

    @Test
    fun `a book never uploaded has no job to read live against`() {
        assertFalse(book(jobId = null).canReadLive)
    }

    @Test
    fun `a book whose job vanished server-side cannot read live either`() {
        assertFalse(book(jobId = "job-1", jobMissing = true).canReadLive)
    }
}
