package dev.reedd.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.reedd.data.settings.ServerSettings

/** The only engine the app offers -- see the class doc below for why. */
private const val ENGINE = "pocket_tts"

/**
 * Voice and speed for one conversion.
 *
 * The engine itself is not a choice here: `pocket_tts` is the only one this
 * app -- or, as of this session, the server itself -- offers at all (Kokoro
 * and Supertonic were both removed server-side; see git history on
 * audiblez/engines.py). Voices come from `GET /api/engines`, scoped to
 * `pocket_tts`'s own catalog. When the server does not report `pocket_tts`
 * at all (no TTS stack installed, or an older server) this falls back to a
 * free-text voice field submitted with no engine chosen, rather than an
 * empty picker the user cannot get past.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
    onConfirm: (voice: String?, speed: Double, engine: String?) -> Unit,
) {
    val options by viewModel.options.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadConversionOptions() }

    var voice by remember { mutableStateOf<String?>(null) }
    var freeTextVoice by remember { mutableStateOf("") }
    var speed by remember { mutableFloatStateOf(settings.speed.toFloat()) }
    val engineAvailable = options.engines.any { it.id == ENGINE }

    LaunchedEffect(options.engines) {
        val voicesForEngine = options.voicesFor(ENGINE)
        if (voice !in voicesForEngine) {
            voice = (settings.voice?.takeIf { it in voicesForEngine }) ?: options.defaultVoiceFor(ENGINE)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Convert this book", style = MaterialTheme.typography.titleLarge)

            when {
                options.loading -> CircularProgressIndicator(Modifier.size(24.dp))

                options.error != null -> Text(
                    options.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                !engineAvailable -> {
                    Text("Voice", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The server did not report the $ENGINE engine, so it is not validating voices. Type one audiblez accepts, or leave this blank for the server's default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = freeTextVoice,
                        onValueChange = { freeTextVoice = it },
                        label = { Text("Voice") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> {
                    val voicesForEngine = options.voicesFor(ENGINE)
                    Text("Voice", style = MaterialTheme.typography.titleSmall)
                    if (voicesForEngine.isEmpty()) {
                        Text(
                            "This engine reported no voices; using its server-side default.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            voicesForEngine.forEach { candidate ->
                                FilterChip(
                                    selected = voice == candidate,
                                    onClick = { voice = candidate },
                                    label = { Text(candidate) },
                                )
                            }
                        }
                    }
                }
            }

            Text("Speed: ${"%.2f".format(speed)}x", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = speed,
                onValueChange = { speed = it },
                // The server rejects anything outside this, so the control cannot
                // offer it (see MIN_SPEED/MAX_SPEED in server/app/audiblez_meta.py).
                valueRange = ServerSettings.MIN_SPEED.toFloat()..ServerSettings.MAX_SPEED.toFloat(),
                steps = 5,
            )

            Button(
                onClick = {
                    val chosenVoice = if (engineAvailable) voice else freeTextVoice.ifBlank { null }
                    val chosenEngine = if (engineAvailable) ENGINE else null
                    onConfirm(chosenVoice, speed.toDouble(), chosenEngine)
                },
                enabled = !options.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send to server")
            }
        }
    }
}
