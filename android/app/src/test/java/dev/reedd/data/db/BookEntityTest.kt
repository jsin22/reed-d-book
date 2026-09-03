package dev.reedd.data.db

import dev.reedd.data.align.ChunkAligner
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
