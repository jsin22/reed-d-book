package dev.reedd.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Where the server lives and what defaults to convert with.
 *
 * @param baseUrl raw, as typed by the user; run it through
 *   [dev.reedd.data.remote.ServerAddress] before use.
 * @param token matches the server's `REEDD_API_TOKEN`; blank when it has none.
 * @param deleteJobAfterDownload off by default: a finished job left on the
 *   server is what lets [dev.reedd.domain.ServerLibraryAdopter] pull the same
 *   book onto another device -- or this one again, after a reinstall -- without
 *   re-uploading or waiting through TTS a second time. Turning it on trades that
 *   away for reclaiming the server's disk as each book finishes downloading; see
 *   `server/README.md`, "Accumulating a library".
 */
data class ServerSettings(
    val baseUrl: String? = null,
    val token: String? = null,
    val voice: String? = null,
    val speed: Double = 1.0,
    /** Null defers to the server's own default engine (see `GET /api/engines`). */
    val engine: String? = null,
    val deleteJobAfterDownload: Boolean = false,
) {
    companion object {
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 2.0

        /**
         * Where the app looks for the server when nothing has been configured.
         *
         * The server's Tailscale Funnel URL (see `server/README.md`, "Sharing with
         * others"), deliberately: it resolves and connects from any network, not
         * just this Tailscale tailnet, which is what lets an invited user's app
         * find the server with nothing but a token -- no address to type or be
         * told. It is only a *default* — Settings still overrides it, and clearing
         * the field falls back here rather than to nothing, so a reinstall does not
         * mean retyping an address. (Before Funnel, this pointed at the bare
         * Tailscale IP, `100.98.16.59:8000` -- LAN/tailnet-only, which is why
         * sharing the app previously required the other person to join the
         * tailnet.)
         */
        const val DEFAULT_BASE_URL = "https://gpd-server.tail6bd1d4.ts.net"
    }
}

/**
 * How the reader renders. Separate from [ServerSettings] because it changes for a
 * different reason -- a reading preference, not a connection detail.
 *
 * @param fontSize a scale factor, where 1.0 is the publication's own size.
 *   Defaults to the second-smallest of the reader's 9 slider steps (0.5,
 *   0.75, 1.0, ... 2.5 -- MIN_FONT_SIZE..MAX_FONT_SIZE by 0.25, matching the
 *   `steps = 7` slider in ReaderScreen.kt).
 * @param theme one of Readium's `LIGHT`, `DARK`, `SEPIA`, or null to follow the
 *   system. Defaults to [PAPER].
 */
data class ReaderSettings(
    val fontSize: Double = 0.75,
    val theme: String? = PAPER,
    val scroll: Boolean = false,
) {
    companion object {
        const val MIN_FONT_SIZE = 0.5
        const val MAX_FONT_SIZE = 2.5

        /**
         * An e-ink look: a warm grey page with near-black text.
         *
         * Not one of Readium's own themes (`LIGHT`, `DARK`, `SEPIA`) but a name of
         * our own, turned into an explicit background and text colour in
         * [dev.reedd.ui.reader.ReaderViewModel.preferences].
         */
        const val PAPER = "PAPER"
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(context: Context, scope: CoroutineScope) {

    private val dataStore = context.applicationContext.dataStore

    val settings: Flow<ServerSettings> = dataStore.data.map { prefs ->
        ServerSettings(
            // Falls back to the built-in default, so a fresh install can reach the
            // server without visiting Settings first.
            baseUrl = prefs[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: ServerSettings.DEFAULT_BASE_URL,
            token = prefs[KEY_TOKEN],
            voice = prefs[KEY_VOICE],
            speed = prefs[KEY_SPEED] ?: 1.0,
            engine = prefs[KEY_ENGINE],
            deleteJobAfterDownload = prefs[KEY_DELETE_AFTER] ?: false,
        )
    }

    /**
     * The last value seen, for callers that cannot suspend.
     *
     * OkHttp interceptors run on a background thread but are not coroutines, and
     * blocking one on a DataStore read risks a deadlock during app start. They
     * read this instead; it is primed by the collector below.
     */
    @Volatile
    var snapshot: ServerSettings = ServerSettings()
        private set

    init {
        scope.launch { settings.collect { snapshot = it } }
    }

    suspend fun current(): ServerSettings = settings.first()

    suspend fun setServer(baseUrl: String?, token: String?) {
        dataStore.edit { prefs ->
            if (baseUrl.isNullOrBlank()) prefs.remove(KEY_BASE_URL) else prefs[KEY_BASE_URL] = baseUrl.trim()
            // A real token (secrets.token_urlsafe on the server) never legitimately
            // contains whitespace anywhere, so strip all of it rather than just the
            // edges -- a `singleLine` Compose TextField does not reliably strip an
            // embedded newline from pasted text, and one reaching AuthInterceptor
            // crashes the app on an OkHttp thread outside any try/catch (an
            // `Authorization` header value may not contain a raw control
            // character). See AuthInterceptor's own defensive filter for the other
            // half of this fix, which also self-heals a token already saved bad.
            val clean = token?.filterNot { it.isWhitespace() }
            if (clean.isNullOrBlank()) prefs.remove(KEY_TOKEN) else prefs[KEY_TOKEN] = clean
        }
    }

    suspend fun setConversionDefaults(voice: String?, speed: Double, engine: String?) {
        dataStore.edit { prefs ->
            if (voice.isNullOrBlank()) prefs.remove(KEY_VOICE) else prefs[KEY_VOICE] = voice
            prefs[KEY_SPEED] = speed.coerceIn(ServerSettings.MIN_SPEED, ServerSettings.MAX_SPEED)
            if (engine.isNullOrBlank()) prefs.remove(KEY_ENGINE) else prefs[KEY_ENGINE] = engine
        }
    }

    suspend fun setDeleteJobAfterDownload(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_DELETE_AFTER] = enabled }
    }

    val readerSettings: Flow<ReaderSettings> = dataStore.data.map { prefs ->
        ReaderSettings(
            fontSize = prefs[KEY_READER_FONT_SIZE] ?: ReaderSettings().fontSize,
            theme = when (val stored = prefs[KEY_READER_THEME]) {
                // Never saved -- a fresh install -- gets the app's own default
                // rather than Readium's "follow system", which is otherwise
                // indistinguishable from a user who explicitly chose System
                // (see the sentinel below).
                null -> ReaderSettings().theme
                SYSTEM_THEME_SENTINEL -> null
                else -> stored
            },
            scroll = prefs[KEY_READER_SCROLL] ?: false,
        )
    }

    suspend fun setReaderSettings(settings: ReaderSettings) {
        dataStore.edit { prefs ->
            prefs[KEY_READER_FONT_SIZE] =
                settings.fontSize.coerceIn(ReaderSettings.MIN_FONT_SIZE, ReaderSettings.MAX_FONT_SIZE)
            // Always written, never removed: a null theme has to read back as
            // "the user picked System", not fall through to the app default
            // meant only for an install that has never saved a choice at all.
            prefs[KEY_READER_THEME] = settings.theme ?: SYSTEM_THEME_SENTINEL
            prefs[KEY_READER_SCROLL] = settings.scroll
        }
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_TOKEN = stringPreferencesKey("api_token")
        val KEY_VOICE = stringPreferencesKey("voice")
        val KEY_SPEED = doublePreferencesKey("speed")
        val KEY_ENGINE = stringPreferencesKey("engine")
        val KEY_DELETE_AFTER = booleanPreferencesKey("delete_job_after_download")
        val KEY_READER_FONT_SIZE = doublePreferencesKey("reader_font_size")
        val KEY_READER_THEME = stringPreferencesKey("reader_theme")
        val KEY_READER_SCROLL = booleanPreferencesKey("reader_scroll")

        /** Not a real theme name -- see [readerSettings] and [setReaderSettings]. */
        const val SYSTEM_THEME_SENTINEL = "SYSTEM"
        // "reader_page_margins" (a Double) is no longer written, but deliberately
        // not reused for a different type -- an existing install's DataStore may
        // still hold it, and Preferences DataStore has no per-key migration, only
        // whole-file ones. A stray unread key is harmless; a type collision is not.
    }
}
