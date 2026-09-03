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

/**
 * Where you are in the current chapter, for the page indicator.
 *
 * Deliberately per-chapter, not whole-book: Readium's `PaginationListener` reports
 * the real, currently-rendered page count for whatever chapter is loaded, updated
 * live whenever the WebView re-paginates -- including on a font-size change, which
 * is the whole point (see BUGS.md). A true whole-book count is not shown because
 * getting one would mean laying out and measuring every chapter at the current
 * font size, not just the one on screen -- expensive, and would make changing text
 * size noticeably slower on a long book. `page`/[total] are both 1-based.
 */
data class PageInfo(
    val page: Int,
    val total: Int,
) {
    val label: String get() = if (total > 0) "$page / $total" else ""
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
                // Whatever was last saved, or Readium's own default (the
                // spine's first resource) for a book that has never been
                // opened. A whole run of attempts at guessing "chapter 1"
                // instead -- an alignment-vote-based firstChapterLocator,
                // then simulating a tap-and-"Read from here" on open -- each
                // broke on a real book in a new way (a stray text collision,
                // a large novel's own legitimate front-matter audio
                // outvoting its real chapter 1, a table of contents whose
                // own first entry was front matter too) and was scrapped by
                // explicit request rather than chased further: autoPlay
                // (see ReadAlongViewModel.start) already starts playback at
                // the right *audio* position on its own, independent of
                // whatever the page happens to show first, and read-along
                // follow mode (FollowController) already exists to bring the
                // page in line with the audio once it does.
                val initialLocator = book.readingLocator?.toLocator()
                _state.value = ReaderState.Ready(
                    publication = publication,
                    navigatorFactory = EpubNavigatorFactory(publication),
                    initialLocator = initialLocator,
                    tableOfContents = publication.tableOfContents,
                )
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
    }

    /**
     * Called from [EpubNavigatorFragment.PaginationListener.onPageChanged], wired
     * up where the fragment is created (`ReaderScreen.kt`) -- Readium's own report
     * of the current chapter's real, currently-rendered page count, fired again
     * whenever the WebView re-paginates. That includes a font-size change, which
     * is the whole reason this replaced the old whole-book byte-position estimate
     * (see [PageInfo], BUGS.md): this one is honest about being per-chapter, but
     * it is real and it responds to the setting that visibly changes it.
     *
     * @param pageIndex 0-based -- inferred, not documented, from decompiling
     *   `EpubNavigatorFragment`: its own internal page-change plumbing
     *   (`PageChangeListener`) extends `ViewPager.SimpleOnPageChangeListener`,
     *   whose `onPageSelected(position: Int)` is 0-based by Android convention.
     *   +1 here for a human-facing "page 3 of 7".
     */
    fun onPageChanged(pageIndex: Int, pageCount: Int) {
        _pageInfo.value = PageInfo(page = pageIndex + 1, total = pageCount)
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
