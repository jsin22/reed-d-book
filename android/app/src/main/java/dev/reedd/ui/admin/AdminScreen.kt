package dev.reedd.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.reedd.data.remote.JobDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    viewModel: AdminViewModel,
    onBack: () -> Unit,
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    val inviteResult by viewModel.inviteResult.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var email by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin") },
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
            Text("Invite a user", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    viewModel.invite(email)
                    email = ""
                },
                enabled = inviteResult !is InviteResult.Sending,
            ) { Text("Invite") }

            when (val result = inviteResult) {
                is InviteResult.Idle -> Unit
                is InviteResult.Sending -> Text("Sending…", style = MaterialTheme.typography.bodySmall)
                is InviteResult.Sent -> Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Invited ${result.result.user.email}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (result.result.emailSent) {
                            Text("Invite email sent.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(
                                "Email not configured on the server -- hand-deliver this token:",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            result.result.token?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall
                                        .copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                        }
                    }
                }
                is InviteResult.Failed -> Text(
                    result.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()

            Text("Users", style = MaterialTheme.typography.titleMedium)
            users.forEach { user ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(user.email, style = MaterialTheme.typography.bodyMedium)
                    if (user.isAdmin) {
                        Text(
                            "admin",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            Text("Every job", style = MaterialTheme.typography.titleMedium)
            jobs.forEach { job ->
                JobRow(job, onTogglePublic = { viewModel.togglePublic(job.jobId, it) })
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun JobRow(job: JobDto, onTogglePublic: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.fillMaxWidth(0.75f)) {
            Text(job.filename, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${job.ownerEmail ?: "unknown owner"} · ${job.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = job.public, onCheckedChange = onTogglePublic)
    }
}
