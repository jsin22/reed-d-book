package dev.reedd.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * An HTTP error from the conversion server, with FastAPI's `detail` unwrapped.
 *
 * An [IOException] on purpose. That is what an OkHttp interceptor is allowed to
 * throw, which lets [ErrorInterceptor] translate every non-2xx response in one
 * place instead of each call site remembering to wrap the call -- and callers
 * already have to catch `IOException` for the network dropping out, so there is
 * no second failure path to handle.
 *
 * The status code is load-bearing, not decoration:
 *
 *  * **404** on a poll means the server no longer knows the job (its directory
 *    was deleted, or the data dir was wiped). The app marks the book stale and
 *    offers to re-upload; it must not keep polling forever.
 *  * **409** on a download means the job exists but isn't `done` yet.
 *  * **410** means it finished but the files have since been cleaned up.
 *  * **401** means the API token is wrong.
 */
class ApiException(
    val code: Int,
    val detail: String?,
    val url: String? = null,
) : IOException(buildString {
    append("HTTP ").append(code)
    if (detail != null) append(": ").append(detail)
    if (url != null) append(" (").append(url).append(')')
}) {
    val isNotFound: Boolean get() = code == 404
    val isUnauthorized: Boolean get() = code == 401
    /** Job not finished yet. */
    val isNotReady: Boolean get() = code == 409
    /** Job finished, but the output files are gone. */
    val isGone: Boolean get() = code == 410

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Pull `detail` out of a FastAPI error body.
         *
         * It is a string for the handlers in `server/app/main.py`, but a list of
         * objects for request-validation failures, so both shapes are handled.
         * Anything unparseable falls back to the raw body.
         */
        fun parseDetail(body: String?): String? {
            if (body.isNullOrBlank()) return null
            return runCatching {
                when (val detail = lenient.parseToJsonElement(body).jsonObject["detail"]) {
                    is JsonPrimitive -> detail.content
                    is JsonArray -> detail.mapNotNull { item ->
                        (item as? JsonObject)?.get("msg")?.let { (it as? JsonPrimitive)?.content }
                    }.joinToString("; ").ifBlank { detail.toString() }
                    null -> null
                    else -> detail.toString()
                }
            }.getOrNull() ?: body.take(300)
        }
    }
}
