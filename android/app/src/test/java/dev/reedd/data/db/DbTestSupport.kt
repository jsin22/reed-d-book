package dev.reedd.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.reedd.data.remote.JobStatus

fun inMemoryDb(): ReeddDatabase =
    Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        ReeddDatabase::class.java,
    ).allowMainThreadQueries().build()

fun book(
    id: String,
    title: String = "Book $id",
    addedAt: Long = 1_000,
    jobId: String? = null,
    jobStatus: JobStatus? = null,
    jobMissing: Boolean = false,
    downloadState: DownloadState = DownloadState.NONE,
    audiobookPath: String? = null,
    syncPath: String? = null,
    category: String? = null,
    genres: List<String> = emptyList(),
    autoDownload: Boolean = false,
): BookEntity = BookEntity(
    id = id,
    epubPath = "/data/books/$id/book.epub",
    originalFilename = "$title.epub",
    title = title,
    addedAt = addedAt,
    jobId = jobId,
    jobStatus = jobStatus,
    jobMissing = jobMissing,
    downloadState = downloadState,
    audiobookPath = audiobookPath,
    syncPath = syncPath,
    category = category,
    genres = genres,
    autoDownload = autoDownload,
)

fun chunk(bookId: String, ordinal: Int, startMs: Long, endMs: Long, text: String = "s$ordinal", chapter: Int = 1) =
    SyncChunkEntity(bookId = bookId, ordinal = ordinal, text = text, startMs = startMs, endMs = endMs, chapter = chapter)
