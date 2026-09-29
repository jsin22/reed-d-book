package dev.reedd.ui.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A read-only text field plus dropdown of voice names -- shared by the
 * reader's own live-voice picker (`ReaderScreen.kt`'s settings sheet) and
 * the book detail screen's "change offline voice and convert again" dialog,
 * pulled out here (next to [ConversionOptions], which both also share)
 * rather than kept private to either screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceDropdown(voices: List<String>, currentVoice: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = currentVoice ?: "",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            voices.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate) },
                    onClick = {
                        onSelect(candidate)
                        expanded = false
                    },
                )
            }
        }
    }
}
