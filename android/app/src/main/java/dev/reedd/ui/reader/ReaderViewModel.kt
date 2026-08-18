package dev.reedd.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.readium.ReadiumComponents
import dev.reedd.data.settings.ReaderSettings
import dev.reedd.data.settings.SettingsStore
import dev.reedd.di.AppContainer
import dev.reedd.ui.theme.PaperPalette
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.File
import kotlin.math.roundToInt

/**
 * Where you are in the book, for the page indicator.
 *
 * A reflowable epub has no real page numbers — the count depends on font size and
 * screen — so these are Readium's *positions*: stable, evenly-sized slices of the
 * publication, which is the closest honest equivalent and does not change when the
 * text is resized. [percent] is the fallback for a publication whose positions
 * cannot be computed.
 */
data class PageInfo(
    val page: Int?,
    val total: Int,
    val percent: Int?,
) {
    val label: String
        get() = when {
            page != null && total > 0 -> "$page / $total"
            percent != null -> "$percent%"
            else -> ""
        }
}

sealed interface ReaderState {
    data object Loading : ReaderState
    data class Failed(val message: String) : ReaderState
    data class Ready(
        val publication: Publication,
        val navigatorFactory: EpubNavigatorFactory,
        val initialLocator: Locator?,
        val tableOfContents: List<Link>,
    ) : ReaderState
}

/**
 * Owns the open [Publication] for one book.
 *
 * A ViewModel rather than the composable: opening an epub is expensive, and a
 * rotation or a bottom sheet must not reparse the book. The publication is closed
 * in [onCleared], which is the only place that can know the screen is really gone.
 */
class ReaderViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val readium: ReadiumComponents,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    val book: StateFlow<BookEntity?> =
        repository.book(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val readerSettings: StateFlow<ReaderSettings> =
        settingsStore.readerSettings.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    /**
     * The live navigator, handed over by the Compose host once the fragment
     * exists. Held so the table of contents and the appearance controls have
     * something to talk to; cleared when the screen goes away.
     */
    private var navigator: EpubNavigatorFragment? = null

    private val _pageInfo = MutableStateFlow<PageInfo?>(null)
    val pageInfo: StateFlow<PageInfo?> = _pageInfo.asStateFlow()

    /** Total Readium positions, computed once per book; 0 until it is known. */
    private var totalPositions = 0

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val book = repository.get(bookId)
        if (book == null) {
            _state.value = ReaderState.Failed("this book is no longer in the library")
            return
        }
        val file = File(book.epubPath)
        if (!file.isFile) {
            _state.value = ReaderState.Failed("the epub is missing from storage; re-import it")
            return
        }

        readium.open(file).fold(
            onSuccess = { publication ->
                _state.value = ReaderState.Ready(
                    publication = publication,
                    navigatorFactory = EpubNavigatorFactory(publication),
                    initialLocator = book.readingLocator?.toLocator(),
                    tableOfContents = publication.tableOfContents,
                )
                // Off the critical path: computing positions walks every resource,
                // so the book opens first and the page count fills in after.
                viewModelScope.launch {
                    totalPositions = runCatching { publication.positions().size }.getOrDefault(0)
                    _pageInfo.value = _pageInfo.value?.copy(total = totalPositions)
                }
            },
            onFailure = { _state.value = ReaderState.Failed(it.message ?: "could not open this epub") },
        )
    }

    /**
     * Called once the fragment is live.
     *
     * Starts saving the reading position. Debounced, and the first value is
     * dropped: the navigator emits its starting locator immediately, and writing
     * that back would be a pointless database write on every open.
     */
    @OptIn(FlowPreview::class)
    fun onNavigatorReady(fragment: EpubNavigatorFragment) {
        if (navigator === fragment) return
        navigator = fragment
        viewModelScope.launch {
            fragment.currentLocator
                .drop(1)
                .debounce(POSITION_SAVE_DELAY_MS)
                .distinctUntilChanged()
                .collect { locator ->
                    repository.updateReadingPosition(bookId, locator.toJSON().toString())
                }
        }
        // Undebounced and separate: the page indicator should move as soon as the
        // page turns, whereas the database write above deliberately waits.
        viewModelScope.launch {
            fragment.currentLocator.collect { locator ->
                _pageInfo.value = PageInfo(
                    page = locator.locations.position,
                    total = totalPositions,
                    percent = locator.locations.totalProgression?.let { (it * 100).roundToInt() },
                )
            }
        }
    }

    fun onNavigatorGone() {
        navigator = null
    }

    fun goTo(link: Link) {
        navigator?.go(link, animated = false)
    }

    /**
     * Builds the preferences the navigator should render with.
     *
     * [ReaderSettings.PAPER] is not one of Readium's themes — it is the LIGHT theme
     * with an explicit e-ink palette. Publisher styles are switched off for it
     * because they are the one thing that can override the page colours: an epub
     * that sets its own `body { background: #fff }` would otherwise punch a white
     * hole through the grey.
     *
     * `publisherStyles` was briefly forced off for every theme, not just Paper, on
     * the theory that it was letting a book's own CSS override the app's layout.
     * Reverted: decompiling Readium's actual `ReadiumCss.injectHtml` (not guessed
     * at, read directly) turns up no reference to `publisherStyles` anywhere near
     * its stylesheet-selection logic in this version of the library -- it does not
     * gate anything there. The real cause of the vertical-margin and paragraph-
     * indent bug was different: `ReadiumCSS-default.css`, the only stylesheet
     * defining `--RS__flowSpacing`/`--RS__paraIndent` and the rules that consume
     * them, is linked only when the page has *no* CSS of its own, which is
     * essentially never true for a real epub. See `TypographyFixer.kt`, which
     * fixes that directly, and BUGS.md's BUG-12 history for the full chain.
     */
    fun preferences(settings: ReaderSettings, systemInDarkTheme: Boolean): EpubPreferences {
        val paper = settings.theme == ReaderSettings.PAPER
        return EpubPreferences(
            fontSize = settings.fontSize,
            scroll = settings.scroll,
            // Left at Readium's own default (a 1.0 multiplier on the fixed pageGutter
            // set in ReaderScreen's RsProperties) -- no longer a user preference.
            theme = when {
                paper -> Theme.LIGHT
                settings.theme != null -> settings.theme.toTheme() ?: Theme.LIGHT
                systemInDarkTheme -> Theme.DARK
                else -> Theme.LIGHT
            },
            backgroundColor = if (paper) ReadiumColor(PaperPalette.pageArgb) else null,
            textColor = if (paper) ReadiumColor(PaperPalette.inkArgb) else null,
            publisherStyles = if (paper) false else null,
        )
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch {
            settingsStore.setReaderSettings(transform(readerSettings.value))
        }
    }

    override fun onCleared() {
        navigator = null
        (_state.value as? ReaderState.Ready)?.publication?.close()
    }

    private fun String.toLocator(): Locator? =
        runCatching { Locator.fromJSON(JSONObject(this)) }.getOrNull()

    private fun String.toTheme(): Theme? = runCatching { Theme.valueOf(this) }.getOrNull()

    companion object {
        private const val POSITION_SAVE_DELAY_MS = 800L

        fun factory(container: AppContainer, bookId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReaderViewModel(bookId, container.repository, container.readium, container.settings) as T
        }
    }
}

/** Readium reports external links; the app opens them in the browser. */
class ExternalLinkOpener(private val onOpen: (AbsoluteUrl) -> Unit) : EpubNavigatorFragment.Listener {
    override fun onExternalLinkActivated(url: AbsoluteUrl) = onOpen(url)
}
