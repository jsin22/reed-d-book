package dev.reedd.ui.reader

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Fullscreen
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
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.reedd.data.settings.ReaderSettings
import dev.reedd.ui.theme.paperColorScheme
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent

/** Log tag for the word-tap path, which can only be diagnosed on hardware. */
private const val TAG_TAP = "ReeddTap"

/** Roughly four seconds of looking for the fragment before giving up on it. */
private const val FRAGMENT_LOOKUP_ATTEMPTS = 40
private const val FRAGMENT_LOOKUP_DELAY_MS = 100L

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
    // Immersive: toolbars hidden so the text has the whole screen. Entered from the
    // toolbar button, left by a tap that lands on nothing readable.
    var immersive by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pageInfo by viewModel.pageInfo.collectAsStateWithLifecycle()
    val tappedWord by readAlongViewModel.tappedWord.collectAsStateWithLifecycle()
    val definition by readAlongViewModel.definition.collectAsStateWithLifecycle()

    // Hide the system status and navigation bars too, not just the app's own toolbars
    // — otherwise "immersive" still leaves a status-bar strip above the text, which is
    // most of what remains of the top margin.
    val view = LocalView.current
    LaunchedEffect(immersive) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // With the e-ink theme the whole screen becomes paper, not just the page: a grey
    // page inside a white window would read as a rendering fault rather than a
    // choice. Readium is handed the same two colours.
    ReaderPalette(paper = settings.theme == ReaderSettings.PAPER) {

        Scaffold(
            topBar = {
                if (!immersive) {
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
                            // Explicit control, because a tap on the page is now an
                            // offer to read from there and cannot double as this.
                            IconButton(onClick = { immersive = true }) {
                                Icon(Icons.Filled.Fullscreen, contentDescription = "Full screen")
                            }
                            IconButton(onClick = { showContents = true }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents")
                            }
                            IconButton(onClick = { showAppearance = true }) {
                                Icon(Icons.Filled.TextFields, contentDescription = "Appearance")
                            }
                        },
                    )
                }
            },
            bottomBar = {
                // Only for a book that actually has audio; a plain epub reads as before.
                if (readAlong.available && !immersive) {
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
            snackbarHost = { SnackbarHost(snackbar) },
            // With the bars hidden the page should reach the physical edges of the
            // screen, so the Scaffold stops reserving room for system insets.
            contentWindowInsets = if (immersive) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (val current = state) {
                    is ReaderState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    is ReaderState.Failed -> Text(
                        current.message,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    is ReaderState.Ready -> EpubNavigator(
                        state = current,
                        viewModel = viewModel,
                        readAlongViewModel = readAlongViewModel,
                        settings = settings,
                        onToggleImmersive = { immersive = !immersive },
                        onMessage = { text -> scope.launch { snackbar.showSnackbar(text) } },
                    )
                }

                // Page indicator, over the bottom of the page rather than in a bar
                // of its own: it stays visible when the toolbars are hidden, which is
                // when you most want to know where you are.
                val page = pageInfo
                if (page != null && page.label.isNotEmpty() && state is ReaderState.Ready) {
                    Text(
                        page.label,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
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

        // The menu for a tapped word, positioned beside the word itself.
        tappedWord?.let { target ->
            WordMenu(
                target = target,
                onReadFromHere = readAlongViewModel::readFromTappedWord,
                onDefine = readAlongViewModel::defineTappedWord,
                onDismiss = readAlongViewModel::dismissWordMenu,
            )
        }

        definition?.let { current ->
            DefinitionSheet(state = current, onDismiss = readAlongViewModel::dismissDefinition)
        }

    } // ReaderPalette
}

/**
 * Supplies the e-ink colour scheme for the reader, or leaves the app's own alone.
 *
 * A wrapper rather than splitting the screen into two composables: the reader's
 * state (which sheet is open, the current book) all lives in one function, and
 * threading it through a parameter list to satisfy a theme change would be a poor
 * trade.
 */
@Composable
private fun ReaderPalette(paper: Boolean, content: @Composable () -> Unit) {
    if (paper) MaterialTheme(colorScheme = paperColorScheme(), content = content) else content()
}

@Composable
private fun EpubNavigator(
    state: ReaderState.Ready,
    viewModel: ReaderViewModel,
    readAlongViewModel: ReadAlongViewModel,
    settings: ReaderSettings,
    onToggleImmersive: () -> Unit,
    onMessage: (String) -> Unit,
) {
    // Temporary, while the tap path is being confirmed on hardware: every tap says
    // what it resolved to, so a failure can be pinpointed without adb. Remove once
    // word tapping is known good.
    val onTapDiagnostic: (String) -> Unit = onMessage

    val context = LocalContext.current
    val activity = context as FragmentActivity
    val darkTheme = isSystemInDarkTheme()
    val preferences = viewModel.preferences(settings, darkTheme)
    val highlightTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f).toArgbCompat()
    val scope = rememberCoroutineScope()

    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val readAlong by readAlongViewModel.state.collectAsStateWithLifecycle()
    val navigateTo by readAlongViewModel.navigateTo.collectAsStateWithLifecycle()
    val tappedWord by readAlongViewModel.tappedWord.collectAsStateWithLifecycle()

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
            // Two things here. The invisible template for the tap-target layer,
            // without which Readium's default Underline paints over every sentence
            // on the page (BUGS.md, BUG-3); and a "Read from here" item added to the
            // text-selection toolbar.
            configuration = EpubNavigatorFragment.Configuration(
                // Shrinks the padding at its source. `pageMargins` is only a
                // multiplier on this, so lowering the base is what actually lets the
                // text reach the edges; `flowSpacing` is the vertical rhythm between
                // blocks, which is most of the gap above the first heading.
                readiumCssRsProperties = RsProperties(
                    pageGutter = Length.Px(8.0),
                    flowSpacing = Length.Rem(0.5),
                ),
                decorationTemplates = ReadAlongLocators.decorationTemplates(),
                selectionActionModeCallback = ReadFromHereAction(
                    onSelected = {
                        scope.launch {
                            val fragment = activity.supportFragmentManager.fragments
                                .filterIsInstance<EpubNavigatorFragment>()
                                .firstOrNull()
                            val selection = fragment?.currentSelection()
                            val text = selection?.locator?.text?.highlight
                            if (text.isNullOrBlank()) {
                                onMessage("Nothing selected")
                            } else {
                                val href = selection.locator.href.toString()
                                if (readAlongViewModel.playFromSelection(href, text)) {
                                    fragment.clearSelection()
                                } else {
                                    onMessage("That passage is not matched to the audio")
                                }
                            }
                        }
                    },
                ),
            ),
        )
        true
    }

    AndroidFragment<EpubNavigatorFragment>(modifier = Modifier.fillMaxSize()) { fragment ->
        viewModel.onNavigatorReady(fragment)
        navigator = fragment
    }

    // Everything below hangs off `navigator`: the input listener that makes word taps
    // work, the decorations, and the preference pushes. Relying solely on
    // AndroidFragment's update lambda to supply it makes all of them fail together and
    // silently if that lambda does not run, so the fragment is also looked up directly
    // from the FragmentManager. Whichever arrives first wins.
    LaunchedEffect(state.publication) {
        repeat(FRAGMENT_LOOKUP_ATTEMPTS) {
            if (navigator != null) return@LaunchedEffect
            activity.supportFragmentManager.fragments
                .filterIsInstance<EpubNavigatorFragment>()
                .firstOrNull()
                ?.let {
                    Log.i(TAG_TAP, "navigator resolved from the FragmentManager")
                    viewModel.onNavigatorReady(it)
                    navigator = it
                    return@LaunchedEffect
                }
            delay(FRAGMENT_LOOKUP_DELAY_MS)
        }
        if (navigator == null) {
            Log.w(TAG_TAP, "no EpubNavigatorFragment found; taps and highlights will not work")
            onMessage("Reader did not attach; word tap unavailable")
        }
    }

    // Push appearance changes as they happen.
    //
    // This has to be its own effect keyed on `preferences`: AndroidFragment's update
    // lambda above runs when it creates the fragment, not on every recomposition, so
    // submitting preferences there meant a font-size or theme change did not reach
    // the WebView until the reader was closed and reopened. Pushed rather than
    // recreating the fragment, which would lose the reading position.
    LaunchedEffect(navigator, preferences) {
        navigator?.submitPreferences(preferences)
    }

    // Dragging the page means the reader wants to look somewhere else, so stop
    // moving it for them. Registered once per fragment.
    LaunchedEffect(navigator) {
        val fragment = navigator ?: return@LaunchedEffect
        val input = object : InputListener {
            /**
             * A single tap in the middle of the page *offers* to read from the
             * sentence tapped; confirming is a second tap on the prompt.
             *
             * Edge taps are left to Readium so page turns still work, so this is the
             * middle third only. When the tap does not land on mapped text it either
             * dismisses an outstanding offer or toggles the toolbars, so a tap is
             * never a dead action.
             */
            override fun onTap(event: TapEvent): Boolean {
                // Never bail out on a zero width. publicationView can report 0 before
                // it has been laid out, and the old code returned false there, which
                // made the tap do nothing at all with no way to tell why.
                val width = fragment.publicationView.width
                    .takeIf { it > 0 }
                    ?: fragment.publicationView.resources.displayMetrics.widthPixels

                val x = event.point.x
                if (x <= width / 3f || x >= width * 2f / 3f) {
                    // Turning the page: a menu about a word that is about to leave
                    // the screen should not survive it.
                    readAlongViewModel.dismissWordMenu()
                    return false
                }

                scope.launch {
                    // Readium's JS multiplies by devicePixelRatio before handing the
                    // tap over, so the point arrives in device pixels and has to be
                    // divided back to the CSS pixels the WebView works in.
                    val density = fragment.publicationView.resources.displayMetrics.density
                    val tapped = TapTextResolver.resolve(
                        fragment = fragment,
                        x = event.point.x / density,
                        y = event.point.y / density,
                    )
                    Log.i(TAG_TAP, "tap dev=(${event.point.x}, ${event.point.y}) " +
                        "density=$density width=$width -> ${tapped?.word ?: "no word"}")

                    if (tapped == null) {
                        onTapDiagnostic(
                            "No word found at (${event.point.x.toInt()}, ${event.point.y.toInt()})"
                        )
                        if (readAlongViewModel.tappedWord.value != null) {
                            readAlongViewModel.dismissWordMenu()
                            TapTextResolver.clearHighlight(fragment)
                        } else {
                            onToggleImmersive()
                        }
                        return@launch
                    }
                    readAlongViewModel.onWordTapped(
                        word = tapped.word,
                        resourceHref = fragment.currentLocator.value.href.toString(),
                        blockText = tapped.blockText,
                        offset = tapped.offset,
                        // Just below the word, so the menu does not cover it.
                        anchorX = (tapped.left * density).toInt(),
                        anchorY = (tapped.bottom * density).toInt(),
                    )
                }
                return true
            }

            override fun onDrag(event: DragEvent): Boolean {
                readAlongViewModel.onUserDragged()
                readAlongViewModel.dismissWordMenu()
                return false // let Readium scroll or page as normal
            }
        }
        fragment.addInputListener(input)
        Log.i(TAG_TAP, "input listener attached to the navigator")
        onTapDiagnostic("Reader ready: tap a word")

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
    // The word highlight belongs to the menu: when the menu goes, so does it.
    LaunchedEffect(navigator, tappedWord) {
        val fragment = navigator ?: return@LaunchedEffect
        if (tappedWord == null) TapTextResolver.clearHighlight(fragment)
    }

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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // null means "follow the system", which is the default. "Paper" is ours,
            // not one of Readium's: a warm grey page with near-black text.
            listOf(
                null to "System",
                ReaderSettings.PAPER to "Paper (e-ink)",
                "LIGHT" to "Light",
                "SEPIA" to "Sepia",
                "DARK" to "Dark",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = settings.theme == value,
                    onClick = { onChange { it.copy(theme = value) } },
                    label = { Text(label) },
                )
            }
        }

        Text("Margins", style = MaterialTheme.typography.titleSmall)
        Text(
            "How much white space is left either side of the text. Lower fills more of the screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = settings.pageMargins.toFloat(),
            onValueChange = { value -> onChange { it.copy(pageMargins = value.toDouble()) } },
            valueRange = ReaderSettings.MIN_PAGE_MARGINS.toFloat()..ReaderSettings.MAX_PAGE_MARGINS.toFloat(),
            steps = 8,
        )

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
