package dev.reedd.domain

import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.ServerNotConfigured
import dev.reedd.data.settings.SettingsStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** Whether this device can currently talk to the server as someone -- distinct
 *  from [dev.reedd.ui.settings.ConnectionCheck], which only runs when the user
 *  is on the Settings screen actively testing. */
sealed interface AuthStatus {
    /** Not checked yet, or nothing to report -- shows nothing. */
    data object Unknown : AuthStatus
    data object Ok : AuthStatus
    /** 401, or no token/server configured at all: same actionable fix either way. */
    data object NeedsToken : AuthStatus
    data class Unreachable(val reason: String) : AuthStatus
}

/**
 * Whether this device can talk to the server as someone, checked with
 * `GET /api/me` -- a single source of truth shared by every screen that
 * cares (the library's banner, the admin gate), rather than each ViewModel
 * keeping its own copy.
 *
 * This used to live only inside LibraryViewModel, which meant
 * SettingsViewModel.saveToken() had no way to tell it a token had just been
 * saved and confirmed working -- the library kept showing "No valid API
 * token" until its own next reconcile or a manual Refresh tap, even though
 * Settings had already made (and answered) the exact same check moments
 * earlier. [setOk]/[setNeedsToken]/[setUnreachable] let a caller that
 * already knows the answer -- because it just made the call itself --
 * publish it directly instead of forcing a second, redundant round trip.
 */
class AuthStatusMonitor(
    private val settingsStore: SettingsStore,
    private val api: ApiProvider,
    private val checkTimeoutMs: Long = CHECK_TIMEOUT_MS,
) {
    private val _status = MutableStateFlow<AuthStatus>(AuthStatus.Unknown)
    val status: StateFlow<AuthStatus> = _status.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    /** A direct `GET /api/me`, independent of whatever a caller's own reconcile
     *  does with jobs, so a bad token is reported even when there is nothing
     *  yet to poll or adopt. */
    suspend fun check() {
        // Settled from DataStore directly, not ApiProvider's cached snapshot:
        // that cache is filled by a background collector started in
        // SettingsStore's own init, which is not guaranteed to have delivered
        // its first value yet by the time this runs (this can fire from
        // LibraryViewModel's own init, essentially at process start). Racing
        // that meant a real, saved token could briefly -- and then, since
        // nothing re-triggered a check, permanently -- read back as "no
        // token" on a cold launch, showing AuthStatusBanner over a perfectly
        // valid setup.
        val settled = settingsStore.current()
        if (settled.token.isNullOrBlank()) {
            setNeedsToken()
            return
        }
        try {
            // OkHttpClient.connectTimeout does not reliably bound DNS
            // resolution on Android -- confirmed live: with Tailscale
            // connected at app launch, resolving this server's MagicDNS
            // name (`*.ts.net`, only resolvable through Tailscale's own
            // resolver) could hang well past it, apparently while that
            // resolver was still settling in right as the app's very first
            // request fired, leaving this stuck in AuthStatus.Unknown
            // forever with no error to show. withTimeout bounds it from the
            // coroutine side regardless of what the underlying blocking
            // call is actually stuck on: cancelling a suspending Retrofit
            // call this way does call the underlying OkHttp Call.cancel(),
            // which unblocks the caller even if a leaked native thread
            // doing the actual DNS lookup lingers a little longer in the
            // background -- an acceptable trade against hanging forever.
            val me = withTimeout(checkTimeoutMs) { api.service().me() }
            setOk(me.isAdmin)
        } catch (e: ApiException) {
            if (e.isUnauthorized) setNeedsToken() else setUnreachable(describe(e))
        } catch (e: ServerNotConfigured) {
            setNeedsToken()
        } catch (e: TimeoutCancellationException) {
            setUnreachable("timed out reaching the server")
        } catch (e: IOException) {
            setUnreachable(describe(e))
        }
    }

    fun setOk(isAdmin: Boolean) {
        _status.value = AuthStatus.Ok
        _isAdmin.value = isAdmin
    }

    fun setNeedsToken() {
        _status.value = AuthStatus.NeedsToken
        _isAdmin.value = false
    }

    fun setUnreachable(reason: String) {
        _status.value = AuthStatus.Unreachable(reason)
        _isAdmin.value = false
    }

    companion object {
        /** How long [check] waits before giving up and reporting
         *  [AuthStatus.Unreachable] instead of hanging indefinitely. */
        private const val CHECK_TIMEOUT_MS = 15_000L

        private fun describe(e: Throwable): String = when (e) {
            is ApiException -> e.detail ?: "the server returned ${e.code}"
            else -> e.message ?: "could not reach the server"
        }
    }
}
