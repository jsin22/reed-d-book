package dev.reedd.ui.reader

import dev.reedd.domain.ChunkIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the word-tap context menu is about: a single tapped word, or a
 * selection extended by dragging a handle out from one -- unified so both
 * drive the same [WordMenuBar]. There is no long-press-to-select gesture: a
 * selection always starts as a [Tap] (see [WordSelectionController.
 * armHandles]), because Readium's `InputListener` has no long-press timing
 * signal to build one from -- `onDrag` fires identically for the start of an
 * ordinary page-turn swipe and a deliberate hold, so extending is instead
 * always an explicit drag on a small handle (see [SelectionTextResolver]).
 *
 * No on-screen position is carried here (an earlier version did, so
 * [WordMenuBar] could float itself beside the word) -- [WordMenuBar] now
 * takes over the reader's own bottom bar, in place of the transport
 * controls, for as long as this is non-null, rather than floating anywhere
 * near the text at all. That sidesteps an entire class of real bugs several
 * rounds of floating-popup positioning ran into: the reader's bottom bar is
 * a fixed, already-reserved layout slot, so the menu can never end up on
 * top of the word, the highlight, or a [SelectionHandle] regardless of
 * where on the page the text is.
 *
 * @property sentenceIndex the sentence this word/selection belongs to, or
 *   null when unmapped to the audio -- "Read from here" is hidden in that case.
 */
sealed interface WordMenuTarget {
    val sentenceIndex: Int?
    /** The word, or the selected passage -- what a note quotes, and what
     *  Definition looks up. */
    val quotedText: String
    /** False only for a multi-word selection: looking up a whole passage in
     *  a dictionary is not a thing, so the row is omitted entirely rather
     *  than shown disabled -- same convention as "Read from here" already
     *  uses for [sentenceIndex]. */
    val canDefine: Boolean

    val canReadFromHere: Boolean get() = sentenceIndex != null

    data class Tap(
        val word: String,
        /** Kept (not discarded after resolving [sentenceIndex], the way this
         *  used to work) so a Notes tap can build a real [org.readium.r2.
         *  shared.publication.Locator] from it -- see [dev.reedd.domain.
         *  NoteLocators.tapLocator]. */
        val resourceHref: String?,
        val blockText: String,
        val offset: Int,
        val progression: Double?,
        override val sentenceIndex: Int?,
    ) : WordMenuTarget {
        override val quotedText: String get() = word
        override val canDefine: Boolean get() = true
    }

    /** Built once a handle-drag ends, from [SelectionHandles] -- see
     *  [dev.reedd.domain.NoteLocators.extendedLocator] for how a note's
     *  Locator is built from [text]/[before]/[after] rather than from a
     *  Readium-resolved one, since there is no such thing here any more. */
    data class ExtendedSelection(
        val text: String,
        val before: String,
        val after: String,
        val resourceHref: String,
        val progression: Double?,
        override val sentenceIndex: Int?,
    ) : WordMenuTarget {
        override val quotedText: String get() = text
        override val canDefine: Boolean get() = !text.any { it.isWhitespace() }
    }
}

/**
 * The live state of a selection's two drag handles, in CSS px -- see
 * [WordSelectionController.selectionHandles].
 *
 * [startX]/[startY]/[endX]/[endY] (and their `Bottom` counterparts) are the
 * **raw query position** -- where the finger has actually dragged to,
 * accumulated purely from touch deltas by [WordSelectionController.
 * onHandleMoved] and never touched by a JS result. [displayStartX]/
 * [displayStartY]/[displayEndX]/[displayEndY] (and their `Bottom`s) are the
 * separate, JS-*resolved* caret position from the last successful
 * [SelectionTextResolver.extend] call, used only for rendering the handle
 * precisely snapped to a character boundary.
 *
 * These used to be the same fields -- [WordSelectionController.
 * onExtendResolved] overwrote the raw position with the resolved one, and
 * the next [WordSelectionController.onHandleMoved] tick's delta then
 * accumulated on top of *that*. A real, confirmed-live bug: near a line
 * wrap, `caretRangeFromPoint` can resolve two very different lines for two
 * query points only a pixel apart, so a resolved snap could land 15-20px
 * away (one line height) from where the finger truly was -- silently
 * relocating the accumulator's own baseline. Every following delta then
 * compounded against that wrong baseline instead of the physical touch
 * position, which is what made dragging feel imprecise and, worse, made a
 * selection fail to visibly shrink when dragged back: the very next
 * resolved snap could teleport the query point forward again before the
 * shrink ever got sent. Keeping the two separate means the query position
 * sent to [SelectionTextResolver.extend] -- and the baseline every future
 * delta accumulates against -- always tracks the real finger, regardless of
 * how the JS chooses to resolve any single query.
 */
data class SelectionHandles(
    val startX: Float,
    val startY: Float,
    val startBottom: Float,
    val endX: Float,
    val endY: Float,
    val endBottom: Float,
    val displayStartX: Float,
    val displayStartY: Float,
    val displayStartBottom: Float,
    val displayEndX: Float,
    val displayEndY: Float,
    val displayEndBottom: Float,
    val text: String,
    val before: String,
    val after: String,
    val resourceHref: String,
    val progression: Double?,
)

/**
 * Turns a tap or a handle-drag into a [WordMenuTarget]: armed handles, their
 * live drag position, and the drag-in-progress flag [ReaderScreen]'s own
 * page-swipe listener has to check before it tramples them.
 *
 * Pulled out of `ReadAlongViewModel` itself, which had grown five distinct
 * jobs (playback/follow, this, dictionary lookup, notes-editor integration,
 * and the notes-navigation highlight) sharing one file -- this was the
 * largest and most self-contained of them, a genuinely large state machine
 * in its own right (see the Margin Notes review). `ReadAlongViewModel`
 * keeps the same public surface (`tappedWord`, `armHandles`, etc.) as thin
 * delegates to an instance of this, so nothing above it -- `ReaderScreen`
 * included -- had to change.
 *
 * [chunkIndex] is a supplier, not a snapshot: `ReadAlongViewModel` reloads
 * its `ChunkIndex` after an alignment pass or a sync-offset change, and a
 * tap or a drag-release needs whatever is current *then*, not whatever was
 * current when this controller was constructed.
 */
class WordSelectionController(private val chunkIndex: () -> ChunkIndex) {

    private val _tappedWord = MutableStateFlow<WordMenuTarget?>(null)
    val tappedWord: StateFlow<WordMenuTarget?> = _tappedWord.asStateFlow()

    /**
     * Where a selection's two drag handles are right now, in **CSS pixels**
     * (same unit [dev.reedd.ui.reader.TapTextResolver.TappedWord]'s rect
     * uses) -- armed from a tapped word's own rect ([armHandles]), then kept
     * live as the reader drags either end ([onHandleMoved]/[onExtendResolved]).
     * Non-null for exactly as long as a tap's menu could plausibly still be
     * extended into a selection, i.e. from the moment a word is tapped until
     * the next tap/dismiss -- not the same lifetime as [tappedWord], which a
     * handle-drag deliberately leaves untouched until [onHandleDragEnd].
     */
    private val _selectionHandles = MutableStateFlow<SelectionHandles?>(null)
    val selectionHandles: StateFlow<SelectionHandles?> = _selectionHandles.asStateFlow()

    /**
     * True from [onHandleDragStart] to [onHandleDragEnd] -- the window during
     * which a handle is actively under a finger. Readium's own
     * `InputListener.onDrag` (see `ReaderScreen`'s `input` object) fires for
     * *any* drag it sees, including one that starts on a
     * [dev.reedd.ui.reader.SelectionHandle] sitting on top of the WebView --
     * that callback calls `dismissWordMenu` unconditionally, which was
     * silently wiping [_selectionHandles] and [_tappedWord] out from under an
     * in-progress handle drag the instant it began (the drag then looked like
     * it "did nothing," because the state it was updating had already been
     * nulled). `ReaderScreen` checks this before calling `dismissWordMenu`
     * from that listener.
     */
    private var draggingHandle = false
    val isHandleDragActive: Boolean get() = draggingHandle

    /**
     * A word was tapped: open its menu.
     *
     * Deliberately does nothing else. Playback does not move, the page does not
     * scroll, no other state changes — a tap is cheap and easily accidental, and it
     * is also how pages are turned, so it only offers.
     *
     * The sentence is resolved now rather than when the menu item is chosen, so
     * "Read from here" can be hidden for a passage that has no audio mapped to it
     * instead of failing after the reader picks it.
     */
    fun onWordTapped(
        word: String,
        resourceHref: String?,
        blockText: String,
        offset: Int,
        readingProgression: Double?,
    ) {
        _tappedWord.value = WordMenuTarget.Tap(
            word = word,
            resourceHref = resourceHref,
            blockText = blockText,
            offset = offset,
            progression = readingProgression,
            // readingProgression: where the reader is looking on screen, not
            // audio playback position -- see ChunkIndex.indexOfTap for why that
            // distinction matters.
            sentenceIndex = chunkIndex().indexOfTap(resourceHref, blockText, offset, readingProgression),
        )
    }

    /**
     * Seeds the drag handles from the just-tapped word's own rect (CSS px,
     * same as [dev.reedd.ui.reader.TapTextResolver.TappedWord]'s) -- no JS
     * needed, since a single word never wraps a line, so its own corners
     * already are the two handle positions. Called right after
     * [onWordTapped], from the same tap.
     */
    fun armHandles(word: String, left: Float, top: Float, right: Float, bottom: Float, resourceHref: String, progression: Double?) {
        _selectionHandles.value = SelectionHandles(
            startX = left, startY = top, startBottom = bottom,
            endX = right, endY = top, endBottom = bottom,
            displayStartX = left, displayStartY = top, displayStartBottom = bottom,
            displayEndX = right, displayEndY = top, displayEndBottom = bottom,
            text = word,
            before = "",
            after = "",
            resourceHref = resourceHref,
            progression = progression,
        )
    }

    /** A handle drag began: see [draggingHandle]'s own docstring for why
     *  [ReaderScreen] needs to know this before its own drag listener fires. */
    fun onHandleDragStart() {
        draggingHandle = true
    }

    /**
     * Optimistic, JS-free update of whichever handle is being dragged, for
     * instant visual feedback while [onExtendResolved]'s JS round trip is
     * still in flight. Both `y`/`bottom` are set to the same raw drag
     * position here -- a placeholder until the next resolved rect arrives.
     *
     * Takes a **delta**, not an absolute position, and accumulates it against
     * [_selectionHandles]' own current value -- not the caller's. A real
     * on-device bug: the caller (`ReaderScreen`'s `SelectionHandle.onDrag`)
     * used to compute the new absolute position itself, from a `handles`
     * value captured in its own Compose closure. During a fast, continuous
     * drag, touch events can arrive faster than Compose recomposes, so that
     * closure was often one or more ticks stale -- each tick then based its
     * "new" position on an old baseline, silently dropping every delta in
     * between. The visible result was a handle that twitched slightly but
     * never actually travelled the full distance dragged, so the extended
     * range it fed into `extend()` never meaningfully grew past the original
     * word. Reading and writing [_selectionHandles] here instead -- a plain
     * Kotlin property, not something recomposition-timing-dependent -- means
     * every tick accumulates against the true latest value regardless of
     * whether Compose has caught up yet.
     */
    fun onHandleMoved(isStart: Boolean, dxCss: Float, dyCss: Float) {
        val handles = _selectionHandles.value ?: return
        _selectionHandles.value = if (isStart) {
            val x = handles.startX + dxCss
            val y = handles.startY + dyCss
            handles.copy(startX = x, startY = y, startBottom = y)
        } else {
            val x = handles.endX + dxCss
            val y = handles.endY + dyCss
            handles.copy(endX = x, endY = y, endBottom = y)
        }
    }

    /**
     * The result of a throttled [SelectionTextResolver.extend] call: the
     * newly-extended text/context, and where to *render* each handle now.
     *
     * Deliberately only touches the `display*` fields, never [SelectionHandles.
     * startX]/[SelectionHandles.startY]/[SelectionHandles.endX]/
     * [SelectionHandles.endY] themselves -- see [SelectionHandles]' own
     * docstring for the real bug that came from this function overwriting the
     * raw query position with a JS-resolved one that could land a line away
     * from where the finger actually was.
     */
    fun onExtendResolved(result: ExtendedSelection) {
        val handles = _selectionHandles.value ?: return
        _selectionHandles.value = handles.copy(
            displayStartX = result.startX, displayStartY = result.startY, displayStartBottom = result.startBottom,
            displayEndX = result.endX, displayEndY = result.endY, displayEndBottom = result.endBottom,
            text = result.text, before = result.before, after = result.after,
        )
    }

    /** A handle drag ended: turn the current [selectionHandles] snapshot into
     *  a menu target, the same way [onWordTapped] does for a plain tap. */
    fun onHandleDragEnd() {
        draggingHandle = false
        val handles = _selectionHandles.value ?: return
        _tappedWord.value = WordMenuTarget.ExtendedSelection(
            text = handles.text,
            before = handles.before,
            after = handles.after,
            resourceHref = handles.resourceHref,
            progression = handles.progression,
            sentenceIndex = chunkIndex().indexOfSelection(handles.resourceHref, handles.text),
        )
    }

    /** Also clears [selectionHandles] -- once the menu is gone, its handles
     *  (if any were armed) have nothing left to extend. */
    fun dismiss() {
        _tappedWord.value = null
        _selectionHandles.value = null
    }
}
