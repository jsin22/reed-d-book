package dev.reedd.diagnostics

import android.util.Log
import dev.reedd.data.remote.ApiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Delivers crash reports somewhere a human will see them.
 *
 * Two routes, because either can be unavailable at the moment it matters:
 *
 *  * [lastReport] is surfaced in the app on the next launch, which works with no
 *    server and no cable;
 *  * [upload] posts to the conversion server, which writes it to disk and logs it
 *    to the uvicorn console — useful when the phone is in one hand and the terminal
 *    is on screen.
 *
 * A report is only deleted once the server has accepted it, so a failed upload is
 * retried on the next launch rather than lost.
 */
class CrashLog(
    private val reports: () -> List<File>,
    private val api: ApiProvider,
    private val clear: () -> Unit,
) {
    private val _lastReport = MutableStateFlow<String?>(null)

    /** The most recent crash report, for display. Null when the last run was clean. */
    val lastReport: StateFlow<String?> = _lastReport.asStateFlow()

    /**
     * Load any pending reports and try to send them.
     *
     * Best effort throughout: this runs during app start, and a diagnostics
     * feature that can itself break startup would be worse than no diagnostics.
     */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val pending = runCatching { reports() }.getOrDefault(emptyList())
            if (pending.isEmpty()) return@launch

            _lastReport.value = runCatching { pending.last().readText() }.getOrNull()
            Log.w(TAG, "${pending.size} crash report(s) from a previous run")

            val allSent = pending.all { file -> upload(file) }
            if (allSent) runCatching { clear() }
        }
    }

    /** @return true if the server accepted it. */
    private suspend fun upload(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val text = file.readText()
            api.service().reportCrash(text.toRequestBody(TEXT_PLAIN))
            Log.i(TAG, "crash report ${file.name} sent to the server")
            true
        }.getOrElse {
            // No server configured, or it is unreachable. The file stays put.
            Log.i(TAG, "could not send ${file.name}: ${it.message}")
            false
        }
    }

    /**
     * Close the on-screen banner. Deliberately does *not* delete the
     * underlying report file: [start] only calls [clear] once every pending
     * report has actually been confirmed sent (`allSent`), and dismissing
     * the banner is not the same event -- a slow or briefly-offline upload
     * can easily still be in flight, or have already failed, at the moment
     * someone taps past it. A dismissed-but-undelivered report is retried
     * on the next launch instead of being silently lost; this is the fix
     * for a real, confirmed case of exactly that (a new user's crash that
     * was shown on-device but never reached the server -- see the Margin
     * Notes follow-up, 2026-09-04).
     */
    fun dismiss() {
        _lastReport.value = null
    }

    private companion object {
        const val TAG = "ReeddCrash"
        val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
    }
}
