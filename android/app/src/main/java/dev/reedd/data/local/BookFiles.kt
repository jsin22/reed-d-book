package dev.reedd.data.local

import android.content.Context
import java.io.File

/**
 * Where a book's files live on the device.
 *
 * ```
 * filesDir/
 *   books/<bookId>/book.epub          the imported copy
 *   books/<bookId>/cover.jpg          extracted at import, if the epub has one
 *   audiobooks/<bookId>/<name>.m4b    downloaded
 *   audiobooks/<bookId>/<name>.json   downloaded, then parsed into sync_chunks
 *   audiobooks/<bookId>/<name>.part   in-flight download; renamed on completion
 * ```
 *
 * App-private internal storage, deliberately: the epub is copied here rather
 * than read through its original `content://` URI because a SAF permission does
 * not reliably outlive the picker, and a background worker may need the file
 * hours later. It also means uninstalling the app takes its files with it.
 */
class BookFiles(context: Context) {

    private val root: File = context.filesDir

    fun bookDir(bookId: String): File = File(root, "books/$bookId").apply { mkdirs() }

    fun epub(bookId: String): File = File(bookDir(bookId), "book.epub")

    fun cover(bookId: String): File = File(bookDir(bookId), "cover.jpg")

    fun audiobookDir(bookId: String): File = File(root, "audiobooks/$bookId").apply { mkdirs() }

    /**
     * Keeps the server's filename, which is what the sync file's `audio_file`
     * field refers to.
     */
    fun audiobook(bookId: String, filename: String): File =
        File(audiobookDir(bookId), sanitize(filename))

    /** The partial file a resumable download appends to. */
    fun partial(target: File): File = File(target.parentFile, "${target.name}.part")

    fun deleteAll(bookId: String) {
        bookDir(bookId).deleteRecursively()
        audiobookDir(bookId).deleteRecursively()
    }

    fun bytesOnDisk(bookId: String): Long =
        listOf(bookDir(bookId), audiobookDir(bookId))
            .flatMap { it.walkTopDown().filter(File::isFile) }
            .sumOf { it.length() }

    private companion object {
        /**
         * The filename comes from the server, so it is already sanitised there --
         * but it arrives over the network, and a `../` in it would write outside
         * the book's directory.
         */
        fun sanitize(name: String): String =
            File(name).name.replace(Regex("""[^A-Za-z0-9._-]+"""), "_").ifBlank { "audiobook" }
    }
}
