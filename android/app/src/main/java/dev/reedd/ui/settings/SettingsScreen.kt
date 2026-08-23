package dev.reedd.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenAdmin: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val storage by viewModel.storageBytes.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()

    var baseUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    // Seed the fields once the stored settings arrive, without stamping over what
    // the user is currently typing.
    LaunchedEffect(settings.baseUrl, settings.token) {
        if (baseUrl.isEmpty()) baseUrl = settings.baseUrl.orEmpty()
        if (token.isEmpty()) token = settings.token.orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Conversion server", style = MaterialTheme.typography.titleMedium)
            Text(
                "The machine running the FastAPI server. On the same wifi that is its LAN address; over Tailscale it works from anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Address") },
                placeholder = { Text("192.168.1.101:8000") },
                supportingText = {
                    // Shows what the app will actually request, so a missing scheme
                    // or a stray path is visible before anything is sent.
                    viewModel.normalizedPreview(baseUrl)?.let { Text("Will use $it") }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("API token") },
                supportingText = { Text("The token from your invite. Paste it here.") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.testConnection(baseUrl, token) }) { Text("Test connection") }
                OutlinedButton(onClick = { viewModel.save(baseUrl, token) }) { Text("Save") }
            }

            when (val result = check) {
                is ConnectionCheck.Idle -> Unit
                is ConnectionCheck.Checking -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                    Text("Checking", style = MaterialTheme.typography.bodySmall)
                }

                is ConnectionCheck.Reachable -> Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Server reached", style = MaterialTheme.typography.titleSmall)
                        result.dataDir?.let {
                            Text(
                                "data: $it",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                        result.broker?.let {
                            Text(
                                "broker: $it",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }

                is ConnectionCheck.Unreachable -> Text(
                    result.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            me?.let { current ->
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Logged in as ${current.email}", style = MaterialTheme.typography.bodyMedium)
                    if (current.isAdmin) {
                        OutlinedButton(onClick = onOpenAdmin) { Text("Admin") }
                    }
                }
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.fillMaxWidth(0.75f)) {
                    Text("Free server disk after downloading", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Deletes the job on the server once both files are on this device. Off by default: " +
                            "a book left on the server can be pulled onto another device, or this one again " +
                            "after a reinstall, without converting it a second time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.deleteJobAfterDownload,
                    onCheckedChange = viewModel::setDeleteJobAfterDownload,
                )
            }

            HorizontalDivider()

            Text("Storage", style = MaterialTheme.typography.titleMedium)
            Text(
                "%.1f MB used by imported books and audiobooks".format(storage / 1024.0 / 1024.0),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = viewModel::refreshStorage) { Text("Recalculate") }

            HorizontalDivider()

            // What tells one install apart from the last one, since debug builds
            // change several times a day and versionName/versionCode do not move.
            Text(
                "Build ${dev.reedd.BuildInfo.GIT_SHA} · ${dev.reedd.BuildInfo.BUILT_AT}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
