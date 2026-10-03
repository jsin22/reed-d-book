package dev.reedd.domain

import dev.reedd.data.align.AlignmentResult
import dev.reedd.data.align.ChunkAligner
import dev.reedd.data.align.EpubTextExtractor
import dev.reedd.data.align.ResourceText
import dev.reedd.data.align.TextNormalizer
import dev.reedd.data.db.SyncChapterEntity
import dev.reedd.data.db.SyncChunkEntity
import dev.reedd.data.local.BookFiles
import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.LiveAdvanceBody
import dev.reedd.data.remote.LiveStartBody
import dev.reedd.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives one live-only book's reading end to end: starts a server-side
 * synthesis session one chapter at a time (`app/live_reading.py`), downloads
 * each sentence's audio, aligns newly-arrived sentences against the epub's
 * own text (the same [ChunkAligner] a real conversion uses), and feeds both
 * a growing [ChunkIndex] and the player's live queue. See
 * CPU_LIVE_READING_PLAN Phase 4.
 *
 * Owns exactly one reading session for its own lifetime -- a new instance is
 * created each time [dev.reedd.ui.reader.ReadAlongViewModel] (its owner) is,
 * matching that class's per-reader-open lifecycle. Nothing here persists
 * across a closed reader or a process restart; the owner is what saves the
 * `(resourceHref, sentenceIndex)` resume point after the fact.
 *
 * [startFrom] is a *hard* reset (a fresh player queue, a fresh `ChunkIndex`
 * starting at ms 0) -- used for the initial open, "read from here", and a
 * scrub/seek. Reaching the end of a chapter's own chunks is a *soft*
 * continuation instead (kept entirely internal, in [runChapter]'s own
 * recursive call): the player queue and the growing `ChunkIndex` are left
 * alone and simply keep extending into the next resource, so playback
 * crosses a chapter boundary the same way a real audiobook's single file
 * already does -- gaplessly, with one continuous position space.
 */
class LiveChunkSource(
    private val bookId: String,
    /** The server-side job id -- what every `/api/books/{id}/live/...` call
     *  actually addresses server-side (`main.py`'s `get_job` resolves this
     *  path segment as a job id, matching every other route in the API).
     *  Deliberately kept separate from [bookId], which is this device's own
     *  local Room primary key and is never known to the server: passing
     *  [bookId] here instead 404s every call (confirmed live -- see
     *  `live_reading_job_id_vs_book_id` memory). [bookId] stays the right
     *  key for [player]/[files], which only need a stable local identity. */
    private val jobId: String,
    private val epub: File,
    private var voice: String,
    private val title: String,
    private val author: String?,
    private val coverPath: String?,
    private val api: ApiProvider,
    private val player: PlayerConnection,
    private val files: BookFiles,
    /** Given the resource currently being read, the next one in the book's
     *  own reading order -- or null at the end of the book. Supplied by the
     *  caller (which has the open `Publication`) rather than resolved here,
     *  so this class stays free of any Readium dependency, matching
     *  [ChunkAligner]'s own design goal. */
    private val nextResourceHref: (currentResourceHref: String) -> String?,
    private val aligner: ChunkAligner = ChunkAligner(),
    /** Diagnostic-only, see `ReadAlongViewModel.startLive`'s own wiring --
     *  routes to `CrashReporter.reportDiagnostic` so a tester with no adb
     *  access can still see what a "Read from here" restart actually did. */
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** Bumped by every `startFrom`/`startFromFraction` call, checked
     *  again once `reset()`'s await returns -- a second seek fired before
     *  the first one's `reset()` finished waiting for the old job to
     *  actually stop would otherwise see the same not-yet-reassigned [job],
     *  both proceed past their own `reset()`, and both launch their own
     *  `runChapter`, two coroutines mutating [chunks] at once. Whichever
     *  call is no longer the latest bails out instead of launching. */
    private var generation = 0

    private var resources: List<ResourceText>? = null
    private fun resources(): List<ResourceText> =
        resources ?: runCatching { EpubTextExtractor.extract(epub) }.getOrDefault(emptyList()).also { resources = it }

    // Book-session-wide bookkeeping, reset only by startFrom -- continues
    // across chapter transitions so the whole book is one continuous
    // "global" ms timeline and chunk-ordinal space, matching what a real
    // audiobook's own single file/sync already is.
    private val chunks = mutableListOf<SyncChunkEntity>()
    private var nextGlobalStartMs = 0L
    private var nextOrdinal = 0
    private var chapterNumber = 0
    private var currentSessionId: String? = null
    /** Each chapter's own starting global ordinal and resource href, by
     *  chapter number -- used to map a global sentence index back to
     *  "which resource, which sentence within it" for [advance] and
     *  [resumePointFor]. Kept for every chapter this session has touched,
     *  not just the current one, since a chunk index resolved from a
     *  slightly-stale poll could still name an earlier one. */
    private val chapterStartOrdinals = mutableMapOf<Int, Int>()
    private val chapterResourceHrefs = mutableMapOf<Int, String>()

    private val _chunkIndex = MutableStateFlow(ChunkIndex.EMPTY)
    val chunkIndexFlow: StateFlow<ChunkIndex> = _chunkIndex.asStateFlow()

    /** Set on a busy/error response, or a real error mid-session; the
     *  caller surfaces it and clears it once shown. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** True once the last chapter's chunks are exhausted, played, and there
     *  is nowhere left in the book's reading order to continue into. */
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /**
     * True from the moment a session is asked to start until its first
     * sentence's audio reaches the player (or it fails) -- the ~5s the server
     * takes to synthesize that first sentence. Without it a Play press in that
     * window looked like nothing happened, and tapping again restarted the wait.
     */
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    private fun fail(message: String) {
        _message.value = message
        _preparing.value = false
    }

    /** What the latest [restart] asked for, so [applyVoice] can redo a start
     *  that has not played anything yet in the new voice. */
    private data class StartRequest(
        val resourceHref: String,
        val fromSentenceIndex: Int,
        val fromFraction: Double?,
        val anchorText: String?,
        val anchorOffset: Int?,
    )
    private var lastStart: StartRequest? = null

    /** Starts (or restarts) from a specific resource + sentence. Discards
     *  whatever was buffered, aligned, or queued -- see this class's own
     *  doc for why a hard reset, not a seamless handoff, is this phase's
     *  deliberate scope. */
    suspend fun startFrom(resourceHref: String, sentenceIndex: Int) = restart(resourceHref, sentenceIndex, fromFraction = null)

    /**
     * The reader applied a new voice from the settings sheet. Every case ends
     * with the new voice actually in use -- previously a change made before
     * anything had played was dropped, and the old voice kept playing:
     *
     *  * [resumeAt] given (something has played): restart at that sentence in
     *    the new voice, a hard reset like [startFrom];
     *  * otherwise, a session already started but nothing played yet: redo that
     *    same start in the new voice;
     *  * otherwise nothing has started: the next start uses it.
     *
     * Anything already buffered or queued in the old voice is discarded.
     */
    suspend fun applyVoice(newVoice: String, resumeAt: Pair<String, Int>?) {
        log("applyVoice: oldVoice=$voice newVoice=$newVoice resumeAt=$resumeAt lastStart=$lastStart")
        voice = newVoice
        val pending = lastStart
        when {
            resumeAt != null -> startFrom(resumeAt.first, resumeAt.second)
            pending != null -> restart(
                pending.resourceHref, pending.fromSentenceIndex, pending.fromFraction,
                pending.anchorText, pending.anchorOffset,
            )
        }
    }

    /**
     * "Read from here" on a word that has not been synthesized yet: the tap
     * itself already gives a resource href and a within-resource
     * progression (Readium's own locator), so unlike the seek bar (see
     * `ReaderViewModel.goToProgression`, which moves the page via
     * Readium's own `Publication.positions()` and never touches this class
     * or the server at all) there is no book-wide resolution to do here --
     * only the server can turn a
     * proportion into a real sentence index (it holds the actual sentence
     * list; [app.live_reading.start]'s own doc explains why an absolute
     * character offset computed here would not line up with its own,
     * differently-parsed text). A hard reset, same as [startFrom].
     */
    suspend fun startFromFraction(
        resourceHref: String,
        withinResourceFraction: Double,
        anchorText: String? = null,
        anchorOffset: Int? = null,
    ) = restart(resourceHref, fromSentenceIndex = 0, fromFraction = withinResourceFraction, anchorText = anchorText, anchorOffset = anchorOffset)

    /**
     * "Read from here" on a plain word tap, specifically: computes both an
     * exact character-offset position (over [withinResourceFraction]'s own
     * Readium `Locator.progression`) *and* a window of raw text centered on
     * it, by locating [blockText] (the tapped DOM block's own text,
     * verbatim) in this resource's [resources] -- the same text
     * [ChunkAligner] itself searches -- via [TextNormalizer]'s forgiving
     * match, then adding [offset] (the tap's own character offset within
     * that block).
     *
     * The text window (sent server-side as `anchor_text`, see
     * `app.live_reading._resolve_anchor`) is what actually fixes the
     * mismatch, not the fraction alone: confirmed live, even the precise
     * character-offset fraction can still drift several sentences on a
     * resource where this device's own text extraction and the server's
     * disagree enough on total length (a resource spanning many chapters
     * in one file makes that disagreement compound). An exact text match is
     * immune to that -- it does not care how long either side thinks the
     * resource is. `from_fraction` (from the exact offset, still better
     * than Readium's own progression) stays as the fallback for when the
     * anchor window matches nothing server-side.
     */
    suspend fun startFromTap(resourceHref: String, blockText: String, offset: Int, withinResourceFraction: Double) {
        val precise = preciseLocation(resourceHref, blockText, offset)
        val fraction = precise?.fraction ?: withinResourceFraction
        log("startFromTap: resourceHref=$resourceHref blockText=\"$blockText\" offset=$offset " +
            "readiumProgression=$withinResourceFraction preciseFraction=$fraction anchorText=\"${precise?.anchorText}\" " +
            "anchorOffset=${precise?.anchorOffset}")
        restart(
            resourceHref, fromSentenceIndex = 0, fromFraction = fraction,
            anchorText = precise?.anchorText, anchorOffset = precise?.anchorOffset,
        )
    }

    private data class PreciseLocation(val fraction: Double, val anchorText: String, val anchorOffset: Int)

    private fun preciseLocation(resourceHref: String, blockText: String, offset: Int): PreciseLocation? {
        val resource = resources().firstOrNull { bareResourceName(it.href) == bareResourceName(resourceHref) } ?: return null
        if (resource.text.isEmpty()) return null
        val haystack = TextNormalizer.normalize(resource.text)
        val needle = TextNormalizer.normalizeToString(blockText)
        if (needle.isBlank()) return null
        val matchStart = haystack.text.indexOf(needle)
        if (matchStart < 0) return null
        val original = haystack.originalRange(matchStart, matchStart + needle.length)
        val absoluteOffset = (original.first + offset).coerceIn(0, resource.text.length)
        val fraction = absoluteOffset.toDouble() / resource.text.length
        val windowStart = (absoluteOffset - ANCHOR_WINDOW_CHARS).coerceAtLeast(0)
        val windowEnd = (absoluteOffset + ANCHOR_WINDOW_CHARS).coerceAtMost(resource.text.length)
        // Not always absoluteOffset - windowStart == ANCHOR_WINDOW_CHARS: a
        // tap near either edge of the resource clamps one side of the
        // window, so the tap does not sit at the window's own midpoint.
        // Sent alongside anchorText so the server can locate the tap
        // precisely instead of assuming it is centered -- see this field's
        // own doc on `LiveStartBody.anchorOffset` for the real bug this
        // fixes (a first-line tap resolving to a later sentence).
        val anchorOffset = absoluteOffset - windowStart
        return PreciseLocation(fraction, resource.text.substring(windowStart, windowEnd), anchorOffset)
    }

    private suspend fun restart(
        resourceHref: String,
        fromSentenceIndex: Int,
        fromFraction: Double?,
        anchorText: String? = null,
        anchorOffset: Int? = null,
    ) {
        val myGeneration = ++generation
        reset()
        if (myGeneration != generation) return // superseded by a newer call while reset() was awaiting
        lastStart = StartRequest(resourceHref, fromSentenceIndex, fromFraction, anchorText, anchorOffset)
        _preparing.value = true
        job = scope.launch { runChapter(resourceHref, fromSentenceIndex, fromFraction, anchorText, anchorOffset) }
            .also { started -> started.invokeOnCompletion { if (myGeneration == generation) _preparing.value = false } }
    }

    /**
     * Waits for the previous chapter's own coroutine to actually stop
     * before touching any shared state -- `job?.cancel()` alone is
     * cooperative and only takes effect at that coroutine's next suspension
     * point, so a `startFrom`/`startFromFraction` racing in right after
     * it could `chunks.clear()` (Main thread, here) while `runChapter`
     * (Dispatchers.IO) was still mid-flight mutating the very same plain,
     * unsynchronized `chunks` list. Confirmed live as a real, reproducible
     * crash from rapid seek-bar scrubbing: `IndexOutOfBoundsException`
     * inside `runChapter`'s own alignment splice, `chunks` having been
     * cleared out from under it between reading its size and writing to it.
     */
    private suspend fun reset() {
        job?.cancelAndJoin()
        releaseSession(currentSessionId)
        currentSessionId = null
        chunks.clear()
        nextGlobalStartMs = 0L
        nextOrdinal = 0
        chapterNumber = 0
        chapterStartOrdinals.clear()
        chapterResourceHrefs.clear()
        _chunkIndex.value = ChunkIndex.EMPTY
        _finished.value = false
        _preparing.value = false
        player.prepareLive(bookId, title, author, coverPath)
    }

    /** Tells the server where the reader actually is now, in the *global*
     *  ordinal space -- a no-op if that index belongs to a chapter this
     *  session no longer has a live session for (an already-finished
     *  earlier chapter; nothing left there to prune/buffer). */
    fun advance(globalIndex: Int) {
        val sessionId = currentSessionId ?: return
        val chapter = chunks.getOrNull(globalIndex)?.chapter ?: return
        val chapterStart = chapterStartOrdinals[chapter] ?: return
        if (chapter != chapterNumber) return // that session was already stopped
        scope.launch { runCatching { api.service().advanceLive(jobId, sessionId, LiveAdvanceBody(globalIndex - chapterStart)) } }
    }

    /** The `(resourceHref, chapter-relative sentence index)` for a *global*
     *  chunk index -- what a future `startFrom` needs to resume exactly
     *  where playback left off, not just at the chapter's own start.
     *  `chunk.resourceHref` (only set once aligned, and only on the
     *  epub's own copy of the text) is not used here on purpose: this
     *  needs the resource this chapter was *asked for*, which is known
     *  unconditionally, whether or not that particular sentence aligned. */
    fun resumePointFor(globalIndex: Int): Pair<String, Int>? {
        val chunk = chunks.getOrNull(globalIndex) ?: return null
        val href = chapterResourceHrefs[chunk.chapter] ?: return null
        val chapterStart = chapterStartOrdinals[chunk.chapter] ?: return null
        return href to (globalIndex - chapterStart)
    }

    /** Releases the pool slot early, e.g. when the reader leaves the book --
     *  see `stop`'s own server-side doc for why this matters (an idle
     *  session self-releases eventually regardless, but there is no reason
     *  to make the next listener wait for that). */
    fun stop() {
        job?.cancel()
        releaseSession(currentSessionId)
        currentSessionId = null
    }

    private fun releaseSession(sessionId: String?) {
        if (sessionId == null) return
        scope.launch { runCatching { api.service().stopLive(jobId, sessionId) } }
    }

    private suspend fun runChapter(
        resourceHref: String,
        fromSentenceIndex: Int,
        fromFraction: Double? = null,
        anchorText: String? = null,
        anchorOffset: Int? = null,
    ) {
        chapterNumber += 1
        val thisChapter = chapterNumber
        chapterResourceHrefs[thisChapter] = resourceHref
        log("runChapter: chapter=$thisChapter resourceHref=$resourceHref fromSentenceIndex=$fromSentenceIndex " +
            "fromFraction=$fromFraction anchorText=\"$anchorText\" anchorOffset=$anchorOffset voice=$voice")

        val session = try {
            api.service().startLive(
                jobId,
                LiveStartBody(resourceHref, fromSentenceIndex, fromFraction, anchorText, anchorOffset, voice),
            )
        } catch (e: ApiException) {
            fail(if (e.isBusy) "Reading live is busy right now -- try again shortly" else (e.detail ?: "Could not start reading live"))
            return
        } catch (e: CancellationException) {
            throw e // a new startFrom/startFromFraction cancelled this one -- not a real error, see reset's own doc
        } catch (e: Exception) {
            fail("Could not reach the server to read live")
            return
        }
        currentSessionId = session.sessionId
        // The server's own chunk.index for this chapter starts counting at
        // its *resolved* starting sentence index, not 0 (app/live_reading.py's
        // next_to_synthesize) -- offsetting the recorded start back by it is
        // what makes globalIndex - chapterStart reproduce that same server-
        // relative index later, in advance()/resumePointFor(). Read from the
        // response, not the request's own fromSentenceIndex: a fromFraction
        // seek does not know the real starting index until the server
        // resolves it against its own sentence list.
        chapterStartOrdinals[thisChapter] = nextOrdinal - session.fromSentenceIndex
        val sessionId = session.sessionId
        log("runChapter: chapter=$thisChapter server session=$sessionId resolved from_sentence_index=${session.fromSentenceIndex}")

        // The server prunes already-consumed chunks off the *front* of its
        // own status().chunks list as advance() reports progress (bounds
        // its memory) -- status.chunks is not append-only, it can shrink.
        // Tracking "seen" as a plain count against that list (this used to
        // be `status.chunks.drop(seenCount)`) breaks the moment pruning
        // shrinks the list below the count: drop() then returns nothing,
        // and every genuinely new chunk that arrived in that same response
        // is silently missed -- never downloaded, never queued, never
        // played. Confirmed live as real, audible skipped sentences.
        // Each chunk's own `index` is stable and monotonic regardless of
        // pruning, so that -- not list position -- is the correct watermark.
        var lastProcessedIndex = session.fromSentenceIndex - 1
        while (currentCoroutineContext().isActive) {
            val status = try {
                api.service().liveStatus(jobId, sessionId)
            } catch (e: CancellationException) {
                throw e // see runChapter's own startLive catch for why
            } catch (e: Exception) {
                fail("Lost the connection while reading live")
                return
            }

            val newChunks = status.chunks.filter { it.index > lastProcessedIndex }
            if (newChunks.isNotEmpty()) {
                val newEntities = newChunks.map { info ->
                    val startMs = nextGlobalStartMs
                    val durationMs = (info.durationS * 1000).toLong()
                    nextGlobalStartMs += durationMs
                    SyncChunkEntity(
                        bookId = bookId,
                        ordinal = nextOrdinal++,
                        text = info.text,
                        startMs = startMs,
                        endMs = startMs + durationMs,
                        chapter = thisChapter,
                    )
                }
                val chapterStart = chunks.indexOfFirst { it.chapter == thisChapter }.let { if (it < 0) chunks.size else it }
                chunks.addAll(newEntities)

                // Re-run alignment over this whole chapter's chunks-so-far, not just
                // the batch that just arrived: ChunkAligner.alignChapter searches
                // forward from one continuous cursor, the same way a real
                // conversion's single align() call processes a whole chapter --
                // aligning only each new batch on its own gave every batch a fresh
                // cursor restarting at the chapter's own start, with no memory of
                // how far earlier batches had already searched. Confirmed live:
                // "Read from here" appeared on some sentences on a page and not
                // others, with no visible reason, purely because of which poll
                // batch a sentence happened to arrive in. Re-deriving already-
                // aligned entries again here is cheap (a plain substring search
                // over one chapter's own text) and deterministic, so it costs
                // nothing beyond the redundant work.
                val chapterChunks = chunks.subList(chapterStart, chunks.size).toList()
                val chapterEntity = SyncChapterEntity(
                    bookId = bookId, chapterIndex = thisChapter, title = null,
                    source = resourceHref, startMs = chapterChunks.first().startMs, endMs = chapterChunks.last().endMs,
                )
                val aligned = runCatching {
                    aligner.align(chapterChunks, listOf(chapterEntity), resources(), skipFirstOrdinal = false)
                }.getOrDefault(AlignmentResult(chapterChunks, aligned = 0, total = chapterChunks.size))
                for ((offset, updated) in aligned.chunks.withIndex()) {
                    chunks[chapterStart + offset] = updated
                }
                _chunkIndex.value = ChunkIndex(chunks.toList())

                if (lastProcessedIndex == session.fromSentenceIndex - 1) {
                    // Only the chapter's first-ever batch -- this is the one
                    // that answers "did Read from here actually land on the
                    // right sentence": compare rawText (what the server
                    // actually started synthesizing, at its resolved
                    // from_sentence_index) against where the aligner then
                    // placed it on the page.
                    val first = chunks[chapterStart]
                    log(
                        "runChapter: chapter=$thisChapter first chunk raw text=\"${first.text}\" " +
                            "isAligned=${first.isAligned} resourceHref=${first.resourceHref} " +
                            "textHighlight=${first.textHighlight} progression=${first.progression}",
                    )
                }

                for (info in newChunks) {
                    val audio = try {
                        api.service().liveChunk(jobId, sessionId, info.index).bytes()
                    } catch (e: CancellationException) {
                        throw e // see runChapter's own startLive catch for why
                    } catch (e: Exception) {
                        continue // a dropped chunk download is not fatal -- the next poll's chunks still queue
                    }
                    val file = files.liveChunk(bookId, sessionId, info.index)
                    file.writeBytes(audio)
                    // PlayerConnection's own doc: every Media3 MediaController call
                    // must happen on the main thread, and it deliberately does not
                    // hop threads itself -- callers are the ones who have to stay on
                    // main. This coroutine runs on Dispatchers.IO (for the network/
                    // file work above), so the one call that actually touches the
                    // controller has to jump back explicitly. Confirmed live as a
                    // real crash without this: IllegalStateException, "MediaController
                    // method is called from a wrong thread."
                    withContext(Dispatchers.Main) { player.appendLiveChunk(bookId, file) }
                    _preparing.value = false
                }
                lastProcessedIndex = newChunks.maxOf { it.index }
            }

            when {
                status.status == "error" -> {
                    fail(status.error ?: "Reading live failed")
                    return
                }
                status.status == "done" && lastProcessedIndex + 1 >= status.totalSentences -> {
                    // This chapter's own session is genuinely finished --
                    // release its pool slot before moving on, or it leaks
                    // for the rest of the reading session (currentSessionId
                    // is about to be overwritten by the next chapter's own,
                    // losing the only reference that could stop this one).
                    releaseSession(sessionId)
                    val next = nextResourceHref(resourceHref)
                    if (next == null) {
                        currentSessionId = null
                        _finished.value = true
                        return
                    }
                    // Soft continuation: no reset, the queue and ChunkIndex
                    // just keep extending into the next resource.
                    runChapter(next, 0)
                    return
                }
                else -> delay(POLL_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 400L

        /** Characters either side of the tap's own exact position, for the
         *  anchor text window `startFromTap` sends server-side (see its own
         *  doc). Generous on purpose: a typical sentence runs well under
         *  this, so the window comfortably covers the tapped sentence plus
         *  its neighbours either side, giving `_resolve_anchor` real
         *  context to pick the closest-to-center match from. */
        private const val ANCHOR_WINDOW_CHARS = 300
    }
}
