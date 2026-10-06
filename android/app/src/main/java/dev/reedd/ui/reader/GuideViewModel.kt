package dev.reedd.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.guide.BookGuideStore
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.AskBodyDto
import dev.reedd.data.remote.AskPositionDto
import dev.reedd.data.remote.AskTurnDto
import dev.reedd.data.remote.GuideDto
import dev.reedd.domain.ReaderPoint
import dev.reedd.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The open book's guide (recaps + characters): the copy saved on the phone
 * straight away, then the server's if it has more -- so it works offline, and
 * fills in as the server finishes building it.
 */
class GuideViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val store: BookGuideStore,
    private val api: ApiProvider,
) : ViewModel() {

    /** One question and, once it arrives, its answer -- or why there is none. */
    data class ChatTurn(val question: String, val answer: String? = null, val error: String? = null) {
        val pending: Boolean get() = answer == null && error == null
    }

    /** This book's conversation, kept only while the book is open. */
    private val _chat = MutableStateFlow<List<ChatTurn>>(emptyList())
    val chat: StateFlow<List<ChatTurn>> = _chat.asStateFlow()

    /**
     * Ask about the book up to [point] -- the furthest place the reader has
     * reached. Answers come only from the text before it (server-side; see
     * `server/app/book_ask.py`). The last few answered turns go along so a
     * follow-up like "and his sister?" makes sense.
     */
    fun ask(question: String, point: ReaderPoint?) {
        val q = question.trim()
        if (q.isEmpty() || _chat.value.any { it.pending }) return
        val history = _chat.value.filter { it.answer != null }.takeLast(MAX_HISTORY)
            .map { AskTurnDto(it.question, it.answer!!) }
        _chat.value = _chat.value + ChatTurn(q)
        viewModelScope.launch {
            val jobId = repository.get(bookId)?.jobId
            val result: ChatTurn = when {
                jobId == null -> ChatTurn(q, error = "This book isn't on the server, so it can't be asked about.")
                point == null -> ChatTurn(q, error = "Couldn't tell where you are in the book yet. Try again once the page has loaded.")
                else -> try {
                    val position = AskPositionDto(point.resourceHref, point.anchorText, point.anchorOffset, point.fraction)
                    val answer = withTimeout(ASK_TIMEOUT_MS) { api.service().ask(jobId, AskBodyDto(q, position, history)) }
                    ChatTurn(q, answer = answer.answer)
                } catch (e: ApiException) {
                    ChatTurn(q, error = when (e.code) {
                        502 -> "The AI couldn't answer right now. Try again in a moment."
                        400 -> "Couldn't place where you are in the book."
                        else -> "The server couldn't answer (${e.code})."
                    })
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                    ChatTurn(q, error = "Couldn't reach the server. Asking needs a connection; Story so far and Characters work offline.")
                }
            }
            _chat.value = _chat.value.dropLast(1) + result
        }
    }

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
        /** Answered turns sent with a question, for follow-ups. */
        private const val MAX_HISTORY = 6
        /** A whole chapter of context can take the model a while. */
        private const val ASK_TIMEOUT_MS = 45_000L

        fun factory(container: AppContainer, bookId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GuideViewModel(bookId, container.repository, container.guides, container.api) as T
        }
    }
}
