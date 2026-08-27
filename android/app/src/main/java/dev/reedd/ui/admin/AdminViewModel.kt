package dev.reedd.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.InviteRequestDto
import dev.reedd.data.remote.InviteResultDto
import dev.reedd.data.remote.JobDto
import dev.reedd.data.remote.PublicUpdateDto
import dev.reedd.data.remote.ServerNotConfigured
import dev.reedd.data.remote.UserDto
import dev.reedd.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The last invite's outcome, shown inline so the token can be hand-delivered
 * when the server has no SMTP configured (`email_sent == false`).
 */
sealed interface InviteResult {
    data object Idle : InviteResult
    data object Sending : InviteResult
    data class Sent(val result: InviteResultDto) : InviteResult
    data class Failed(val reason: String) : InviteResult
}

/**
 * A thin wrapper over the four admin endpoints under `/api/admin/` -- see
 * `server/README.md`, "Sharing with others". Needs only [ApiProvider], the
 * same object every other screen already has; no repository layer, since
 * there is nothing here to cache or reconcile against local state the way
 * [dev.reedd.data.BookRepository] does for the library.
 */
class AdminViewModel(private val api: ApiProvider) : ViewModel() {

    private val _jobs = MutableStateFlow<List<JobDto>>(emptyList())
    val jobs: StateFlow<List<JobDto>> = _jobs.asStateFlow()

    private val _users = MutableStateFlow<List<UserDto>>(emptyList())
    val users: StateFlow<List<UserDto>> = _users.asStateFlow()

    private val _inviteResult = MutableStateFlow<InviteResult>(InviteResult.Idle)
    val inviteResult: StateFlow<InviteResult> = _inviteResult.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { api.service().adminJobs().jobs }.onSuccess { _jobs.value = it }
            runCatching { api.service().adminUsers().users }.onSuccess { _users.value = it }
        }
    }

    fun togglePublic(jobId: String, public: Boolean) {
        viewModelScope.launch {
            try {
                api.service().setJobPublic(jobId, PublicUpdateDto(public))
                refresh()
            } catch (e: ApiException) {
                _message.value = e.detail ?: e.message
            } catch (e: IOException) {
                _message.value = e.message ?: "could not reach the server"
            }
        }
    }

    /**
     * Removes a job from the server entirely -- `DELETE /api/jobs/{id}`, the
     * same endpoint the owner's own device uses to delete a book, except an
     * admin is allowed to call it on *any* job, not just their own (see
     * `_owns_or_admin` in `server/app/main.py`). This deletes the job's whole
     * directory server-side: the epub, the audiobook, the sync file, all of
     * it -- there is no undo, and every device (owner's included) stops
     * seeing it on its next poll.
     */
    fun deleteJob(jobId: String) {
        viewModelScope.launch {
            try {
                api.service().deleteJob(jobId)
                refresh()
            } catch (e: ApiException) {
                _message.value = e.detail ?: e.message
            } catch (e: IOException) {
                _message.value = e.message ?: "could not reach the server"
            }
        }
    }

    fun invite(email: String) {
        if (email.isBlank()) return
        viewModelScope.launch {
            _inviteResult.value = InviteResult.Sending
            _inviteResult.value = try {
                val result = api.service().inviteUser(InviteRequestDto(email.trim()))
                refresh()
                InviteResult.Sent(result)
            } catch (e: ServerNotConfigured) {
                InviteResult.Failed("No server address set.")
            } catch (e: ApiException) {
                InviteResult.Failed(e.detail ?: "the server returned ${e.code}")
            } catch (e: IOException) {
                InviteResult.Failed(e.message ?: "could not reach the server")
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun dismissInviteResult() {
        _inviteResult.value = InviteResult.Idle
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AdminViewModel(container.api) as T
        }
    }
}
