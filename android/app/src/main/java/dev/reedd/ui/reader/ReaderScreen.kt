package dev.reedd.ui.reader

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.reedd.data.settings.ReaderSettings
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * The reading screen.
 *
 * Readium's EPUB navigator is a Fragment wrapping a WebView, so this hosts it via
 * [AndroidFragment] rather than rendering the text in Compose. That is the
 * trade-off taken deliberately: a WebView renders the publication's own CSS,
 * fonts and pagination faithfully, and it is what Phase 4 will draw highlight
 * decorations into.
 *
 * The fragment is constructed by a [androidx.fragment.app.FragmentFactory] that
 * Readium builds around the open publication, so the factory has to be installed
 * on the activity's FragmentManager *before* [AndroidFragment] instantiates
 * anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val book by viewModel.book.collectAsStateWithLifecycle()
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showContents by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        book?.title ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showContents = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents")
                    }
                    IconButton(onClick = { showAppearance = true }) {
                        Icon(Icons.Filled.TextFields, contentDescription = "Appearance")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                is ReaderState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is ReaderState.Failed -> Text(
                    current.message,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )

                is ReaderState.Ready -> EpubNavigator(current, viewModel, settings)
            }
        }
    }

    if (showContents) {
        val toc = (state as? ReaderState.Ready)?.tableOfContents.orEmpty()
        ModalBottomSheet(onDismissRequest = { showContents = false }) {
            if (toc.isEmpty()) {
                Text(
                    "This book has no table of contents.",
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn {
                    items(toc) { link ->
                        ListItem(
                            headlineContent = { Text(link.title ?: link.href.toString()) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.goTo(link)
                                    showContents = false
                                },
                        )
                    }
                }
            }
        }
    }

    if (showAppearance) {
        ModalBottomSheet(onDismissRequest = { showAppearance = false }) {
            AppearanceControls(settings, viewModel::updateSettings)
        }
    }
}

@Composable
private fun EpubNavigator(
    state: ReaderState.Ready,
    viewModel: ReaderViewModel,
    settings: ReaderSettings,
) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val darkTheme = isSystemInDarkTheme()
    val preferences = viewModel.preferences(settings, darkTheme)

    // Installed before AndroidFragment creates the fragment: the factory is what
    // injects the open publication and the starting position. Keyed on the
    // publication so a different book replaces it.
    remember(state.publication) {
        activity.supportFragmentManager.fragmentFactory = state.navigatorFactory.createFragmentFactory(
            initialLocator = state.initialLocator,
            initialPreferences = preferences,
            listener = ExternalLinkOpener { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()))
                }
            },
        )
        true
    }

    AndroidFragment<EpubNavigatorFragment>(
        modifier = Modifier.fillMaxSize(),
    ) { fragment ->
        viewModel.onNavigatorReady(fragment)
        // Appearance changes are pushed here rather than by recreating the
        // fragment, which would lose the reading position.
        fragment.submitPreferences(preferences)
    }
}

@Composable
private fun AppearanceControls(
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Text size", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = settings.fontSize.toFloat(),
            onValueChange = { value -> onChange { it.copy(fontSize = value.toDouble()) } },
            valueRange = ReaderSettings.MIN_FONT_SIZE.toFloat()..ReaderSettings.MAX_FONT_SIZE.toFloat(),
            steps = 7,
        )

        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // null means "follow the system", which is the default.
            listOf(null to "System", "LIGHT" to "Light", "SEPIA" to "Sepia", "DARK" to "Dark").forEach { (value, label) ->
                FilterChip(
                    selected = settings.theme == value,
                    onClick = { onChange { it.copy(theme = value) } },
                    label = { Text(label) },
                )
            }
        }

        Text("Layout", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !settings.scroll,
                onClick = { onChange { it.copy(scroll = false) } },
                label = { Text("Pages") },
            )
            FilterChip(
                selected = settings.scroll,
                onClick = { onChange { it.copy(scroll = true) } },
                label = { Text("Continuous scroll") },
            )
        }
    }
}
