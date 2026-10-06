package dev.reedd.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.SyncDao
import dev.reedd.data.readium.ReadiumComponents
import dev.reedd.data.settings.ReaderSettings
import dev.reedd.data.settings.SettingsStore
import dev.reedd.di.AppContainer
import dev.reedd.diagnostics.Breadcrumbs
import dev.reedd.domain.chaptersToc
import dev.reedd.domain.currentChapterTitle
import dev.reedd.ui.theme.PaperPalette
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search
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
    private val syncStore: SyncDao,
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

    /** The navigator's own current locator, `locations.totalProgression`
     *  only -- a live book's own seek bar shows this while not being
     *  dragged (`ReadAlongBar`'s `livePageProgression`), since dragging it
     *  only ever moves the page (`goToProgression`), never audio; the bar's
     *  own displayed position has to come from the same place its own
     *  seeks land, not from `ReadAlongViewModel` (which has no reference to
     *  the navigator/Publication at all, by design). */
    private val _currentProgression = MutableStateFlow<Double?>(null)
    val currentProgression: StateFlow<Double?> = _currentProgression.asStateFlow()

    /** The resource (chapter) the page is in -- the book guide's spoiler limit
     *  is the chapter the reader has reached. */
    private val _currentHref = MutableStateFlow<String?>(null)
    val currentHref: StateFlow<String?> = _currentHref.asStateFlow()

    /** The table-of-contents entry the reader is currently inside -- shown
     *  next to the page number in the page indicator. Null wherever
     *  [currentChapterTitle] itself would be (see its own doc): no usable
     *  table of contents, or reading before its first entry. */
    private val _currentChapterTitle = MutableStateFlow<String?>(null)
    val currentChapterTitle: StateFlow<String?> = _currentChapterTitle.asStateFlow()

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
        // Opening counts as reading for "Last opened"/"Reading now", even if
        // no page is turned before closing (that is all that updated it before).
        repository.markOpened(bookId)

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
                Breadcrumbs.leave("reader opened: '${book.title}' (${book.id})")
                // A book with no read-along data at all (not converted with
                // Pocket TTS, or never finished aligning) has no chapters to
                // fall back to either -- chaptersToc already degrades to the
                // real (possibly broken) table of contents in that case.
                val chapters = runCatching { syncStore.chapters(bookId) }.getOrDefault(emptyList())
                _state.value = ReaderState.Ready(
                    publication = publication,
                    navigatorFactory = EpubNavigatorFactory(publication),
                    initialLocator = initialLocator,
                    tableOfContents = chaptersToc(publication, publication.tableOfContents, chapters),
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
        viewModelScope.launch {
            fragment.currentLocator.collect { locator ->
                _currentProgression.value = locator.locations.totalProgression
                _currentHref.value = locator.href.toString()
            }
        }
        viewModelScope.launch {
            fragment.currentLocator.collect { locator ->
                val ready = _state.value as? ReaderState.Ready ?: return@collect
                _currentChapterTitle.value = currentChapterTitle(
                    readingOrder = ready.publication.readingOrder,
                    tableOfContents = ready.tableOfContents,
                    resourceHref = locator.href.toString(),
                )
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

    /** "Go here" from a search result: unlike [goTo] (a [Link], from the
     *  table of contents) this is a full [Locator] -- a search hit's own
     *  exact spot, not just a resource's start. Not `animated = false` like
     *  [goTo]: a search result can land far from wherever the reader
     *  currently is, and the jump reads better as a page turn than an
     *  instant cut -- same reasoning [NotesSheet]'s own "go to this spot"
     *  already applies. */
    fun goTo(locator: Locator) {
        navigator?.go(locator, animated = true)
    }

    /** Readium's own canonical, book-wide position list -- see
     *  [goToProgression]'s own doc. Cached per open publication: it is a
     *  real (if lightweight -- per-resource byte length, not a render)
     *  computation, and the seek bar can ask for it on every drag release. */
    private var bookPositions: List<Locator>? = null

    /**
     * A live book's own seek bar: [overallFraction] (0..1) of [bookPositions]'
     * own size picks a target locator directly -- Readium's own "total
     * number of pages" concept (`Publication.positions()`, one entry per
     * roughly-fixed-size chunk of the whole book, independent of screen
     * size or font), the same thing an ebook reader's page count ordinarily
     * means. Replaced an earlier attempt that estimated a target resource
     * by its own text length (`dev.reedd.domain.BookProgression`) -- that
     * measured a *different* quantity than what a reader means by "page",
     * and produced visibly wrong jumps; this is what the reader actually
     * asked for: take the total page count, multiply by the seek
     * percentage, go to that page.
     *
     * Deliberately just moves the page -- no [dev.reedd.domain.
     * LiveChunkSource], no server call, nothing about audio at all. Only an
     * explicit "Read from here" tap (`ReadAlongViewModel.
     * readFromTappedWord`) ever asks the server to synthesize something new;
     * dragging this bar is page navigation, exactly like tapping a table of
     * contents entry, and must stay that cheap and that safe to do freely.
     */
    fun goToProgression(overallFraction: Double) {
        viewModelScope.launch {
            val ready = _state.value as? ReaderState.Ready ?: return@launch
            val pages = bookPositions ?: runCatching { ready.publication.positions() }.getOrNull()
                ?.also { bookPositions = it } ?: return@launch
            if (pages.isEmpty()) return@launch
            val targetIndex = (overallFraction.coerceIn(0.0, 1.0) * (pages.size - 1)).roundToInt()
                .coerceIn(0, pages.size - 1)
            navigator?.go(pages[targetIndex], animated = false)
        }
    }

    /** In-book full-text search. [Locator] is used directly as the result
     *  type -- it already carries everything a results list needs (the
     *  containing chapter's [Locator.title], [Locator.Text.before]/
     *  [Locator.Text.highlight]/[Locator.Text.after] for a snippet, and
     *  [Locator.locations]' href/progression for both [goTo] and
     *  [ReadAlongViewModel.playFromSearchResult]) -- a wrapper type would
     *  just be repeating fields Readium already named well. */
    sealed interface SearchUiState {
        data object Idle : SearchUiState
        data object Searching : SearchUiState
        data class Results(val hits: List<Locator>, val truncated: Boolean) : SearchUiState
        data class Failed(val message: String) : SearchUiState
    }

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Debounced so typing a whole query does not re-search on every
     * keystroke; each call cancels whatever search is still running for the
     * previous one, since only the latest query's results are ever worth
     * finishing.
     *
     * Drains readium-shared's own [SearchIterator] in a loop rather than
     * exposing paging in this app's own UI: its default
     * [dev.reedd.data.readium.ReadiumComponents]-configured implementation
     * yields one resource's matches per [SearchIterator.next] call, not a
     * fixed-size page, so "load the next page" would mean something
     * different depending on how long each chapter happens to be --
     * looping here and capping the *total* hit count instead
     * ([MAX_SEARCH_HITS]) is a simpler contract for a novel-sized book.
     */
    fun search(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _searchState.value = SearchUiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val ready = _state.value as? ReaderState.Ready ?: return@launch
            _searchState.value = SearchUiState.Searching
            val result = runCatching {
                // Null specifically means this publication has no search
                // service at all -- shouldn't happen for anything this app
                // opens (ReadiumComponents attaches one to every
                // publication), but a plain empty result reads better than
                // a crash if a future publication source ever skips it.
                val iterator = ready.publication.search(trimmed) ?: return@runCatching emptyList<Locator>() to false
                val hits = mutableListOf<Locator>()
                var truncated = false
                while (hits.size < MAX_SEARCH_HITS) {
                    val page = iterator.next().getOrNull()?.locators ?: break
                    if (page.isEmpty()) break
                    hits += page
                }
                iterator.close()
                if (hits.size > MAX_SEARCH_HITS) {
                    truncated = true
                }
                hits.take(MAX_SEARCH_HITS) to truncated
            }
            _searchState.value = result.fold(
                onSuccess = { (hits, truncated) -> SearchUiState.Results(hits, truncated) },
                onFailure = { SearchUiState.Failed(it.message ?: "search failed") },
            )
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchState.value = SearchUiState.Idle
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
            // Always paginated: continuous-scroll mode was removed.
            scroll = false,
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
        val before = readerSettings.value
        val after = transform(before)
        if (after != before) {
            Breadcrumbs.leave(
                "reader settings changed: " +
                    "fontSize ${before.fontSize}->${after.fontSize} theme ${before.theme}->${after.theme}",
            )
        }
        viewModelScope.launch {
            settingsStore.setReaderSettings(after)
        }
    }

    override fun onCleared() {
        navigator = null
        searchJob?.cancel()
        (_state.value as? ReaderState.Ready)?.publication?.close()
    }

    private fun String.toLocator(): Locator? =
        runCatching { Locator.fromJSON(JSONObject(this)) }.getOrNull()

    private fun String.toTheme(): Theme? = runCatching { Theme.valueOf(this) }.getOrNull()

    companion object {
        private const val POSITION_SAVE_DELAY_MS = 800L
        private const val SEARCH_DEBOUNCE_MS = 300L

        /** A caller of the search sheet is a person reading a list, not a
         *  program consuming an index -- a common word in a long novel can
         *  otherwise return thousands of hits with no ceiling. */
        private const val MAX_SEARCH_HITS = 200

        fun factory(container: AppContainer, bookId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReaderViewModel(
                    bookId, container.repository, container.readium, container.settings, container.syncStore,
                ) as T
        }
    }
}

/** Readium reports external links; the app opens them in the browser. */
class ExternalLinkOpener(private val onOpen: (AbsoluteUrl) -> Unit) : EpubNavigatorFragment.Listener {
    override fun onExternalLinkActivated(url: AbsoluteUrl) = onOpen(url)
}
