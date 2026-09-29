package dev.reedd.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.reedd.data.BookRepository
import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.BookmarkType
import dev.reedd.data.dictionary.Dictionary
import dev.reedd.data.local.BookFiles
import dev.reedd.data.remote.ApiProvider
import dev.reedd.di.AppContainer
import dev.reedd.diagnostics.Breadcrumbs
import dev.reedd.diagnostics.CrashReporter
import dev.reedd.diagnostics.FeedbackReporter
import dev.reedd.domain.ChunkIndex
import dev.reedd.domain.FollowController
import dev.reedd.domain.LiveChunkSource
import dev.reedd.domain.ReadAlongAligner
import dev.reedd.playback.PlayerConnection
import dev.reedd.playback.PlayerState
import dev.reedd.ui.library.ConversionOptions
import dev.reedd.ui.library.LibraryViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** A note's passage, waiting to be highlighted once [resourceHref] is the
 *  resource actually loaded in the navigator -- see [ReadAlongViewModel.
 *  pendingHighlight]'s own docstring. [type] is the note/bookmark's own
 *  color -- resolved to an actual [androidx.compose.ui.graphics.Color] by
 *  `ReaderScreen` (the UI layer), not here. */
data class PendingHighlight(
    val resourceHref: String,
    val text: String,
    val before: String,
    val after: String,
    val type: BookmarkType,
)

/** A definition being shown, or being looked up. */
data class DefinitionState(
    val word: String,
    val loading: Boolean = false,
    val definition: dev.reedd.data.dictionary.Definition? = null,
    val notFound: Boolean = false,
)

/** What the read-along UI renders, and what the reader screen highlights from. */
data class ReadAlongState(
    val available: Boolean = false,
    val player: PlayerState = PlayerState(),
    /** Index into [ChunkIndex], or -1 before the first sentence. */
    val currentIndex: Int = -1,
    val following: Boolean = true,
    val alignedChunks: Int = 0,
    val totalChunks: Int = 0,
    val syncOffsetMs: Long = 0,
    val aligning: Boolean = false,
    /** True for a live-only book -- see [dev.reedd.domain.LiveChunkSource].
     *  Branches the transport bar's seek bar between the file path's
     *  ms-based duration/position and the book-wide progression
     *  `ReaderScreen` feeds it from the navigator's own current locator
     *  (`ReadAlongBar`'s `livePageProgression` param) -- not tracked here,
     *  since this class deliberately has no reference to the open
     *  `Publication`/navigator (see its own class doc). */
    val isLive: Boolean = false,
)

/**
 * Drives read-along: playback, the position poll, and which sentence is current.
 *
 * Kept apart from [ReaderViewModel], which owns the open publication, because the
 * two have different lifetimes and different reasons to change. This one is about
 * the audio; that one is about the book.
 *
 * The position poll is the loop the plan calls for. It reads an in-memory field
 * from the player and does a binary search over an in-memory [ChunkIndex], so the
 * per-tick cost is negligible; nothing is written to the database except a
 * throttled bookmark of the playback position.
 */
class ReadAlongViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val aligner: ReadAlongAligner,
    private val dictionary: Dictionary,
    val player: PlayerConnection,
    /** Start audio immediately once loaded -- the library's play button, not
     *  every way of reaching the reader. See `ReeddNavHost.kt`'s
     *  `ReaderRoute.autoPlay` for where this comes from. */
    private val autoPlay: Boolean = false,
    /** Only used for a `canReadLive` book (see [LiveChunkSource]) -- kept
     *  out of the constructor's required list since most books never need
     *  them, and both are cheap to omit for tests that don't exercise live
     *  reading at all. */
    private val api: ApiProvider? = null,
    private val files: BookFiles? = null,
    /** The open publication's own reading order, href per resource, in
     *  order -- suspends until the publication (owned by `ReaderViewModel`,
     *  a separate class with its own async open()) is actually ready,
     *  rather than racing it. Supplied by `ReeddNavHost.kt`, which
     *  constructs both ViewModels together and so is the one place that
     *  can bridge them without either depending on the other's type. Kept
     *  as plain hrefs, not a `Publication`, so this class -- like
     *  `ChunkAligner`/`LiveChunkSource` -- never needs a Readium
     *  dependency for live reading's own purposes. */
    private val readingOrderHrefs: suspend () -> List<String> = { emptyList() },
    /** Only used to route [LiveChunkSource]'s own diagnostic logging to
     *  `CrashReporter.reportDiagnostic` -- the server console is the only
     *  place a tester without adb/device access can see what happened,
     *  same channel `ReeddFollowDisengage`/`ReeddDictionary` already use. */
    private val appContext: android.content.Context? = null,
) : ViewModel() {

    /** Routes to `CrashReporter.reportDiagnostic` -- the server console/
     *  crash-file store is the only place a tester without adb access can
     *  see what actually happened, same channel [buildLiveChunkSource]'s
     *  own `log` callback already uses. Added specifically to trace two
     *  real, reported bugs with no visible error: switching between live
     *  and offline mode "doesn't seem to work," and picking a new live
     *  voice not actually changing it -- both [switchToLive]/
     *  [switchToOffline]/[setVoice] have several silent early-return
     *  points that previously gave no signal at all when one was hit. */
    private fun logModeSwitch(message: String) {
        appContext?.let { CrashReporter.reportDiagnostic(it, "ReeddModeSwitch", message) }
    }

    private val follower = FollowController(following = true)

    private val _state = MutableStateFlow(ReadAlongState())
    val state: StateFlow<ReadAlongState> = _state.asStateFlow()

    /**
     * Emits the sentence index the page should move to.
     *
     * A separate signal from [state] on purpose: navigating is an *event*: replaying
     * it because something else in the state changed would yank the page back.
     */
    private val _navigateTo = MutableStateFlow<Int?>(null)
    val navigateTo: StateFlow<Int?> = _navigateTo.asStateFlow()

    /**
     * Word-tap and drag-selection interaction -- armed handles, their live
     * position, and the resulting [WordMenuTarget] -- pulled out into its
     * own state machine; see [WordSelectionController]'s own doc. This
     * ViewModel's own [tappedWord]/[selectionHandles]/[isHandleDragActive]/
     * [onWordTapped]/[armHandles]/[onHandleDragStart]/[onHandleMoved]/
     * [onExtendResolved]/[onHandleDragEnd] are thin delegates to it, kept
     * here so nothing above this class -- `ReaderScreen` included -- had to
     * change.
     */
    private val selection = WordSelectionController(chunkIndex = ::chunkIndex, isLive = { isLive })

    /**
     * The word the reader tapped, if its menu is open.
     *
     * Separate from [state] because it is not a property of playback: it is a
     * transient selection that any subsequent tap replaces.
     */
    val tappedWord: StateFlow<WordMenuTarget?> = selection.tappedWord

    private val _definition = MutableStateFlow<DefinitionState?>(null)
    val definition: StateFlow<DefinitionState?> = _definition.asStateFlow()

    /**
     * The tap/selection a note/bookmark is being written for, once the
     * reader has picked "Bookmark" off the menu -- separate from
     * [tappedWord] so choosing it can close the menu (clearing [tappedWord],
     * which is what makes the popup and its highlight/selection cleanup
     * disappear) without also losing what the note is about.
     */
    private val _pendingNoteTarget = MutableStateFlow<WordMenuTarget?>(null)
    val pendingNoteTarget: StateFlow<WordMenuTarget?> = _pendingNoteTarget.asStateFlow()

    /**
     * A passage to highlight (display-only, no handles/menu) once its
     * resource has loaded, requested by [NotesSheet]'s "go to this spot"
     * button. `ReaderScreen`'s `EpubNavigator` watches this and
     * `fragment.currentLocator`'s href together: `fragment.go(locator)` can
     * land on a resource already open (no reload, highlight straight away)
     * or one that still needs to load its HTML document first (wait for the
     * href to match before searching it), and this alone can't tell which.
     * Cleared once painted, by [clearPendingHighlight].
     */
    private val _pendingHighlight = MutableStateFlow<PendingHighlight?>(null)
    val pendingHighlight: StateFlow<PendingHighlight?> = _pendingHighlight.asStateFlow()

    fun requestHighlight(resourceHref: String, text: String, before: String, after: String, type: BookmarkType) {
        _pendingHighlight.value = PendingHighlight(resourceHref, text, before, after, type)
    }

    fun clearPendingHighlight() {
        _pendingHighlight.value = null
    }

    /** See [selection]'s own doc. */
    val selectionHandles: StateFlow<SelectionHandles?> = selection.selectionHandles

    /** See [selection]'s own doc. */
    val isHandleDragActive: Boolean get() = selection.isHandleDragActive

    private var index: ChunkIndex = ChunkIndex.EMPTY
    private var lastSavedPositionMs = 0L
    private var lastSavedLiveIndex = -1

    /** True once [start] decides this book has no downloaded audio but can
     *  be read live (`book.canReadLive`) -- see CPU_LIVE_READING_PLAN Phase
     *  4. Branches [pollPosition]'s position source and [playFrom]'s seek
     *  mechanism; everything else (`FollowController`, `ReadAlongLocators`,
     *  word-tap/selection resolution) is unchanged, shared code. */
    private var isLive = false
    private var liveChunkSource: LiveChunkSource? = null

    /** A busy/error message from the live-reading server, surfaced once and
     *  cleared -- see [dev.reedd.domain.LiveChunkSource.message]. Null the
     *  rest of the time, including for every non-live book. */
    private val _liveMessage = MutableStateFlow<String?>(null)
    val liveMessage: StateFlow<String?> = _liveMessage.asStateFlow()

    fun clearLiveMessage() {
        _liveMessage.value = null
    }

    /** The engine/voice catalog, for a live book's own voice picker (the
     *  reader's Appearance sheet) -- same shape and fetch [ConversionOptions]
     *  already uses for the upload-time picker, reloaded here rather than
     *  shared: this ViewModel and the library sheet have different
     *  lifetimes, and the catalog is cheap enough that duplicating one
     *  small `GET /api/engines` call is simpler than plumbing a shared
     *  cache across screens for it. */
    private val _voiceOptions = MutableStateFlow(ConversionOptions())
    val voiceOptions: StateFlow<ConversionOptions> = _voiceOptions.asStateFlow()

    fun loadVoiceOptions() {
        val api = api ?: return
        if (_voiceOptions.value.engines.isNotEmpty() || _voiceOptions.value.loading) return
        viewModelScope.launch {
            _voiceOptions.value = ConversionOptions(loading = true)
            _voiceOptions.value = try {
                ConversionOptions(engines = api.service().engines().engines)
            } catch (e: IOException) {
                ConversionOptions(error = LibraryViewModel.describe(e))
            }
        }
    }

    /**
     * Persists a live book's own voice for future live sessions
     * ([buildLiveChunkSource] reads [BookEntity.liveVoice] fresh from the
     * database every time it runs) *and*, if a live session is already
     * open right now, re-synthesizes from the currently-playing sentence in
     * the new voice immediately -- "resend the current sentence with the
     * new voice," per the reader's own framing, rather than making them
     * close and reopen the book for a voice change to take effect.
     *
     * Writes [BookEntity.liveVoice], not [BookEntity.voice] -- see that
     * column's own doc for why the two used to be the same field and why
     * that was a real bug (this picker silently changing what a future
     * offline conversion would request).
     */
    fun setVoice(voice: String) {
        viewModelScope.launch {
            logModeSwitch(
                "setVoice: voice=$voice isLive=$isLive currentIndex=${_state.value.currentIndex} " +
                    "hasLiveChunkSource=${liveChunkSource != null}"
            )
            repository.updateLiveVoice(bookId, voice)
            if (!isLive) {
                logModeSwitch("setVoice: not live -- persisted only, takes effect on next open")
                return@launch
            }
            val source = liveChunkSource ?: run {
                logModeSwitch("setVoice: isLive=true but liveChunkSource is null, aborting")
                return@launch
            }
            val globalIndex = _state.value.currentIndex
            val (href, sentenceIndex) = if (globalIndex >= 0) {
                source.resumePointFor(globalIndex) ?: run {
                    logModeSwitch("setVoice: resumePointFor($globalIndex) returned null, aborting")
                    return@launch
                }
            } else {
                // nothing has actually started playing yet -- next startLive already picks up the new voice
                logModeSwitch("setVoice: currentIndex=$globalIndex, nothing played yet, aborting")
                return@launch
            }
            val wasPlaying = player.state.value.isPlaying
            logModeSwitch("setVoice: calling changeVoice(voice=$voice, href=$href, sentenceIndex=$sentenceIndex) wasPlaying=$wasPlaying")
            source.changeVoice(voice, href, sentenceIndex)
            if (wasPlaying) player.play()
        }
    }

    /**
     * Persists which of the two a `LIVE_OFFLINE` book (once both are
     * actually available) opens as next time -- see [BookEntity.
     * preferLiveReading]'s own doc -- *and*, if this reader is open right
     * now, switches the session itself over immediately (see
     * [switchToLive]/[switchToOffline]), same "don't make the reader
     * reopen the book" fix already applied to [setVoice]. Confirmed live as
     * the identical gap, reported the same way: "if I switch from offline
     * to live in the settings... I have to exit the book and reopen before
     * it starts playing the live version."
     */
    fun setPreferLiveReading(preferLive: Boolean) {
        viewModelScope.launch {
            logModeSwitch("setPreferLiveReading: preferLive=$preferLive currentIsLive=$isLive")
            repository.updatePreferLiveReading(bookId, preferLive)
            val book = repository.get(bookId) ?: run {
                logModeSwitch("setPreferLiveReading: repository.get($bookId) returned null, aborting")
                return@launch
            }
            if (preferLive) switchToLive(book) else switchToOffline(book)
        }
    }

    /** [bookContext] (e.g. "Five Survive (live mode)") is built by the
     *  caller ([ReaderScreen], which already has the book's title and
     *  [isLive]) rather than here, so this stays a thin pass-through --
     *  see [FeedbackReporter.submit]'s own doc for why it's attached at
     *  all. Null [api] (this ViewModel built without live-reading
     *  dependencies) fails the submission rather than silently dropping
     *  the book context. */
    suspend fun submitFeedback(type: FeedbackReporter.Type, message: String, bookContext: String?): Boolean {
        val api = api ?: return false
        return runCatching { FeedbackReporter.submit(api, type, message, bookContext) }.isSuccess
    }

    /**
     * True until [pollPosition] has processed one tick.
     *
     * `player.currentPositionMs()` right after `prepare()` cannot be trusted the
     * instant this ViewModel is created: [player] is shared app-wide and its
     * `MediaController` talks to the session asynchronously, so a poll tick that
     * lands before that round trip completes can read a stale position -- often
     * whatever a *previously* open book left behind. Treating that as a genuine
     * sentence change would fight the page [dev.reedd.ui.reader.ReaderViewModel]
     * already opened at (its own saved locator), which is exactly BUGS.md BUG-10's
     * successor: the reader "waking up at the beginning" despite a saved position
     * elsewhere. The first tick only establishes the baseline; navigation is
     * trusted from the second tick on, by which point the read has settled in
     * every case that matters here.
     */
    private var firstTick = true

    fun chunkIndex(): ChunkIndex = index

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        val book = repository.get(bookId) ?: return
        // book.preferLiveReading only matters when a LIVE_OFFLINE book has
        // actually reached the point where both are real choices -- for
        // every other book (only one of these two is ever true) the
        // preference is irrelevant, and the existing isPlayable-first
        // precedence (unchanged from before this setting existed) decides.
        when {
            book.preferLiveReading && book.canReadLive -> startLive(book)
            book.isPlayable -> startFile(book)
            book.canReadLive -> startLive(book)
            else -> return
        }
        pollPosition()
    }

    private suspend fun startFile(book: BookEntity) {
        loadIndex(book)
        if (book.needsAlignment) realign(book)

        // The saved position is wherever playback last happened to be paused
        // -- almost never a sentence's own start -- but the sentence it falls
        // inside gets highlighted whole either way, so resuming from the raw
        // saved value played from *inside* the highlighted sentence rather
        // than its beginning. playFrom()/seekPositionFor already snap a
        // deliberate "read from here" or a next/previous-sentence tap to the
        // sentence's own start; this makes the very first resume do the same
        // rather than being the one path that does not.
        val resumeStartMs = index.indexAt(book.playbackPositionMs).takeIf { it >= 0 }
            ?.let { index.seekPositionFor(it) }
            ?: book.playbackPositionMs

        player.connect()
        player.prepare(
            bookId = bookId,
            audiobook = File(book.audiobookPath!!),
            title = book.title,
            author = book.author,
            coverPath = book.coverPath,
            startMs = resumeStartMs,
        )
        // The library's play button, not every way of reaching the reader --
        // see this class's own constructor doc. prepare() only loads/buffers;
        // without this, the reader opened paused regardless of how it was
        // reached, requiring an extra manual tap on the transport controls
        // every time, first sentence or a hundredth chapter in alike.
        if (autoPlay) player.play()

        // Seed from the position just handed to the player, not a live read of
        // it, for the same reason `firstTick` exists: this value is known good,
        // a poll tick's read of the player this soon after `prepare()` might not
        // be yet.
        //
        // Requests the page actually move here (_navigateTo, the same thing
        // every other navigation goes through, picked up once ReaderScreen's
        // navigator is ready even if it is not yet), rather than the silent
        // record-only `follower.onNavigated` this used to call directly: the
        // fragment opens at `book.readingLocator` (ReaderViewModel.open), a
        // separate saved position from this one (`book.playbackPositionMs`),
        // and the two drifting apart is not the edge case it once was --
        // confirmed live as "the book opens on the wrong page and stays
        // there until the crosshair is toggled off and on, or until the
        // audio reaches the next sentence" (autoplay only). Both of those
        // "fixes" were really just the only two paths that ever actually
        // called fragment.go(); this makes every open one too, immediately.
        index.indexAt(resumeStartMs).takeIf { it >= 0 }?.let { seedIndex ->
            _state.value = _state.value.copy(currentIndex = seedIndex)
            _navigateTo.value = seedIndex
        }

        _state.value = _state.value.copy(available = true)
    }

    /**
     * The live-reading equivalent of [startFile] -- see
     * CPU_LIVE_READING_PLAN Phase 4 and [LiveChunkSource]'s own doc for the
     * design. Resumes from `book.liveResourceHref`/`liveSentenceIndex` if
     * this book was read live before and that resource is still in the
     * publication's reading order (an edited/re-uploaded epub could
     * plausibly have dropped it -- falling back to the very first resource
     * rather than a resolveable-but-wrong one is the safer default).
     */
    private suspend fun startLive(book: BookEntity) {
        val hrefs = readingOrderHrefs()
        if (hrefs.isEmpty()) return
        val source = buildLiveChunkSource(book, hrefs) ?: return
        isLive = true
        _state.value = _state.value.copy(isLive = true)
        wireLiveChunkSource(source)

        val startHref = book.liveResourceHref?.takeIf { it in hrefs } ?: hrefs.first()
        val startSentence = if (book.liveResourceHref == startHref) book.liveSentenceIndex else 0

        player.connect()
        source.startFrom(startHref, startSentence)
        // See startFile's own identical call for why: without this the
        // reader opens paused regardless of how it was reached.
        if (autoPlay) player.play()
    }

    /** The construction [startLive] and [switchToLive] both need -- null if
     *  this book can't build one at all (no jobId, or this ViewModel was
     *  built without live-reading dependencies -- see the constructor's own
     *  doc), same guards [startLive] used to inline itself. */
    private fun buildLiveChunkSource(book: BookEntity, hrefs: List<String>): LiveChunkSource? {
        val api = api ?: return null
        val files = files ?: return null
        val jobId = book.jobId ?: return null
        return LiveChunkSource(
            bookId = bookId,
            jobId = jobId,
            epub = File(book.epubPath),
            // liveVoice first (an explicit pick), then voice (the offline
            // conversion's own choice -- a reasonable starting guess if the
            // reader has never picked a separate live one), then a
            // hardcoded default.
            voice = book.liveVoice ?: book.voice ?: DEFAULT_LIVE_VOICE,
            title = book.title,
            author = book.author,
            coverPath = book.coverPath,
            api = api,
            player = player,
            files = files,
            nextResourceHref = { current -> hrefs.getOrNull(hrefs.indexOf(current) + 1) },
            log = { message -> appContext?.let { dev.reedd.diagnostics.CrashReporter.reportDiagnostic(it, "ReeddReadFromHere", message) } },
        )
    }

    /** Wires a freshly-built [LiveChunkSource] into this ViewModel's own
     *  state -- shared by [startLive] (a fresh session) and [switchToLive]
     *  (mid-session, switching away from the downloaded audiobook). */
    private fun wireLiveChunkSource(source: LiveChunkSource) {
        liveChunkSource = source
        viewModelScope.launch {
            source.chunkIndexFlow.collect { newIndex ->
                val wasEmpty = index.isEmpty
                index = newIndex
                _state.value = _state.value.copy(
                    totalChunks = newIndex.size,
                    alignedChunks = newIndex.chunks.count { it.isAligned },
                )
                // The very first chunk to ever arrive after a fresh
                // startFrom/startFromFraction is always global index 0
                // (ordinal counting resets there) -- seeding the page to it
                // the same way startFile seeds its own resumeStartMs. Also
                // what makes switchToLive's own restart land on the right
                // page, not wherever the just-discarded offline index left
                // off: [index] is reset to empty there before this fires.
                if (wasEmpty && !newIndex.isEmpty) {
                    _state.value = _state.value.copy(currentIndex = 0, available = true)
                    _navigateTo.value = 0
                }
            }
        }
        viewModelScope.launch {
            source.message.collect { message ->
                if (message != null) {
                    _liveMessage.value = message
                    source.clearMessage()
                }
            }
        }
    }

    /**
     * Switching a `LIVE_OFFLINE` book from Offline to Live *right now*,
     * from the reader's own settings sheet -- not just for the next open
     * (see [setPreferLiveReading]). Resumes from wherever offline playback
     * currently is: the current chunk's own `resourceHref`/`progression`
     * (already precisely known, from real alignment against the epub --
     * not a tap's own approximate position) plus its own text as an
     * `anchor_text`, the same exact-match mechanism "read from here" on
     * unaligned live text already uses (see [LiveChunkSource.
     * startFromFraction]/`app.live_reading._resolve_anchor`). Silently does
     * nothing beyond persisting the preference if the current chunk was
     * never aligned (nothing precise to resume from) -- the next open
     * already picks up the new preference regardless.
     */
    private suspend fun switchToLive(book: BookEntity) {
        logModeSwitch(
            "switchToLive: isLive=$isLive currentIndex=${_state.value.currentIndex} indexSize=${index.size} " +
                "jobId=${book.jobId} canReadLive=${book.canReadLive}"
        )
        if (isLive) {
            logModeSwitch("switchToLive: already live, nothing to do")
            return
        }
        val currentChunk = index.chunkAtIndex(_state.value.currentIndex) ?: run {
            logModeSwitch("switchToLive: no chunk at currentIndex=${_state.value.currentIndex} (index empty or out of range), aborting")
            return
        }
        val href = currentChunk.resourceHref ?: run {
            logModeSwitch("switchToLive: chunk ordinal=${currentChunk.ordinal} has no resourceHref (never aligned), aborting")
            return
        }
        val progression = currentChunk.progression ?: run {
            logModeSwitch("switchToLive: chunk resourceHref=$href has no progression, aborting")
            return
        }
        val anchorText = currentChunk.textHighlight ?: currentChunk.text
        val hrefs = readingOrderHrefs()
        if (hrefs.isEmpty()) {
            logModeSwitch("switchToLive: readingOrderHrefs() is empty, aborting")
            return
        }
        val source = buildLiveChunkSource(book, hrefs) ?: run {
            logModeSwitch("switchToLive: buildLiveChunkSource returned null (missing api/files/jobId), aborting")
            return
        }
        val wasPlaying = player.state.value.isPlaying

        isLive = true
        _state.value = _state.value.copy(isLive = true)
        index = ChunkIndex.EMPTY
        wireLiveChunkSource(source)

        player.connect()
        logModeSwitch(
            "switchToLive: starting live session at href=$href progression=$progression " +
                "anchorText=\"$anchorText\" wasPlaying=$wasPlaying"
        )
        source.startFromFraction(href, progression, anchorText = anchorText)
        if (wasPlaying) player.play()
    }

    /**
     * The reverse of [switchToLive]: back to the downloaded audiobook,
     * right now. Resumes from wherever live playback currently is by
     * searching the *offline* mapping for the same sentence text
     * ([ChunkIndex.indexOfSelection], the same fuzzy match "read from
     * here" on a drag-selection already uses) -- both mappings are built
     * from the same epub by the same [dev.reedd.data.align.ChunkAligner],
     * so the current live sentence's own text is expected to appear
     * close to verbatim in the offline one too. Falls back to this
     * resource's own first sentence if nothing matches (a chunk that
     * never aligned live, or real wording drift between the two
     * syntheses); does nothing beyond persisting the preference if even
     * that fails, or if the book is not actually [BookEntity.isPlayable]
     * yet -- the reader settings sheet already disables this toggle until
     * then, so that should not be reachable in practice.
     */
    private suspend fun switchToOffline(book: BookEntity) {
        logModeSwitch(
            "switchToOffline: isLive=$isLive audiobookPath=${book.audiobookPath} isPlayable=${book.isPlayable} " +
                "currentIndex=${_state.value.currentIndex}"
        )
        if (!isLive) {
            logModeSwitch("switchToOffline: not live, nothing to do")
            return
        }
        val audiobookPath = book.audiobookPath ?: run {
            logModeSwitch("switchToOffline: book.audiobookPath is null, aborting")
            return
        }
        if (!book.isPlayable) {
            logModeSwitch("switchToOffline: book.isPlayable is false, aborting")
            return
        }
        val liveChunk = index.chunkAtIndex(_state.value.currentIndex)
        logModeSwitch(
            "switchToOffline: liveChunk at ${_state.value.currentIndex} -> resourceHref=${liveChunk?.resourceHref} " +
                "text=\"${liveChunk?.textHighlight ?: liveChunk?.text}\""
        )
        val wasPlaying = player.state.value.isPlaying

        liveChunkSource?.stop()
        liveChunkSource = null
        isLive = false
        _state.value = _state.value.copy(isLive = false)

        loadIndex(book)
        if (book.needsAlignment) realign(book)

        val matchIndex: Int? = liveChunk?.resourceHref?.let { href ->
            index.indexOfSelection(href, liveChunk.textHighlight ?: liveChunk.text)
                ?: index.chunks.indexOfFirst { it.resourceHref == href }.takeIf { it >= 0 }
        }
        logModeSwitch("switchToOffline: matchIndex=$matchIndex (offline index has ${index.size} chunks)")
        // Falls back to the last confirmed *offline* position (from before
        // this book was ever switched to live), not 0 -- a closer guess
        // than the very start of the book when nothing in the live
        // session's current sentence matched at all.
        val resumeMs = matchIndex?.let { index.seekPositionFor(it) } ?: book.playbackPositionMs
        logModeSwitch("switchToOffline: resumeMs=$resumeMs wasPlaying=$wasPlaying")

        player.clearQueue() // see PlayerConnection.prepare's own doc for why this matters here specifically
        player.prepare(
            bookId = bookId,
            audiobook = File(audiobookPath),
            title = book.title,
            author = book.author,
            coverPath = book.coverPath,
            startMs = resumeMs,
        )
        if (wasPlaying) player.play()
        // Re-derived from resumeMs, not matchIndex directly (same pattern
        // startFile's own resumeStartMs uses) -- so the highlighted
        // sentence is always in sync with whatever index was actually
        // seeked to, matchIndex found or not.
        index.indexAt(resumeMs).takeIf { it >= 0 }?.let { seedIndex ->
            _state.value = _state.value.copy(currentIndex = seedIndex, available = true)
            _navigateTo.value = seedIndex
        }
    }

    private suspend fun loadIndex(book: BookEntity) {
        val chunks = repository.syncChunks(bookId)
        index = ChunkIndex(chunks, offsetMs = book.syncOffsetMs)
        _state.value = _state.value.copy(
            alignedChunks = chunks.count { it.isAligned },
            totalChunks = chunks.size,
            syncOffsetMs = book.syncOffsetMs,
        )
    }

    /**
     * Align a book that has none, e.g. one downloaded before the aligner existed.
     *
     * Silent and best-effort: playback works either way, so a failure here must not
     * stop the audio starting.
     */
    private suspend fun realign(book: BookEntity) {
        _state.value = _state.value.copy(aligning = true)
        val result = runCatching { aligner.alignExisting(bookId, File(book.epubPath)) }.getOrNull()
        if (result != null) {
            repository.setAlignment(bookId, result.aligned, result.total)
            loadIndex(book)
        }
        _state.value = _state.value.copy(aligning = false)
    }

    /**
     * The poll loop.
     *
     * Ticks fast while playing and slowly while paused -- a paused player still
     * moves when the user scrubs, so the highlight has to keep up, but there is no
     * reason to wake up ten times a second to watch a position that is not changing.
     */
    private suspend fun pollPosition() {
        while (viewModelScope.isActive) {
            val playerState = player.state.value
            val position = currentGlobalPositionMs()
            val nextIndex = index.indexAt(position)

            if (firstTick) {
                // See the KDoc on firstTick: this read is not trusted for
                // navigation, only start()'s seeded baseline is, until the
                // player's own state has had one tick to settle.
                firstTick = false
            } else if (nextIndex != _state.value.currentIndex) {
                _state.value = _state.value.copy(currentIndex = nextIndex)
                if (follower.onSentenceChanged(nextIndex)) {
                    _navigateTo.value = nextIndex
                }
                if (isLive && nextIndex >= 0) liveChunkSource?.advance(nextIndex)
            }
            player.refresh()
            _state.value = _state.value.copy(player = player.state.value, following = follower.isFollowing)

            if (playerState.isPlaying) {
                if (isLive) saveLiveProgress(nextIndex) else saveProgress(position)
            }
            delay(if (playerState.isPlaying) PLAYING_TICK_MS else IDLE_TICK_MS)
        }
    }

    /**
     * The "global," cross-chapter position `ChunkIndex` expects. Media3's
     * own `currentPosition` is *window-relative* -- it resets per queue
     * item for a live queue, unlike a plain file's single continuous
     * timeline -- so a live position adds back whichever item is current's
     * own recorded start. See `LiveChunkSource`'s own doc for why that
     * start is trustworthy the instant a chunk is queued (computed from the
     * server's own measured duration, not re-derived from the player).
     */
    private fun currentGlobalPositionMs(): Long {
        if (!isLive) return player.currentPositionMs()
        val itemStart = index.chunkAtIndex(player.currentMediaItemIndex())?.startMs ?: 0L
        return itemStart + player.currentPositionMs()
    }

    /** Bookmark the position, but not on every tick: once every few seconds is plenty. */
    private suspend fun saveProgress(positionMs: Long) {
        if (kotlin.math.abs(positionMs - lastSavedPositionMs) < SAVE_INTERVAL_MS) return
        lastSavedPositionMs = positionMs
        repository.updatePlaybackPosition(bookId, positionMs)
    }

    /** [saveProgress]'s live-mode counterpart -- a resource + sentence
     *  index, not a millisecond position (see `BookEntity.liveResourceHref`/
     *  `liveSentenceIndex`'s own doc for why). */
    private suspend fun saveLiveProgress(globalIndex: Int) {
        if (globalIndex < 0 || globalIndex == lastSavedLiveIndex) return
        val (href, sentenceIndex) = liveChunkSource?.resumePointFor(globalIndex) ?: return
        lastSavedLiveIndex = globalIndex
        repository.updateLivePosition(bookId, href, sentenceIndex)
    }

    // -- controls ------------------------------------------------------------

    fun togglePlayPause() {
        player.togglePlayPause()
        player.refresh()
        _state.value = _state.value.copy(player = player.state.value)
    }

    fun setSpeed(speed: Float) {
        Breadcrumbs.leave("playback speed changed to ${speed}x")
        player.setSpeed(speed)
        _state.value = _state.value.copy(player = player.state.value)
    }

    /**
     * Play from the start of a sentence, e.g. because the reader tapped it.
     *
     * Explicitly requests the page move there (`_navigateTo.value =
     * chunkIndex`, the same thing `resumeFollowing()` does) rather than
     * leaving it to the poll loop's own "did the sentence change" check. That
     * check compares real playback position against `_state.value.currentIndex`
     * -- which this function itself sets to `chunkIndex` immediately, before
     * `player.seekTo()` has actually landed there (ExoPlayer's seek completes
     * asynchronously). The first poll tick after this runs would then see
     * *stale, pre-seek* audio position against an *already-updated*
     * currentIndex, "detect" a change to the old position, and send the page
     * there instead -- and once the seek genuinely completes and playback
     * position catches up to the real target, currentIndex already matches it,
     * so the poll loop never corrects it either. Confirmed real, not
     * theoretical: this was the actual cause behind BUG-17's repeated
     * "still broken" reports, none of which were actually in `ChunkIndex` at
     * all despite three rounds of fixes there.
     */
    fun playFrom(chunkIndex: Int) {
        follower.onSeekRequested()
        if (isLive) {
            // A live queue's items *are* its chunk indices (LiveChunkSource
            // never removes one once queued), so "seek to sentence N" is
            // "jump to queue item N" directly -- Media3's own single-arg
            // seekTo only ever addresses the current item, which cannot
            // reach anywhere else in a multi-item queue.
            player.seekToQueueItem(chunkIndex)
        } else {
            val target = index.seekPositionFor(chunkIndex) ?: return
            player.seekTo(target)
        }
        player.play()
        _state.value = _state.value.copy(currentIndex = chunkIndex, following = true)
        _navigateTo.value = chunkIndex
    }

    // -- word tap / drag-selection ---------------------------------------------
    // Thin delegates to [selection]; see [WordSelectionController]'s own doc
    // for what each of these actually does.

    fun onWordTapped(word: String, resourceHref: String?, blockText: String, offset: Int, readingProgression: Double?) =
        selection.onWordTapped(word, resourceHref, blockText, offset, readingProgression)

    fun armHandles(word: String, left: Float, top: Float, right: Float, bottom: Float, resourceHref: String, progression: Double?) =
        selection.armHandles(word, left, top, right, bottom, resourceHref, progression)

    fun onHandleDragStart() = selection.onHandleDragStart()

    fun onHandleMoved(isStart: Boolean, dxCss: Float, dyCss: Float) = selection.onHandleMoved(isStart, dxCss, dyCss)

    fun onExtendResolved(result: ExtendedSelection) = selection.onExtendResolved(result)

    fun onHandleDragEnd() = selection.onHandleDragEnd()

    /**
     * Play from the beginning of the sentence the tapped word/selection sits
     * in -- or, for a live book with nothing aligned there yet (the common
     * case: most of the book has simply never been visited), restart the
     * live session at that spot instead. See [WordMenuTarget.
     * canReadFromHere]'s own doc for why only a live book gets this
     * fallback; this is also the *only* thing that ever asks the server to
     * synthesize something new -- the seek bar deliberately does not (see
     * `ReaderViewModel.goToProgression`).
     */
    fun readFromTappedWord() {
        val target = tappedWord.value ?: return
        dismissWordMenu()
        val sentenceIndex = target.sentenceIndex
        val quoted = target.quotedText
        if (sentenceIndex != null) {
            appContext?.let {
                dev.reedd.diagnostics.CrashReporter.reportDiagnostic(
                    it, "ReeddReadFromHere",
                    "aligned path: tappedWord=\"$quoted\" resourceHref=${target.resourceHref} " +
                        "tapProgression=${target.progression} -> sentenceIndex=$sentenceIndex " +
                        "chunk.resourceHref=${index.chunkAtIndex(sentenceIndex)?.resourceHref} " +
                        "chunk.textHighlight=${index.chunkAtIndex(sentenceIndex)?.textHighlight} " +
                        "chunk.progression=${index.chunkAtIndex(sentenceIndex)?.progression}",
                )
            }
            playFrom(sentenceIndex)
            return
        }
        if (!isLive) return
        val href = target.resourceHref ?: return
        val progression = target.progression ?: return
        appContext?.let {
            dev.reedd.diagnostics.CrashReporter.reportDiagnostic(
                it, "ReeddReadFromHere",
                "fraction path: tappedWord=\"$quoted\" resourceHref=$href tapProgression=$progression " +
                    "isTap=${target is WordMenuTarget.Tap}",
            )
        }
        viewModelScope.launch {
            // A plain word tap carries its own exact block text + character
            // offset -- LiveChunkSource.startFromTap uses that to build a
            // precise anchor window; a drag-selection has no single block
            // offset, but its own selected text (plus its already-captured
            // before/after context) makes just as good an anchor directly.
            when (target) {
                is WordMenuTarget.Tap -> liveChunkSource?.startFromTap(href, target.blockText, target.offset, progression)
                is WordMenuTarget.ExtendedSelection ->
                    liveChunkSource?.startFromFraction(
                        href, progression,
                        anchorText = target.before + target.text + target.after,
                        // The middle of the selected text itself, not the
                        // combined string's own midpoint -- before/after
                        // context are not generally the same length, so
                        // len/2 would suffer the same off-center bug a tap
                        // near a resource's edge does (see LiveStartBody.
                        // anchorOffset's own doc).
                        anchorOffset = target.before.length + target.text.length / 2,
                    )
            }
            // Only now, not before: startFromTap/startFromFraction's own
            // reset() calls PlayerConnection.prepareLive, which pauses and
            // stops the controller -- calling play() any earlier would just
            // get silently undone by that. Matches playFrom's own identical
            // call for the already-aligned path above; without this, "Read
            // from here" only ever highlighted the new spot and left the
            // reader to separately hit play -- confirmed live as a real gap.
            player.play()
            // follower.onSeekRequested(), not a direct `following = true`
            // write to _state: that only touched the exposed StateFlow, not
            // FollowController's own internal isFollowing -- pollPosition's
            // very next tick overwrites _state.following from *that* real
            // value regardless, so a direct write here was a one-frame
            // cosmetic flash, not an actual re-engage. Confirmed live as
            // the crosshair not lighting up (or immediately un-lighting)
            // after "Read from here" on text that was not already aligned.
            follower.onSeekRequested()
            _state.value = _state.value.copy(following = true)
        }
    }

    /**
     * "Play from here" on a search result -- the search sheet's own
     * counterpart to [readFromTappedWord], reusing the exact same
     * resolution each of that function's two branches already uses rather
     * than inventing a third. A search hit has no character offset or
     * on-screen tap position (it comes from `Publication.search()`, not a
     * WebView tap), only the matched text and its resource + progression --
     * exactly the shape [ChunkIndex.indexOfSelection] and
     * [LiveChunkSource.startFromFraction] already accept for a
     * drag-selection with no single tap point either.
     *
     * @param resourceHref the hit's own resource, e.g. `Locator.href`.
     * @param progression the hit's position within that resource (0.0-1.0),
     *   `Locator.locations.progression` -- only used for a live book with
     *   nothing aligned there yet, same as [WordMenuTarget.ExtendedSelection].
     * @param text the matched passage, e.g. `Locator.text.highlight`.
     */
    fun playFromSearchResult(resourceHref: String, progression: Double, text: String) {
        if (!isLive) {
            val sentenceIndex = index.indexOfSelection(resourceHref, text) ?: return
            playFrom(sentenceIndex)
            return
        }
        viewModelScope.launch {
            liveChunkSource?.startFromFraction(
                resourceHref, progression,
                anchorText = text,
                anchorOffset = text.length / 2,
            )
            // Same ordering as readFromTappedWord's own live branch, for the
            // same reason: startFromFraction's reset() calls
            // PlayerConnection.prepareLive, which pauses/stops the
            // controller, so play() has to come after it or it is silently
            // undone.
            player.play()
            follower.onSeekRequested()
            _state.value = _state.value.copy(following = true)
        }
    }

    /**
     * Look the tapped word (or selection) up in the bundled dictionary.
     *
     * Playback stops first: reading a definition and listening at the same time is
     * not something anyone is doing on purpose.
     */
    fun defineTappedWord() {
        val target = tappedWord.value ?: return
        dismissWordMenu()
        player.pause()
        _definition.value = DefinitionState(word = target.quotedText, loading = true)
        viewModelScope.launch {
            val found = runCatching { dictionary.lookup(target.quotedText) }.getOrNull()
            _definition.value = DefinitionState(
                word = target.quotedText,
                loading = false,
                definition = found,
                notFound = found == null,
            )
        }
    }

    /** Also clears [selectionHandles] -- once the menu is gone, its handles
     *  (if any were armed) have nothing left to extend. */
    fun dismissWordMenu() = selection.dismiss()

    /** The menu's Bookmark row: close the menu, keep what it was about. */
    fun openNoteEditor() {
        val target = tappedWord.value ?: return
        dismissWordMenu()
        _pendingNoteTarget.value = target
    }

    fun dismissNoteEditor() {
        _pendingNoteTarget.value = null
    }

    fun dismissDefinition() {
        _definition.value = null
    }

    fun nextSentence() {
        index.nextIndex(currentGlobalPositionMs())?.let(::playFrom)
    }

    fun previousSentence() {
        index.previousIndex(currentGlobalPositionMs())?.let(::playFrom)
    }

    /**
     * A transport scrub bar's seek, for a file-based book only -- a live
     * book's own seek bar deliberately does not go through here (or through
     * this class at all): see `ReaderViewModel.goToProgression` and
     * `ReaderScreen`'s own wiring of `ReadAlongBar`'s `onLiveSeek`. Dragging
     * it just moves the page; only an explicit "Read from here" tap
     * ([readFromTappedWord]) ever starts synthesis, so that the reader is
     * always the one deciding when the server gets asked to do anything.
     */
    fun seekTo(positionMs: Long) {
        follower.onSeekRequested()
        player.seekTo(positionMs)
    }

    /** The reader dragged the page: keep playing, stop moving it for them. */
    fun onUserDragged() {
        if (!follower.isFollowing) return
        follower.onUserDragged()
        _state.value = _state.value.copy(following = false)
    }

    fun resumeFollowing() {
        follower.resume()
        _state.value = _state.value.copy(following = true)
        val current = _state.value.currentIndex
        if (current >= 0) _navigateTo.value = current
    }

    fun toggleFollowing() {
        if (follower.isFollowing) {
            follower.stop()
            _state.value = _state.value.copy(following = false)
        } else {
            resumeFollowing()
        }
    }

    /** Consumed by the reader once it has moved the page. */
    fun onNavigationHandled(chunkIndex: Int) {
        follower.onNavigated(chunkIndex)
        _navigateTo.value = null
    }

    fun setSyncOffset(offsetMs: Long) {
        viewModelScope.launch {
            repository.updateSyncOffset(bookId, offsetMs)
            index = index.withOffset(offsetMs)
            _state.value = _state.value.copy(syncOffsetMs = offsetMs)
        }
    }

    // player itself is never released here -- see this class's own history:
    // it is shared app-wide (AppContainer.playerConnection), so leaving the
    // reader must not tear it down. Library's "now playing" bar and the
    // next book's own guard against auto-starting both depend on the
    // connection, and its state, still being there. viewModelScope itself
    // is still cancelled automatically, which is what actually stops this
    // book's own position poll.
    //
    // liveChunkSource *is* stopped here, though: unlike the poll loop
    // (viewModelScope-scoped, cancelled for free), LiveChunkSource owns its
    // own CoroutineScope so its polling survives a book switch on the
    // shared player -- without this, its background HTTP polling and the
    // server-side engine slot it holds would both leak for as long as the
    // process runs, not just this reader visit.
    override fun onCleared() {
        liveChunkSource?.stop()
    }

    companion object {
        private const val PLAYING_TICK_MS = 100L
        private const val IDLE_TICK_MS = 400L

        /** How far playback must move before the bookmark is rewritten. */
        private const val SAVE_INTERVAL_MS = 5_000L

        const val OFFSET_STEP_MS = 25L
        const val OFFSET_LIMIT_MS = 2_000L

        /** Only reached if a `canReadLive` book somehow has no voice
         *  recorded -- ImportSheet.kt always sends one, so this is a
         *  last-resort fallback, not the normal path. */
        private const val DEFAULT_LIVE_VOICE = "alba"

        fun factory(
            container: AppContainer,
            bookId: String,
            autoPlay: Boolean = false,
            readingOrderHrefs: suspend () -> List<String> = { emptyList() },
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReadAlongViewModel(
                bookId,
                container.repository,
                container.readAlongAligner,
                container.dictionary,
                container.playerConnection,
                autoPlay,
                container.api,
                container.files,
                readingOrderHrefs,
                container.appContext,
            ) as T
        }
    }
}
