package dev.reedd.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import dev.reedd.BuildConfig
import dev.reedd.data.remote.ServerAddress
import dev.reedd.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to disk so they can be read after the fact.
 *
 * Why this exists: when the app dies there is nothing left to show a stack trace
 * with, the emulator does not run on the machine this is developed on, and `adb
 * logcat` is awkward when the phone is being carried around. So the trace is
 * written to a file first, and then on the next launch it is both shown in the app
 * and posted to the conversion server, which logs it to the console.
 *
 * Uses `android.util.Log` rather than Timber (which Readium brings in, and which lint
 * suggests): a crash handler must not depend on a third-party logger having been
 * planted, least of all when the failure might be *in* start-up.
 *
 * The previous handler is always called afterwards. Swallowing the exception would
 * leave the app running in an unknown state, which is worse than crashing — the
 * goal here is to make the crash *legible*, not to prevent it.
 */
object CrashReporter {

    private const val TAG = "ReeddCrash"
    private const val DIR = "crashes"
    private const val MAX_KEPT = 20

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Guarded: a failure while recording the crash must not replace the
            // original one, or the real cause is lost.
            runCatching { write(appContext, thread, throwable) }
                .onFailure { Log.e(TAG, "could not record crash", it) }
            Log.e(TAG, "uncaught exception on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Reports not yet sent to the server, oldest first. */
    fun pending(context: Context): List<File> =
        directory(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Best-effort attempt to send any already-pending report(s) *before*
     * [dev.reedd.di.AppContainer] is built -- so a crash that happens during
     * the container's own construction, and so recurs on every launch, still
     * gets off the phone instead of being trapped forever behind the very
     * code path it broke. [CrashLog]'s normal send (wired after the
     * container exists) still runs afterwards and is what actually deletes
     * the file once the server confirms it -- this path only ever adds a
     * send, it never removes anything, so sending a file twice here and
     * again there is harmless (two identical log lines on the server).
     *
     * Deliberately minimal: plain [HttpURLConnection] rather than the app's
     * usual OkHttp/Retrofit stack, run on its own bare [Thread] rather than
     * a coroutine dispatcher, and reads the server address/token straight
     * out of [SettingsStore] -- as few moving parts as possible, since the
     * whole point is to still work when something else in the app does not.
     */
    fun sendPendingEarly(context: Context) {
        val files = pending(context)
        if (files.isEmpty()) return
        val appContext = context.applicationContext
        Thread({
            runCatching {
                val settings = SettingsStore(appContext, CoroutineScope(SupervisorJob()))
                val current = runBlocking { settings.current() }
                val base = ServerAddress.normalize(current.baseUrl) ?: return@runCatching
                val token = current.token?.filterNot { it.isWhitespace() }?.takeIf { it.isNotBlank() }
                for (file in files) {
                    runCatching { postPlain(base, token, file.readText()) }
                        .onFailure { Log.i(TAG, "early send of ${file.name} failed: ${it.message}") }
                }
            }.onFailure { Log.i(TAG, "early crash send skipped: ${it.message}") }
        }, "reedd-early-crash-send").start()
    }

    /**
     * Posts an arbitrary one-off diagnostic line to the same crash-report
     * endpoint, outside the uncaught-exception path entirely -- for logging
     * that needs to reach a developer with no `adb`/device access, not an
     * actual crash. The server does not distinguish; it is "the endpoint of
     * last resort for a client that has just died," but nothing about it
     * requires the client to actually be dying, and it already does exactly
     * what's needed here: written to disk *and* logged to the console as it
     * arrives. Fire-and-forget, same bare-`Thread`-plus-`HttpURLConnection`
     * shape as [sendPendingEarly] and for the same reason -- this must not
     * depend on whatever coroutine dispatcher happens to be live at the call
     * site, since the whole point is to still work if something nearby is
     * already broken.
     */
    fun reportDiagnostic(context: Context, tag: String, message: String) {
        val appContext = context.applicationContext
        Thread({
            runCatching {
                val settings = SettingsStore(appContext, CoroutineScope(SupervisorJob()))
                val current = runBlocking { settings.current() }
                val base = ServerAddress.normalize(current.baseUrl) ?: return@runCatching
                val token = current.token?.filterNot { it.isWhitespace() }?.takeIf { it.isNotBlank() }
                val body = buildString {
                    appendLine("read-d-book diagnostic ($tag)")
                    appendLine("when: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
                    appendLine()
                    append(message)
                }
                postPlain(base, token, body)
            }.onFailure { Log.i(TAG, "diagnostic send skipped: ${it.message}") }
        }, "reedd-diagnostic-send").start()
    }

    private fun postPlain(base: String, token: String?, body: String) {
        val connection = URL(base.trimEnd('/') + "/api/diagnostics/crash")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            Log.i(TAG, "early crash send: HTTP $code")
        } finally {
            connection.disconnect()
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = directory(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.txt")
        file.writeText(report(thread, throwable))
        prune(dir)
    }

    /**
     * The report. Deliberately includes the environment, because "crashes on my
     * phone but not in a test" is usually about the device or the build.
     */
    fun report(thread: Thread, throwable: Throwable): String {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
        return buildString {
            appendLine("read-d-book crash report")
            appendLine("when:      ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
            appendLine("app:       ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
            appendLine("device:    ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android:   ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("thread:    ${thread.name}")
            appendLine("exception: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine()
            append(trace.toString())
        }
    }

    /** A crash loop must not fill the phone's storage. */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.name } ?: return
        files.drop(MAX_KEPT).forEach { it.delete() }
    }

    private fun directory(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }
}
