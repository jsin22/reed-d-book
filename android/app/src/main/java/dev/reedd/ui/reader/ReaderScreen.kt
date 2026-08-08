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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.reedd.data.settings.ReaderSettings
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent

/**
 * The reading screen, with read-along.
 *
 * Readium's EPUB navigator is a Fragment wrapping a WebView, so this hosts it via
 * [AndroidFragment] rather than rendering text in Compose. That was the deliberate
 * Phase 3 trade-off and it is what pays off here: highlighting is a Readium
 * *decoration*, resolved by its own JavaScript against the rendered DOM, so the
 * publication's real CSS and pagination are preserved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    readAlongViewModel: ReadAlongViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val book by viewModel.book.collectAsStateWithLifecycle()
    val readAlong by readAlongViewModel.state.collectAsStateWithLifecycle()
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
        bottomBar = {
            // Only for a book that actually has audio; a plain epub reads as before.
            if (readAlong.available) {
                ReadAlongBar(
                    state = readAlong,
                    onTogglePlay = readAlongViewModel::togglePlayPause,
                    onPreviousSentence = readAlongViewModel::previousSentence,
                    onNextSentence = readAlongViewModel::nextSentence,
                    onSeek = readAlongViewModel::seekTo,
                    onSpeed = readAlongViewModel::setSpeed,
                    onToggleFollow = readAlongViewModel::toggleFollowing,
                )
            }
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

                is ReaderState.Ready -> EpubNavigator(current, viewModel, readAlongViewModel, settings)
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
            AppearanceControls(
                settings = settings,
                onChange = viewModel::updateSettings,
                readAlong = readAlong,
                onSyncOffset = readAlongViewModel::setSyncOffset,
            )
        }
    }
}

@Composable
private fun EpubNavigator(
    state: ReaderState.Ready,
    viewModel: ReaderViewModel,
    readAlongViewModel: ReadAlongViewModel,
    settings: ReaderSettings,
) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val darkTheme = isSystemInDarkTheme()
    val preferences = viewModel.preferences(settings, darkTheme)
    val highlightTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f).toArgbCompat()

    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val readAlong by readAlongViewModel.state.collectAsStateWithLifecycle()
    val navigateTo by readAlongViewModel.navigateTo.collectAsStateWithLifecycle()

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

    AndroidFragment<EpubNavigatorFragment>(modifier = Modifier.fillMaxSize()) { fragment ->
        viewModel.onNavigatorReady(fragment)
        // Appearance changes are pushed rather than recreating the fragment, which
        // would lose the reading position.
        fragment.submitPreferences(preferences)
        navigator = fragment
    }

    // Dragging the page means the reader wants to look somewhere else, so stop
    // moving it for them. Registered once per fragment.
    LaunchedEffect(navigator) {
        val fragment = navigator ?: return@LaunchedEffect
        val input = object : InputListener {
            override fun onTap(event: TapEvent): Boolean = false
            override fun onDrag(event: DragEvent): Boolean {
                readAlongViewModel.onUserDragged()
                return false // let Readium scroll or page as normal
            }
        }
        fragment.addInputListener(input)

        // Tapping a sentence plays from it. The tap arrives as an activated
        // decoration, so the chunk index comes back exactly rather than being
        // guessed from coordinates.
        val decorationListener = object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                val index = ReadAlongLocators.chunkIndexFromTapId(event.decoration.id)
                    ?: return false
                readAlongViewModel.playFrom(index)
                return true
            }
        }
        fragment.addDecorationListener(ReadAlongLocators.TAP_GROUP, decorationListener)
    }

    // Highlight the sentence being spoken. One decoration, replaced by id, so the
    // previous highlight clears itself.
    LaunchedEffect(navigator, readAlong.currentIndex, highlightTint) {
        val fragment = navigator ?: return@LaunchedEffect
        if (!fragment.supportsDecorationStyle(org.readium.r2.navigator.Decoration.Style.Highlight::class)) {
            return@LaunchedEffect
        }
        val chunk = readAlongViewModel.chunkIndex().chunkAtIndex(readAlong.currentIndex)
        val locator = chunk?.let { ReadAlongLocators.locator(state.publication, it) }
        fragment.applyDecorations(
            decorations = locator?.let { listOf(ReadAlongLocators.decoration(it, highlightTint)) } ?: emptyList(),
            group = ReadAlongLocators.DECORATION_GROUP,
        )
    }

    // Move the page to the current sentence when following. A one-shot event, not
    // derived state: replaying it would drag the page back under the reader.
    LaunchedEffect(navigator, navigateTo) {
        val fragment = navigator ?: return@LaunchedEffect
        val target = navigateTo ?: return@LaunchedEffect
        val chunk = readAlongViewModel.chunkIndex().chunkAtIndex(target)
        val locator = chunk?.let { ReadAlongLocators.locator(state.publication, it) }
        if (locator != null) fragment.go(locator, animated = false)
        readAlongViewModel.onNavigationHandled(target)
    }

    // Invisible tappable decorations for the resource on screen. Scoped to that
    // resource so the batch stays small.
    LaunchedEffect(navigator, readAlong.alignedChunks) {
        val fragment = navigator ?: return@LaunchedEffect
        val chunks = readAlongViewModel.chunkIndex().chunks
        if (chunks.isEmpty()) return@LaunchedEffect
        fragment.currentLocator.collect { locator ->
            val href = locator.href.toString().substringAfterLast('/')
            val forResource = chunks.filter { it.resourceHref?.substringAfterLast('/') == href }
            if (forResource.isEmpty()) return@collect
            fragment.applyDecorations(
                decorations = ReadAlongLocators.tapDecorations(
                    publication = state.publication,
                    chunks = chunks,
                    resourceHref = forResource.first().resourceHref!!,
                ),
                group = ReadAlongLocators.TAP_GROUP,
            )
        }
    }
}

@Composable
private fun AppearanceControls(
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    readAlong: ReadAlongState,
    onSyncOffset: (Long) -> Unit,
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

        if (readAlong.available) {
            HorizontalDivider()
            Text("Highlight timing", style = MaterialTheme.typography.titleSmall)
            Text(
                "If the highlight runs ahead of or behind the voice, nudge it. The .m4b carries a few tens of milliseconds of encoder padding that the timings do not describe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        onSyncOffset(
                            (readAlong.syncOffsetMs - ReadAlongViewModel.OFFSET_STEP_MS)
                                .coerceAtLeast(-ReadAlongViewModel.OFFSET_LIMIT_MS)
                        )
                    }
                ) { Text("Earlier") }
                Text(
                    "${readAlong.syncOffsetMs} ms",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = {
                        onSyncOffset(
                            (readAlong.syncOffsetMs + ReadAlongViewModel.OFFSET_STEP_MS)
                                .coerceAtMost(ReadAlongViewModel.OFFSET_LIMIT_MS)
                        )
                    }
                ) { Text("Later") }
                if (readAlong.syncOffsetMs != 0L) {
                    OutlinedButton(onClick = { onSyncOffset(0) }) { Text("Reset") }
                }
            }
        }
    }
}

/** Compose colour to the packed int Readium's decoration API expects. */
private fun androidx.compose.ui.graphics.Color.toArgbCompat(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
