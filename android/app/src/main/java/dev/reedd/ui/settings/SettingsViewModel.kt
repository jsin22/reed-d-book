package dev.reedd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.local.BookFiles
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
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

sealed interface ConnectionCheck {
    data object Idle : ConnectionCheck
    data object Checking : ConnectionCheck
    data class Reachable(val dataDir: String?, val broker: String?) : ConnectionCheck
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

    init {
        refreshStorage()
    }

    fun save(baseUrl: String, token: String) {
        viewModelScope.launch {
            settingsStore.setServer(baseUrl, token)
            _check.value = ConnectionCheck.Idle
        }
    }

    fun setDeleteJobAfterDownload(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setDeleteJobAfterDownload(enabled) }
    }

    /**
     * Hits `GET /api/health`.
     *
     * That endpoint never requires the token, so a success here means "this really
     * is the conversion server" even when the token is wrong -- which is what makes
     * it useful for separating a typo in the address from a typo in the token.
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
                if (health.status == "ok") {
                    ConnectionCheck.Reachable(health.dataDir, health.broker)
                } else {
                    ConnectionCheck.Unreachable("The server answered '${health.status}'.")
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
