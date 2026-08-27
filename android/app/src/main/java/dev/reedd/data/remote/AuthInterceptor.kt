package dev.reedd.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <token>` when a per-user token is configured.
 *
 * The token is read through a lambda rather than captured, so changing it in
 * Settings takes effect on the next request instead of needing the HTTP stack
 * rebuilt. `/api/health` is exempt because the server exempts it -- that is what
 * lets the app check a host before it has valid credentials for it.
 */
class AuthInterceptor(private val token: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Defensively stripped here too, not just where it is saved
        // (SettingsStore.setServer): a header value may not contain a raw
        // control character, and OkHttp throws IllegalArgumentException on an
        // uncaught background thread if it does -- crashing the whole app on
        // every request, not just failing this one. Filtering here also
        // self-heals a token that was already saved bad before this fix
        // shipped, with no need to re-enter it in Settings.
        val value = token()?.filterNot { it.isWhitespace() }?.takeIf { it.isNotBlank() }
        if (value == null || request.url.encodedPath.endsWith("/api/health")) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $value")
                .build()
        )
    }
}
