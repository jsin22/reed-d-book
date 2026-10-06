package dev.reedd.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.guide.BookGuideStore
import dev.reedd.data.remote.GuideDto
import dev.reedd.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The open book's guide (recaps + characters): the copy saved on the phone
 * straight away, then the server's if it has more -- so it works offline, and
 * fills in as the server finishes building it.
 */
class GuideViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val store: BookGuideStore,
) : ViewModel() {

    private val _guide = MutableStateFlow<GuideDto?>(null)
    val guide: StateFlow<GuideDto?> = _guide.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    /** Called on open and each time the guide sheet is shown. */
    fun refresh() {
        viewModelScope.launch {
            _guide.value = _guide.value ?: store.load(bookId)
            val jobId = repository.get(bookId)?.jobId
            if (jobId != null) store.refresh(bookId, jobId)?.let { _guide.value = it }
            _loading.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer, bookId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GuideViewModel(bookId, container.repository, container.guides) as T
        }
    }
}
