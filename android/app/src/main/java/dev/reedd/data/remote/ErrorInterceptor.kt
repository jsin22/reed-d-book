package dev.reedd.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Turns every non-2xx response into an [ApiException] carrying FastAPI's `detail`.
 *
 * Doing this in an interceptor rather than per call site means the downloader
 * gets it too -- it uses the same [okhttp3.OkHttpClient] with a raw call, not
 * Retrofit, so a 409 ("job is not done") or 410 ("files were cleaned up") on
 * `/audiobook` arrives already classified.
 *
 * The body is read with `peekBody` and capped: an error body is a short JSON
 * object, but nothing guarantees that, and this must not pull a hundred
 * megabytes into memory because a proxy returned HTML.
 */
class ErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.isSuccessful) return response

        val body = runCatching { response.peekBody(MAX_ERROR_BODY).string() }.getOrNull()
        response.close()
        throw ApiException(
            code = response.code,
            detail = ApiException.parseDetail(body) ?: response.message.ifBlank { null },
            url = response.request.url.toString(),
        )
    }

    private companion object {
        const val MAX_ERROR_BODY = 64L * 1024
    }
}
