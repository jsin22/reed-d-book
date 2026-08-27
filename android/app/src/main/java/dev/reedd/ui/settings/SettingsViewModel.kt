package dev.reedd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.local.BookFiles
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.MeDto
import dev.reedd.data.remote.ServerAddress
import dev.reedd.data.remote.ServerNotConfigured
import dev.reedd.data.settings.ServerSettings
import dev.reedd.data.settings.SettingsStore
import dev.reedd.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/** Result of the non-admin Save button's own `GET /api/me` check -- distinct
 *  from [ConnectionCheck], which is admin-only UI built around `/api/health`
 *  data a non-admin never fetches. Without this, a bad token silently saved
 *  and showed nothing until the user left Settings and saw the library's own
 *  banner (LibraryViewModel.authStatus) -- the same class of silent failure
 *  BUG-23 was about, just one screen over. */
sealed interface TokenSaveResult {
    data object Idle : TokenSaveResult
    data object Saving : TokenSaveResult
    data object Ok : TokenSaveResult
    data class Failed(val reason: String) : TokenSaveResult
}

sealed interface ConnectionCheck {
    data object Idle : ConnectionCheck
    data object Checking : ConnectionCheck
    data class Reachable(val dataDir: String?, val broker: String?, val loggedInAs: String) : ConnectionCheck
    /** The address is right (health answered), but the token was rejected. Its own
     *  case rather than folded into [Unreachable] -- see BUGS.md BUG-23, where a
     *  bad token silently looked identical to "everything is fine" because
     *  /api/health never checks it. */
    data class InvalidToken(val dataDir: String?, val broker: String?) : ConnectionCheck
    data class Unreachable(val reason: String) : ConnectionCheck
}

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val api: ApiProvider,
    private val repository: BookRepository,
    private val files: BookFiles,
) : ViewModel() {

    val settings: StateFlow<ServerSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ServerSettings())

    private val _check = MutableStateFlow<ConnectionCheck>(ConnectionCheck.Idle)
    val check: StateFlow<ConnectionCheck> = _check.asStateFlow()

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    /** Who the configured token belongs to, for "logged in as" and the Admin
     *  entry. Null while loading or if the server/token isn't set up yet --
     *  those are not errors worth a snackbar here, just "nothing to show". */
    private val _me = MutableStateFlow<MeDto?>(null)
    val me: StateFlow<MeDto?> = _me.asStateFlow()

    init {
        refreshStorage()
        refreshMe()
    }

    fun refreshMe() {
        viewModelScope.launch {
            val result = runCatching { api.service().me() }.getOrNull()
            _me.value = result
            // A non-admin must have zero impact on the backend beyond their own
            // upload -- see SettingsScreen, which hides this toggle from them
            // entirely -- but the stored preference is per-device and outlives
            // any single check, so a value left on from before this
            // restriction (or from when this device's token was an admin's)
            // is force-cleared the moment a non-admin token is confirmed,
            // not just hidden from view.
            if (result != null && !result.isAdmin && settingsStore.current().deleteJobAfterDownload) {
                settingsStore.setDeleteJobAfterDownload(false)
            }
        }
    }

    fun save(baseUrl: String, token: String) {
        viewModelScope.launch {
            settingsStore.setServer(baseUrl, token)
            _check.value = ConnectionCheck.Idle
        }
        refreshMe()
    }

    private val _tokenSaveResult = MutableStateFlow<TokenSaveResult>(TokenSaveResult.Idle)
    val tokenSaveResult: StateFlow<TokenSaveResult> = _tokenSaveResult.asStateFlow()

    /**
     * The non-admin Save button: the address field is not shown to them (it
     * is baked into the app -- see SettingsStore.DEFAULT_BASE_URL), so only
     * the token changes; whatever address is already configured is left
     * alone. Unlike [refreshMe] (which swallows failures -- it runs
     * passively, e.g. on screen open, and has nowhere good to put an error),
     * this is a direct response to something the user just typed and saved,
     * so a bad token has to say so right here, not just fail silently and
     * wait for the library screen's own banner to eventually notice.
     */
    fun saveToken(token: String) {
        viewModelScope.launch {
            settingsStore.setServer(settingsStore.current().baseUrl, token)
            _tokenSaveResult.value = TokenSaveResult.Saving
            _tokenSaveResult.value = try {
                val me = api.service().me()
                _me.value = me
                if (!me.isAdmin && settingsStore.current().deleteJobAfterDownload) {
                    settingsStore.setDeleteJobAfterDownload(false)
                }
                TokenSaveResult.Ok
            } catch (e: ApiException) {
                _me.value = null
                TokenSaveResult.Failed(
                    if (e.isUnauthorized) "That token was not accepted. Check it for typos and try again."
                    else e.detail ?: "The server returned ${e.code}."
                )
            } catch (e: ServerNotConfigured) {
                _me.value = null
                TokenSaveResult.Failed("No server address set.")
            } catch (e: IOException) {
                _me.value = null
                TokenSaveResult.Failed(e.message ?: "Could not reach the server.")
            }
        }
    }

    fun setDeleteJobAfterDownload(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setDeleteJobAfterDownload(enabled) }
    }

    /**
     * Hits `GET /api/health` first, then `GET /api/me`.
     *
     * `/api/health` never requires the token, so a success there means "this
     * really is the conversion server" even when the token is wrong -- which is
     * what separates a typo in the address from a typo in the token. But it says
     * nothing about the token *itself*: every other endpoint requires a valid
     * per-user one now (see server/README.md, "Sharing with others"), so a bad
     * token still needs its own explicit check, or "server reached" reads as
     * "everything is fine" when the app actually cannot do anything useful yet
     * -- exactly what made BUG-23 (BUGS.md) so hard to diagnose from the user's
     * side: nothing in this screen ever said the token itself was the problem.
     */
    fun testConnection(baseUrl: String, token: String) {
        viewModelScope.launch {
            if (!ServerAddress.isValid(baseUrl)) {
                _check.value = ConnectionCheck.Unreachable("That is not a usable address.")
                return@launch
            }
            settingsStore.setServer(baseUrl, token)
            _check.value = ConnectionCheck.Checking
            _check.value = try {
                val health = api.service().health()
                if (health.status != "ok") {
                    ConnectionCheck.Unreachable("The server answered '${health.status}'.")
                } else {
                    try {
                        val me = api.service().me()
                        _me.value = me
                        ConnectionCheck.Reachable(health.dataDir, health.broker, me.email)
                    } catch (e: ApiException) {
                        if (e.isUnauthorized) {
                            _me.value = null
                            ConnectionCheck.InvalidToken(health.dataDir, health.broker)
                        } else {
                            throw e
                        }
                    }
                }
            } catch (e: ServerNotConfigured) {
                ConnectionCheck.Unreachable("No address set.")
            } catch (e: ApiException) {
                ConnectionCheck.Unreachable(e.detail ?: "The server returned ${e.code}.")
            } catch (e: IOException) {
                ConnectionCheck.Unreachable(e.message ?: "Could not reach that address.")
            }
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            _storageBytes.value = repository.allBooks().sumOf { files.bytesOnDisk(it.id) }
        }
    }

    fun normalizedPreview(baseUrl: String): String? = ServerAddress.normalize(baseUrl)

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                container.settings,
                container.api,
                container.repository,
                container.files,
            ) as T
        }
    }
}
