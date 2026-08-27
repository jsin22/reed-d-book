package dev.reedd.ui.voices

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.remote.ApiProvider
import dev.reedd.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** What one voice's preview button is doing right now. Absent from [VoicesViewModel.states]
 *  means idle -- not worth a variant of its own for the common case. */
sealed interface SampleState {
    data object Loading : SampleState
    data object Playing : SampleState
    data class Failed(val reason: String) : SampleState
}

/**
 * Lets a user hear what each Pocket TTS voice sounds like before picking one
 * to convert a book with.
 *
 * Backed by `GET /api/voices/{voice}/sample` (server/app/main.py): a short,
 * fixed-text clip, generated once per voice server-side and cached there
 * forever after. This ViewModel adds its own client-side cache on top --
 * once a voice has been played, replaying it is instant and offline, never
 * re-hitting the network. Both caches exist for the same reason: browsing
 * every voice must not mean re-synthesizing or re-downloading the same clip
 * on every tap.
 */
class VoicesViewModel(
    private val context: Application,
    private val api: ApiProvider,
) : ViewModel() {

    private val _voices = MutableStateFlow<List<String>>(emptyList())
    val voices: StateFlow<List<String>> = _voices.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** One entry per voice currently loading, playing, or that last failed.
     *  A voice with no entry is idle -- the common case for a long list. */
    private val _states = MutableStateFlow<Map<String, SampleState>>(emptyMap())
    val states: StateFlow<Map<String, SampleState>> = _states.asStateFlow()

    private var player: MediaPlayer? = null
    private var playingVoice: String? = null
    private var playJob: Job? = null

    init {
        loadVoices()
    }

    private fun loadVoices() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = try {
                val engines = api.service().engines().engines
                _voices.value = engines.firstOrNull { it.id == ENGINE }?.voices.orEmpty().sorted()
                null
            } catch (e: Exception) {
                "could not load voices: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * A voice's Play button. Tapping the voice already playing stops it;
     * tapping any other voice stops that one (if any) and starts this one --
     * only one preview plays at a time.
     */
    fun play(voice: String) {
        if (playingVoice == voice && player != null) {
            stop()
            return
        }
        stop()
        // The previous tap's coroutine may still be mid-download when a new
        // one arrives; cancelling it here (plus the ensureActive() check
        // below, since a blocking OkHttp call does not itself respond to
        // cancellation) stops it from calling startPlayback() after the
        // fact and hijacking playback back to a voice that is no longer the
        // one requested.
        playJob?.cancel()
        playJob = viewModelScope.launch {
            setState(voice, SampleState.Loading)
            try {
                val file = sampleFile(voice)
                if (!file.exists()) downloadSample(voice, file)
                ensureActive()
                startPlayback(voice, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState(voice, SampleState.Failed(e.message ?: "could not play that sample"))
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        val voice = playingVoice
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playingVoice = null
        if (voice != null) clearState(voice)
    }

    private fun sampleFile(voice: String): File =
        File(context.cacheDir, "voice_samples/$ENGINE/$voice.wav")

    /** Downloaded through the app's shared OkHttp client, not Retrofit --
     *  same reasoning as [dev.reedd.data.download.ResumableDownloader]: this
     *  is a raw file, not a JSON body. [dev.reedd.data.remote.AuthInterceptor]
     *  and [dev.reedd.data.remote.ErrorInterceptor] both still apply, since
     *  they are on the client itself, not Retrofit -- a non-2xx response
     *  throws [dev.reedd.data.remote.ApiException] here exactly as it would
     *  through the service interface.
     */
    private suspend fun downloadSample(voice: String, target: File) = withContext(Dispatchers.IO) {
        val url = api.url("api/voices/$voice/sample").newBuilder()
            .addQueryParameter("engine", ENGINE)
            .build()
        val request = Request.Builder().url(url).build()
        api.okHttp.newCall(request).execute().use { response ->
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            response.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(target)
        }
    }

    /** `prepareAsync()`, not the blocking `prepare()`: this runs on whatever
     *  dispatcher [play] resumes on after the download, and a few hundred
     *  milliseconds of disk/decoder setup has no business blocking it. */
    private fun startPlayback(voice: String, file: File) {
        val mp = MediaPlayer()
        player = mp
        playingVoice = voice
        mp.setOnPreparedListener {
            it.start()
            setState(voice, SampleState.Playing)
        }
        mp.setOnCompletionListener { stop() }
        mp.setOnErrorListener { _, _, _ ->
            setState(voice, SampleState.Failed("could not play that sample"))
            stop()
            true
        }
        mp.setDataSource(file.absolutePath)
        mp.prepareAsync()
    }

    private fun setState(voice: String, state: SampleState) {
        _states.value = _states.value + (voice to state)
    }

    private fun clearState(voice: String) {
        _states.value = _states.value - voice
    }

    override fun onCleared() {
        super.onCleared()
        playJob?.cancel()
        player?.release()
        player = null
    }

    companion object {
        private const val ENGINE = "pocket_tts"

        fun factory(container: AppContainer, context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                VoicesViewModel(context.applicationContext as Application, container.api) as T
        }
    }
}
