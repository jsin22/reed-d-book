package dev.reedd.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import dev.reedd.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
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
            appendLine("reed-d-book crash report")
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
