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
    val deleteJobAfterDownload: Boolean = false,
) {
    companion object {
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 2.0

        /**
         * Where the app looks for the server when nothing has been configured.
         *
         * The Tailscale address of the development machine, deliberately: unlike a
         * LAN address it does not change when DHCP feels like it, and it works from
         * outside the house. It is only a *default* — Settings still overrides it,
         * and clearing the field falls back here rather than to nothing, so a
         * reinstall does not mean retyping an address.
         */
        const val DEFAULT_BASE_URL = "100.98.16.59:8000"
    }
}

/**
 * How the reader renders. Separate from [ServerSettings] because it changes for a
 * different reason -- a reading preference, not a connection detail.
 *
 * @param fontSize a scale factor, where 1.0 is the publication's own size.
 * @param theme one of Readium's `LIGHT`, `DARK`, `SEPIA`, or null to follow the
 *   system.
 */
data class ReaderSettings(
    val fontSize: Double = 1.0,
    val theme: String? = null,
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
            if (token.isNullOrBlank()) prefs.remove(KEY_TOKEN) else prefs[KEY_TOKEN] = token.trim()
        }
    }

    suspend fun setConversionDefaults(voice: String?, speed: Double) {
        dataStore.edit { prefs ->
            if (voice.isNullOrBlank()) prefs.remove(KEY_VOICE) else prefs[KEY_VOICE] = voice
            prefs[KEY_SPEED] = speed.coerceIn(ServerSettings.MIN_SPEED, ServerSettings.MAX_SPEED)
        }
    }

    suspend fun setDeleteJobAfterDownload(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_DELETE_AFTER] = enabled }
    }

    val readerSettings: Flow<ReaderSettings> = dataStore.data.map { prefs ->
        ReaderSettings(
            fontSize = prefs[KEY_READER_FONT_SIZE] ?: 1.0,
            theme = prefs[KEY_READER_THEME],
            scroll = prefs[KEY_READER_SCROLL] ?: false,
        )
    }

    suspend fun setReaderSettings(settings: ReaderSettings) {
        dataStore.edit { prefs ->
            prefs[KEY_READER_FONT_SIZE] =
                settings.fontSize.coerceIn(ReaderSettings.MIN_FONT_SIZE, ReaderSettings.MAX_FONT_SIZE)
            if (settings.theme == null) prefs.remove(KEY_READER_THEME) else prefs[KEY_READER_THEME] = settings.theme
            prefs[KEY_READER_SCROLL] = settings.scroll
        }
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_TOKEN = stringPreferencesKey("api_token")
        val KEY_VOICE = stringPreferencesKey("voice")
        val KEY_SPEED = doublePreferencesKey("speed")
        val KEY_DELETE_AFTER = booleanPreferencesKey("delete_job_after_download")
        val KEY_READER_FONT_SIZE = doublePreferencesKey("reader_font_size")
        val KEY_READER_THEME = stringPreferencesKey("reader_theme")
        val KEY_READER_SCROLL = booleanPreferencesKey("reader_scroll")
        // "reader_page_margins" (a Double) is no longer written, but deliberately
        // not reused for a different type -- an existing install's DataStore may
        // still hold it, and Preferences DataStore has no per-key migration, only
        // whole-file ones. A stray unread key is harmless; a type collision is not.
    }
}
