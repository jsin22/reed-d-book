package dev.reedd.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.InviteRequestDto
import dev.reedd.data.remote.InviteResultDto
import dev.reedd.data.remote.JobDto
import dev.reedd.data.remote.MetadataHealthDto
import dev.reedd.data.remote.PublicUpdateDto
import dev.reedd.data.remote.ServerNotConfigured
import dev.reedd.data.remote.UserDto
import dev.reedd.di.AppContainer
import dev.reedd.domain.ConversionWatcher
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
 * A thin wrapper over the admin endpoints under `/api/admin/` -- see
 * `server/README.md`, "Sharing with others". Mostly needs only [ApiProvider];
 * [watcher] exists solely for [recheckMetadata].
 */
class AdminViewModel(
    private val api: ApiProvider,
    private val watcher: ConversionWatcher,
) : ViewModel() {

    private val _jobs = MutableStateFlow<List<JobDto>>(emptyList())
    val jobs: StateFlow<List<JobDto>> = _jobs.asStateFlow()

    private val _users = MutableStateFlow<List<UserDto>>(emptyList())
    val users: StateFlow<List<UserDto>> = _users.asStateFlow()

    /**
     * Whether the category/genre lookup is currently working. Null until the
     * first refresh answers (or fails to reach the endpoint at all, which is
     * itself left as null rather than shown as broken -- this screen already
     * has [message] for "could not reach the server" generally, and
     * conflating the two would make an unrelated network blip look like the
     * lookup itself is unhealthy).
     */
    private val _metadataHealth = MutableStateFlow<MetadataHealthDto?>(null)
    val metadataHealth: StateFlow<MetadataHealthDto?> = _metadataHealth.asStateFlow()

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
            runCatching { api.service().metadataHealth() }.onSuccess { _metadataHealth.value = it }
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

    /**
     * Revokes a user's access -- `DELETE /api/admin/users/{id}`. Their token
     * stops working immediately; any jobs they own are left alone (see
     * `UserStore.delete`'s docstring server-side), same as this screen's own
     * job list already tolerates an `ownerEmail` that no longer resolves to
     * anyone. The server itself refuses to delete the caller's own account,
     * surfaced here as an ordinary error message rather than hidden client-side.
     */
    fun deleteUser(userId: String) {
        viewModelScope.launch {
            try {
                api.service().deleteUser(userId)
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

    /** True while [recheckMetadata] is running, so the button can show a
     *  spinner and not be tapped twice. */
    private val _rechecking = MutableStateFlow(false)
    val rechecking: StateFlow<Boolean> = _rechecking.asStateFlow()

    /**
     * "Re-check all book metadata": an explicit, immediate [ConversionWatcher.
     * reconcile] -- every book's category/genre (and everything else a
     * reconcile refreshes) is re-read from whatever the server currently has,
     * in one request, regardless of what this device already thought it knew.
     * This used to need a separate "wipe it all first" step, back when a
     * reconcile only ever re-checked a book that had *never* resolved
     * locally; it no longer does (see reconcile's own docstring for why), so
     * this button is now just a convenient, explicit trigger rather than a
     * special path -- the Library screen's own Refresh does the same thing.
     * Kept here anyway: "fix my data" and "check for updates" are different
     * asks even when the code behind them converged.
     */
    fun recheckMetadata() {
        viewModelScope.launch {
            _rechecking.value = true
            try {
                watcher.reconcile()
            } catch (e: IOException) {
                _message.value = e.message ?: "could not reach the server"
            } finally {
                _rechecking.value = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AdminViewModel(container.api, container.watcher) as T
        }
    }
}
