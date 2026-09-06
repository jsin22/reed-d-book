package dev.reedd.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.reedd.data.db.BookmarkType
import dev.reedd.ui.reader.color

/**
 * Names each of the four bookmark colors -- see [BookmarkType]'s own doc
 * for why they carry no built-in meaning. A fresh install shows all four
 * with an empty field; the swatch is what tells the rows apart until a
 * label is set, both here and everywhere else a bookmark shows up (the word
 * menu's own Bookmark editor and its color picker, the Notes sheet).
 *
 * Local per-field state, seeded once each field first sees a real value
 * (same "seed once, don't stamp over typing" pattern `SettingsScreen`
 * already uses for the server address/token), saved explicitly rather than
 * on every keystroke -- there is nothing here worth writing to disk before
 * the reader has actually decided on a name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkLabelsScreen(
    viewModel: BookmarkLabelsViewModel,
    onBack: () -> Unit,
) {
    val labels by viewModel.labels.collectAsStateWithLifecycle()
    val fields = remember { mutableStateMapOf<BookmarkType, String>() }
    // Cleared by editing any field again, not just on the next Save -- once
    // the fields no longer match what was actually saved, the confirmation
    // would otherwise keep claiming something true a moment ago but not now.
    var justSaved by remember { mutableStateOf(false) }

    LaunchedEffect(labels) {
        labels.forEach { (type, label) -> if (type !in fields) fields[type] = label }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bookmark colors") },
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
            Text(
                "Name what each color is for. Leave any of them blank to just " +
                    "show the color, with no label.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BookmarkType.entries.forEach { type ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(type.color()))
                    OutlinedTextField(
                        value = fields[type].orEmpty(),
                        onValueChange = {
                            fields[type] = it
                            justSaved = false
                        },
                        label = { Text("Label") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Button(onClick = {
                BookmarkType.entries.forEach { type -> viewModel.setLabel(type, fields[type].orEmpty()) }
                justSaved = true
            }) { Text("Save") }

            if (justSaved) {
                Text(
                    "Saved successfully.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
