package dev.reedd.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.remote.ApiProvider
import dev.reedd.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

class BookDetailViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val api: ApiProvider,
) : ViewModel() {

    val book: StateFlow<BookEntity?> =
        repository.book(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _log = MutableStateFlow<String?>(null)
    val log: StateFlow<String?> = _log.asStateFlow()

    private val _loadingLog = MutableStateFlow(false)
    val loadingLog: StateFlow<Boolean> = _loadingLog.asStateFlow()

    /**
     * Fetches `GET /api/jobs/{id}/log`: audiblez' own output, ffmpeg included.
     *
     * Worth surfacing rather than hiding behind a generic failure message -- when a
     * conversion dies it is nearly always something in here (a missing espeak
     * library, ffmpeg absent, a chapter audiblez could not parse).
     */
    fun loadLog() {
        val jobId = book.value?.jobId ?: run {
            _log.value = "This book has no job on the server."
            return
        }
        viewModelScope.launch {
            _loadingLog.value = true
            _log.value = try {
                api.service().log(jobId).string().takeLast(MAX_LOG_CHARS)
            } catch (e: IOException) {
                "Could not fetch the log: ${e.message}"
            } finally {
                _loadingLog.value = false
            }
        }
    }

    companion object {
        /** Enough for the tail that matters; a novel's log can be megabytes. */
        private const val MAX_LOG_CHARS = 20_000

        fun factory(container: AppContainer, bookId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BookDetailViewModel(bookId, container.repository, container.api) as T
        }
    }
}
