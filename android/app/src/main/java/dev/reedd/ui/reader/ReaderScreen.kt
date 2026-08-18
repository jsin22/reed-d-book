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
import androidx.compose.ui.layout.onSizeChanged
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.reedd.data.settings.ReaderSettings
import dev.reedd.ui.theme.paperColorScheme
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
 * Room left at the bottom of the page for the "n / total" overlay: its
 * `labelSmall` text plus its own 2dp padding, with a little slack. See where
 * this is applied on the navigator's own modifier for why it has to be a real
 * layout reservation rather than the indicator having a background.
 */
private val PAGE_INDICATOR_RESERVED_HEIGHT = 24.dp

/** How often continuous scroll checks whether it needs to catch up. See [ScrollFollower]. */
private const val SCROLL_FOLLOW_CHECK_INTERVAL_MS = 400L

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
                // Gated on the book's own row (Room, near-instant), not
                // readAlong.available (only true once PlayerConnection's Media3
                // session IPC round-trip finishes, which is neither instant nor
                // synchronous with the very first layout pass). That gap used to
                // mean the reader opened with no bottom bar, laid out and
                // paginated the WebView against the *full* height, then had the
                // bar appear a beat later and shrink the content area -- and
                // paginated columns don't repaginate for a height change, only a
                // width one (R2WebView.onSizeChanged only recomputes on `w !=
                // oldw`), so the page kept whatever vertical layout it had already
                // committed to. Gating on the same fact the Scaffold ultimately
                // cares about -- will a bar be here at all -- rather than on
                // whether the player has finished connecting yet, means paginated
                // mode gets its final height on the first layout pass instead of a
                // second, later one. Only for a book that actually has audio; a
                // plain epub reads as before.
                if (book?.isPlayable == true && !immersive) {
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
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val darkTheme = isSystemInDarkTheme()
    val preferences = viewModel.preferences(settings, darkTheme)
    val highlightTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f).toArgbCompat()
    val scope = rememberCoroutineScope()
    val book by viewModel.book.collectAsStateWithLifecycle()

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
            // A "Read from here" item added to the text-selection toolbar, for a
            // long-press selection. (Single-tap word menu is a separate path, wired
            // through InputListener below -- see BUGS.md BUG-2/BUG-3 history for why
            // it no longer goes through a decoration layer.)
            configuration = EpubNavigatorFragment.Configuration(
                readiumCssRsProperties = RsProperties(
                    // A few pixels, fixed -- not a user preference. There used to be
                    // a "Margins" slider multiplying this; removed because the far
                    // bigger problem was maxLineLength below, and once that is fixed
                    // there is nothing left worth making the user tune here.
                    pageGutter = Length.Px(8.0),
                    // Readium's default caps the reading column at 40rem and centers
                    // it (`body { max-width: var(--RS__maxLineLength); margin: 0
                    // auto; }` in its own stylesheet) -- built for a desktop browser
                    // window wider than that is comfortable to read across, which no
                    // phone screen ever is. Two consequences on a phone: the text
                    // sits in a narrower, centered column with dead space on both
                    // sides instead of using the screen, and because the cap is in
                    // rem (relative to font size), turning the font size down
                    // shrinks the column by the same proportion instead of fitting
                    // more text -- font size stopped controlling density and started
                    // just controlling how much blank margin there was. Set high
                    // enough that it cannot bind on any phone or tablet at any
                    // supported font scale (ReaderSettings.MAX_FONT_SIZE = 2.5x), so
                    // the column is simply the full viewport width.
                    maxLineLength = Length.Rem(100.0),
                    // flowSpacing/paraSpacing/paraIndent below only matter for a book
                    // with no CSS of its own -- decompiling Readium's actual
                    // ReadiumCss.injectHtml (not guessed at, read directly) showed that
                    // the *only* stylesheet defining --RS__flowSpacing/--RS__paraIndent,
                    // and the h1/p rules that consume them, is ReadiumCSS-default.css,
                    // and it is linked only when the page has no <link>, style=, or
                    // <style> of its own. Virtually every real epub has its own CSS, so
                    // for a typical book this stylesheet -- and every value set below --
                    // never loads at all. (maxLineLength above is unaffected: it lives in
                    // ReadiumCSS-after.css, which loads unconditionally, which is why
                    // the width fix stuck while margin/indent tuning here did not.) Kept
                    // at reasonable values for the rare book with none of its own styling,
                    // where default.css does load; TypographyFixer.kt is what actually
                    // fixes heading margins and paragraph indent for a typical book, by
                    // writing directly to the DOM instead of relying on this pipeline.
                    flowSpacing = Length.Rem(0.25),
                    paraSpacing = Length.Rem(0.0),
                    paraIndent = Length.Em(1.0),
                ),
                // The actual cause of BUG-13's "several rows of blank space above
                // the text" and the far more telling symptom that finally pinned
                // it down -- tapping the blank space resolves to a word rendered
                // well below it, as if the first few lines existed twice.
                //
                // Confirmed by decompiling `R2EpubPageFragment`, the fragment that
                // hosts each chapter's actual WebView (a layer below
                // EpubNavigatorFragment, invisible to anything short of reading
                // Readium's own source): when this is left unset, it defaults to
                // *true*, and Readium then attaches its own
                // `ViewCompat.OnApplyWindowInsetsListener` to that fragment's
                // container view and applies real Android `View.setPadding` for
                // system-bar insets -- entirely independent of, and in addition
                // to, whatever this app's own edge-to-edge Scaffold already
                // reserved for the exact same insets (see MainActivity's
                // `enableEdgeToEdge()` and the topBar/bottomBar handling above).
                // That is genuinely double-counted space, not a CSS margin BUG-12
                // could ever have reached. It also explains the tap bug precisely:
                // real View padding shifts where the WebView's content is
                // *painted* without the app's own tap-coordinate math (density
                // division in the InputListener below) knowing to subtract that
                // same offset, so a tap in the padded dead zone gets handed to
                // `caretRangeFromPoint` as a content-relative coordinate that is
                // too far down by exactly the padding amount -- landing on
                // whatever text is actually rendered that much lower.
                //
                // Fullscreen mode was never affected because it hides system bars
                // entirely, leaving nothing for Readium's own listener to pad for.
                // Explicitly false: this app's layout already owns every inset
                // Readium might otherwise try to account for a second time.
                shouldApplyInsetsPadding = false,
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

    // BUG-13 (BUGS.md) was one specific cause of a late resize -- the transport
    // bar appearing after the fragment's first layout -- fixed by gating the bar
    // on data that resolves sooner. This is the general form of that fix: react
    // to the fragment's own measured size actually changing, for *any* reason,
    // rather than trying to anticipate every cause of one. Paginated columns are
    // computed against whatever height the fragment has at the moment content
    // loads, and nothing about Compose resizing this Box afterwards asks Readium
    // to redo that on its own -- R2WebView.onSizeChanged (BUG-12's decompiling)
    // only recomputes anything for a *width* change. Re-submitting preferences on
    // a real height change is what forces that recomputation to happen.
    var lastNavigatorHeight by remember { mutableStateOf(0) }
    AndroidFragment<EpubNavigatorFragment>(
        modifier = Modifier
            .fillMaxSize()
            // A real gap for the page-number overlay ("6 / 29"), reserved at the
            // Compose layout level rather than left to z-order: an AndroidFragment's
            // view is a separate compositing layer that always draws above
            // ordinary Compose-drawn content in the same Box, regardless of
            // declaration order, so giving the indicator Text a background would
            // not have helped once BUG-13's fix let the page's text reach all the
            // way to the physical bottom edge. This is the only place left that
            // can actually keep book text out of that strip.
            .padding(bottom = PAGE_INDICATOR_RESERVED_HEIGHT)
            .onSizeChanged { size ->
                if (lastNavigatorHeight != 0 && lastNavigatorHeight != size.height) {
                    navigator?.let { fragment -> scope.launch { fragment.submitPreferences(preferences) } }
                }
                lastNavigatorHeight = size.height
            },
    ) { fragment ->
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
    //
    // Also keyed on whether the bottom bar is showing (book?.isPlayable), even
    // though it changes nothing about `preferences` itself: a second, cheap nudge
    // for the same reason the bar's own gating condition was just changed above.
    // Re-submitting is what actually asks Readium to relayout against the
    // fragment's current size; if `book` still somehow resolves after the very
    // first paginated frame despite that fix, this is what catches it.
    LaunchedEffect(navigator, preferences, book?.isPlayable) {
        navigator?.submitPreferences(preferences)
    }

    // Dragging the page means the reader wants to look somewhere else, so stop
    // moving it for them. Registered once per fragment.
    LaunchedEffect(navigator) {
        val fragment = navigator ?: return@LaunchedEffect
        val input = object : InputListener {
            /**
             * A single tap anywhere resolves the word under it. Page turning is
             * swipe-only -- there is deliberately no tap-to-turn-page zone, so a tap
             * always means "what word is this" and nothing else. When nothing is
             * under the finger it either dismisses an outstanding menu or toggles the
             * toolbars, so a tap is never a dead action.
             */
            override fun onTap(event: TapEvent): Boolean {
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

                    if (tapped != null) {
                        readAlongViewModel.onWordTapped(
                            word = tapped.word,
                            resourceHref = fragment.currentLocator.value.href.toString(),
                            blockText = tapped.blockText,
                            offset = tapped.offset,
                            // Just below the word, so the menu does not cover it.
                            anchorX = (tapped.left * density).toInt(),
                            anchorY = (tapped.bottom * density).toInt(),
                        )
                        return@launch
                    }

                    if (readAlongViewModel.tappedWord.value != null) {
                        readAlongViewModel.dismissWordMenu()
                        TapTextResolver.clearHighlight(fragment)
                    } else {
                        onToggleImmersive()
                    }
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
    }

    // Readium loads a new HTML document per resource (chapter), so the DOM
    // TypographyFixer touches is gone the moment the href changes and has to be
    // redone for whatever just loaded. Keyed on the href specifically, not every
    // locator change, since most locator changes are a page turn within the same
    // still-fixed-up resource. Also keyed on fontSize: restarting the effect
    // re-subscribes to currentLocator, which as a StateFlow immediately replays
    // its current value, so moving the text-size slider reapplies the fix to
    // whatever chapter is already on screen rather than waiting for the next
    // page turn.
    LaunchedEffect(navigator, settings.fontSize) {
        val fragment = navigator ?: return@LaunchedEffect
        fragment.currentLocator
            .map { it.href }
            .distinctUntilChanged()
            .collect { TypographyFixer.apply(fragment, settings.fontSize) }
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
    //
    // In continuous scroll this is skipped *except* when the sentence has moved
    // into a new chapter. Scroll mode used to make this same jump on every
    // sentence, which is what made it distracting -- see ScrollFollower for the
    // effect that replaces it for in-chapter movement. But `go()` is not just a
    // scroll position: crossing a chapter boundary means the target locator's
    // href is a different resource than what is currently loaded, and only
    // `go()` (not ScrollFollower, which can only scroll within whatever WebView
    // is already on screen) knows how to page the underlying resource pager
    // there. Skipping it unconditionally in scroll mode left the pager on the
    // old chapter forever once playback reached its end -- audio kept going
    // with nothing on screen tracking it.
    LaunchedEffect(navigator, navigateTo) {
        val fragment = navigator ?: return@LaunchedEffect
        val target = navigateTo ?: return@LaunchedEffect
        val chunk = readAlongViewModel.chunkIndex().chunkAtIndex(target)
        val locator = chunk?.let { ReadAlongLocators.locator(state.publication, it) }
        val chapterChanged = locator != null && locator.href != fragment.currentLocator.value.href
        if (locator != null && (!settings.scroll || chapterChanged)) {
            fragment.go(locator, animated = false)
        }
        readAlongViewModel.onNavigationHandled(target)
    }

    // Continuous scroll's replacement for the jump above: see ScrollFollower for
    // why. Polls rather than reacting to `navigateTo`, since what matters is the
    // sentence's actual on-screen position, which drifts continuously as the
    // reader scrolls -- not just the moments the sentence changes.
    LaunchedEffect(navigator, settings.scroll) {
        val fragment = navigator ?: return@LaunchedEffect
        if (!settings.scroll) return@LaunchedEffect
        while (isActive) {
            delay(SCROLL_FOLLOW_CHECK_INTERVAL_MS)
            if (readAlong.following) ScrollFollower.scrollToTopIfPastThreshold(fragment)
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
