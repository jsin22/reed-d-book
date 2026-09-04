package dev.reedd.diagnostics

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A short, in-memory trail of what the reader was doing right before things
 * went wrong -- appearance changes, playback-speed changes, screen
 * open/close, backgrounding -- appended to [CrashReporter.report]'s own
 * output so a crash report says more than just where the stack trace was.
 *
 * Built because a real crash on a brand-new user's phone (see the Margin
 * Notes follow-up, 2026-09-04) turned out to have no stack trace to speak
 * of on the server -- the report never made it off the device -- leaving
 * only secondhand context from the user themselves ("reading, then toggled
 * scroll/page mode and text size, then locked the phone"). A crash report
 * that already carries that trail does not depend on the user remembering
 * or a developer being able to ask.
 *
 * In-memory only, capped, and never itself sent anywhere on its own --
 * [snapshot] is read into a crash report at the moment one is built, not
 * streamed live. Losing the trail on process death is fine: the report
 * that would have used it is written from the very same process, before
 * it dies.
 */
object Breadcrumbs {
    private const val MAX = 40

    private val lock = Any()
    private val trail = ArrayDeque<String>(MAX)
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun leave(message: String) {
        val line = "${format.format(java.util.Date())} $message"
        synchronized(lock) {
            if (trail.size >= MAX) trail.removeFirst()
            trail.addLast(line)
        }
    }

    /** Oldest first, the same order they happened in. */
    fun snapshot(): String =
        synchronized(lock) { trail.toList() }
            .joinToString("\n")
            .ifBlank { "(no breadcrumbs recorded)" }

    /** Reset for a fresh run -- called once at app start, so a report never
     *  mixes breadcrumbs left by a previous, already-reported process. */
    fun clear() {
        synchronized(lock) { trail.clear() }
    }
}
