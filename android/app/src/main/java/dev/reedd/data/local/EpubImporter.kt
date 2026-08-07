package dev.reedd.data.local

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import dev.reedd.data.db.BookEntity
import dev.reedd.data.readium.ReadiumComponents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.cover
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** The picked file is not an epub, or is unreadable. */
class ImportError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a file the user picked into a row in the library.
 *
 * Three steps, in this order for a reason:
 *
 *  1. **Copy first.** A `content://` URI from the picker is only valid for as
 *     long as the permission grant lasts, so nothing else can depend on it.
 *  2. **Validate cheaply.** An epub is a zip; checking the four magic bytes
 *     rejects a mis-picked PDF before Readium spins up a parser. This mirrors
 *     the server's own `looks_like_epub` check, so the app fails at import
 *     rather than after a pointless upload.
 *  3. **Read metadata.** Title, author and cover come from Readium, which is
 *     also what will render the book, so the library and the reader can never
 *     disagree about what the book is called.
 */
class EpubImporter(
    context: Context,
    private val files: BookFiles,
    private val readium: ReadiumComponents,
) {
    private val appContext = context.applicationContext

    suspend fun import(uri: Uri): BookEntity = withContext(Dispatchers.IO) {
        val bookId = UUID.randomUUID().toString()
        val displayName = queryDisplayName(uri) ?: "book.epub"
        if (!displayName.lowercase().endsWith(".epub")) {
            throw ImportError("$displayName is not an .epub")
        }

        val target = files.epub(bookId)
        try {
            copy(uri, target)
            if (!looksLikeZip(target)) {
                throw ImportError("$displayName is not a valid epub (not a zip archive)")
            }
            val details = readDetails(bookId, target)
            BookEntity(
                id = bookId,
                epubPath = target.absolutePath,
                originalFilename = displayName,
                title = details.title ?: displayName.removeSuffix(".epub"),
                author = details.author,
                coverPath = details.coverPath,
                sizeBytes = target.length(),
            )
        } catch (e: Throwable) {
            // Never leave a half-copied book behind for the library to show.
            files.deleteAll(bookId)
            throw if (e is ImportError) e else ImportError("could not import $displayName", e)
        }
    }

    private fun copy(uri: Uri, target: File) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw ImportError("cannot open the selected file")
        // Streamed in chunks: a large book must not be held in memory whole.
        input.use { source -> FileOutputStream(target).use { source.copyTo(it, DEFAULT_BUFFER_SIZE) } }
    }

    private fun queryDisplayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
            ?: uri.lastPathSegment?.substringAfterLast('/')

    private suspend fun readDetails(bookId: String, epub: File): Details {
        val publication = readium.open(epub).getOrElse { cause ->
            throw ImportError(cause.message ?: "unreadable epub", cause)
        }
        return try {
            Details(
                title = publication.metadata.title?.takeIf { it.isNotBlank() },
                author = publication.metadata.authors.firstOrNull()?.name?.takeIf { it.isNotBlank() },
                coverPath = publication.cover()?.let { writeCover(bookId, it) },
            )
        } finally {
            publication.close()
        }
    }

    private fun writeCover(bookId: String, bitmap: Bitmap): String? = runCatching {
        val file = files.cover(bookId)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    }.getOrNull()

    private fun looksLikeZip(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(4)
            stream.read(header) == 4 &&
                header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        }
    }.getOrDefault(false)

    private data class Details(val title: String?, val author: String?, val coverPath: String?)
}
