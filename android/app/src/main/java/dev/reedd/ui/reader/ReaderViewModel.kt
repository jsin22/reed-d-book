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
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.File

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

    fun onNavigatorGone() {
        navigator = null
    }

    fun goTo(link: Link) {
        navigator?.go(link, animated = false)
    }

    /** Builds the preferences the navigator should render with. */
    fun preferences(settings: ReaderSettings, systemInDarkTheme: Boolean): EpubPreferences =
        EpubPreferences(
            fontSize = settings.fontSize,
            scroll = settings.scroll,
            theme = settings.theme?.toTheme() ?: if (systemInDarkTheme) Theme.DARK else Theme.LIGHT,
        )

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
