package dev.reedd.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns what a user types in Settings into something Retrofit accepts.
 *
 * People type `192.168.1.101:8000`, `pocket4.local:8000`, `http://host:8000/`,
 * or paste a URL with a path on the end. Retrofit demands an absolute URL ending
 * in `/` and throws at construction time otherwise, which would be a crash on a
 * typo. Everything is funnelled through here instead.
 */
object ServerAddress {

    const val DEFAULT_PORT = 8000

    /**
     * @return a normalised base URL ending in `/`, or null if it cannot be one.
     */
    fun normalize(input: String?): String? {
        val raw = input?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // A bare host or host:port has no scheme; assume http, since the server
        // is a plain uvicorn on the LAN.
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        val url: HttpUrl = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        // Keep any path prefix (someone may put the server behind a reverse
        // proxy at /reedd), but it has to end in "/" for Retrofit to resolve
        // "api/jobs" against it rather than replacing the last segment.
        val normalized = url.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
        return if (normalized.endsWith("/")) normalized else "$normalized/"
    }

    fun isValid(input: String?): Boolean = normalize(input) != null
}
