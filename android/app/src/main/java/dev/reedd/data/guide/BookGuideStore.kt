package dev.reedd.data.guide

import dev.reedd.data.local.BookFiles
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.GuideDto
import dev.reedd.domain.BookGuideView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The book guide on this phone: fetched from the server alongside the
 * audiobook (and again from the reader while incomplete), kept as a file so the
 * Story so far and Characters screens work offline.
 */
class BookGuideStore(private val files: BookFiles, private val api: ApiProvider) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The saved guide, or null if there is none (or it cannot be read). */
    suspend fun load(bookId: String): GuideDto? = withContext(Dispatchers.IO) {
        val file = files.guide(bookId)
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString(GuideDto.serializer(), file.readText()) }.getOrNull()
    }

    /**
     * Fetches and saves the server's guide if it has more than the saved copy.
     * Never throws: no network, no guide yet (404) or a server error all just
     * leave whatever is saved, which is what offline reading needs.
     *
     * @return the newest guide available, saved or fetched.
     */
    suspend fun refresh(bookId: String, jobId: String): GuideDto? {
        val saved = load(bookId)
        if (saved != null && BookGuideView.isComplete(saved)) return saved
        val fetched = runCatching { api.service().guide(jobId) }.getOrNull() ?: return saved
        withContext(Dispatchers.IO) {
            val file = files.guide(bookId)
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(GuideDto.serializer(), fetched))
            tmp.renameTo(file)
        }
        return fetched
    }
}
