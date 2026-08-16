package dev.reedd.data.remote

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Raised when the user has not told the app where the server is yet. */
class ServerNotConfigured : IOException("no server address configured")

/**
 * Builds and caches the HTTP stack for the currently-configured server.
 *
 * The base URL is a runtime setting, but Retrofit bakes it in at construction,
 * so the Retrofit instance is rebuilt whenever the address changes and cached
 * while it doesn't. The token needs no rebuild -- [AuthInterceptor] reads it per
 * request.
 *
 * The [OkHttpClient] is shared with the downloader on purpose: one connection
 * pool, one set of sockets to the same host.
 */
class ApiProvider(
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val debugLogging: Boolean = false,
) {
    private val json = Json {
        ignoreUnknownKeys = true      // tolerate server-side fields this build predates
        explicitNulls = false
        coerceInputValues = true
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        // Outermost, so it sees the final response after any redirect or retry.
        .addInterceptor(ErrorInterceptor())
        .addInterceptor(AuthInterceptor(token))
        .apply {
            if (debugLogging) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // No write or call timeout: uploading a few hundred megabytes over wifi
        // is expected to take minutes, and a timeout here would abort a healthy
        // transfer.
        .writeTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var cache: Pair<String, ReeddService>? = null

    /** @throws ServerNotConfigured if Settings has no usable address. */
    fun service(): ReeddService {
        val normalized = ServerAddress.normalize(baseUrl()) ?: throw ServerNotConfigured()
        cache?.let { (url, service) -> if (url == normalized) return service }
        val service = try {
            Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttp)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ReeddService::class.java)
        } catch (e: IllegalArgumentException) {
            // Retrofit is stricter about base URLs than HttpUrl is, and it signals
            // that with IllegalArgumentException -- which is not an IOException, so
            // every caller's `catch (IOException)` would miss it and the app would
            // die on a typo in Settings. Reported as "not configured", because from
            // the user's point of view that is exactly what it is.
            throw ServerNotConfigured()
        }
        cache = normalized to service
        return service
    }

    /** Absolute URL for a path such as `api/jobs/<id>/audiobook`, for the downloader. */
    fun url(path: String): HttpUrl {
        val normalized = ServerAddress.normalize(baseUrl()) ?: throw ServerNotConfigured()
        return (normalized + path.removePrefix("/")).toHttpUrlOrNull() ?: throw ServerNotConfigured()
    }

    fun currentToken(): String? = token()?.takeIf { it.isNotBlank() }
}
