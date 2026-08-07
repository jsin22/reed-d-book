package dev.reedd.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <token>` when the server has `REEDD_API_TOKEN` set.
 *
 * The token is read through a lambda rather than captured, so changing it in
 * Settings takes effect on the next request instead of needing the HTTP stack
 * rebuilt. `/api/health` is exempt because the server exempts it -- that is what
 * lets the app check a host before it has valid credentials for it.
 */
class AuthInterceptor(private val token: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val value = token()?.takeIf { it.isNotBlank() }
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
