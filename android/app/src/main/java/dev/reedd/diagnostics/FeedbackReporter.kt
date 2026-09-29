package dev.reedd.diagnostics

import android.os.Build
import dev.reedd.BuildConfig
import dev.reedd.data.remote.ApiProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sends a reader-written bug report or feature request to the server --
 * `POST /api/feedback`, a separate endpoint from [CrashReporter]'s own
 * `/api/diagnostics/crash` since this is submitted on purpose, not left
 * behind by a crash, and gets its own admin-only listing rather than
 * being mixed into general diagnostics.
 *
 * Uses the app's normal Retrofit/OkHttp stack, unlike [CrashReporter]'s
 * bare-`Thread`-plus-`HttpURLConnection` escape hatch -- that exists
 * specifically because a crash handler cannot assume anything else in the
 * app still works. A reader filling out a feedback form has no such
 * constraint, so the normal stack (a real suspend function, a real error
 * back to the dialog if it fails) is the better fit here.
 */
object FeedbackReporter {

    enum class Type(val wireValue: String, val label: String) {
        BUG("bug", "Bug"),
        FEATURE("feature", "Feature request"),
        OTHER("other", "Other"),
    }

    private val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()

    /**
     * @param bookContext e.g. "Five Survive (live mode)" -- attached
     *   automatically when feedback is opened from inside a book, so the
     *   reader never has to type which book or mode they were in. Null
     *   from the general Settings screen, where no book is open.
     *   [Breadcrumbs.snapshot] is attached the same way, for the same
     *   reason: a recent trail of what the app was doing is worth more
     *   than asking the reader to describe it themselves.
     * @throws Exception whatever the network call itself throws -- the
     *   caller (each screen's own ViewModel) decides how to surface that,
     *   the same way every other API call in this app does.
     */
    suspend fun submit(api: ApiProvider, type: Type, message: String, bookContext: String? = null) {
        val body = buildString {
            appendLine("read-d-book feedback")
            appendLine("type:      ${type.label}")
            appendLine("when:      ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
            appendLine("app:       ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
            appendLine("device:    ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android:   ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (bookContext != null) appendLine("book:      $bookContext")
            appendLine()
            appendLine(message)
            appendLine()
            appendLine("breadcrumbs:")
            append(Breadcrumbs.snapshot())
        }
        api.service().submitFeedback(type.wireValue, body.toRequestBody(TEXT_PLAIN))
    }
}
