# -*- coding: utf-8 -*-
"""Read a book live: synthesize sentence by sentence, on demand, instead of
converting the whole thing first. See PURRFECT_HOPPING_UNICORN's plan
(saved 2026-09-07) for the full design; this module is Phase 1 of it --
the server-side synthesis service, independent of the upload-mode and
Android work that come after.

CPU only, always -- never the GPU. This machine has one GPU, so a live
session would monopolize it for a single listener; CPU is what lets
several people read live at the same time (see EnginePool below).

A session is one listener reading one chapter of one book. It keeps a
short read-ahead buffer of synthesized sentences (not the whole rest of
the chapter, which would waste CPU on a chapter nobody may finish) and
tops it up as the reader's own position (`advance`) moves forward. An
idle session (no `advance` call in a while -- the app closed without
calling `stop`, or the reader just stopped) self-releases its pool slot
on a timer, so a dropped connection cannot permanently claim a slot.

Sessions live in memory only (an in-process dict), same lifetime as the
uvicorn process -- a restart drops every in-flight live session, which is
fine for something meant to be listened to live, not resumed later
(cross-device/process resume for a live-only book is explicitly out of
scope for phase 1; see the plan).
"""

import io
import threading
import time
import uuid
from dataclasses import dataclass, field

#: How far ahead of the reader's own position to keep synthesized, in
#: seconds of audio -- a short buffer, not the whole chapter, so a reader
#: who closes the book after one paragraph does not leave the CPU
#: synthesizing the rest of it unattended.
READ_AHEAD_TARGET_SECONDS = 45.0

#: A session with no `advance()` call this long self-releases its pool
#: slot -- the app closing without a clean `stop()` call (killed, network
#: drop) must not permanently strand a slot.
IDLE_TIMEOUT_SECONDS = 120.0

_REAPER_INTERVAL_SECONDS = 15.0


class Busy(Exception):
    """No free engine slot right now -- see EnginePool."""


class UnknownChapter(Exception):
    """resource_href matches no chapter in this book's epub."""


def _load_cpu_engine(voice: str):
    """Loads a Pocket TTS engine forced onto CPU.

    PocketTTSEngine.__init__ moves its model to CUDA *and* bakes the
    voice's audio-prompt state onto whatever device the model is on at
    that moment, both before this function gets a handle to the engine --
    the only way to get a genuinely CPU-only engine is to make CUDA
    invisible before construction, not after (confirmed the hard way
    while building the Live TTS dashboard experiment: moving the model
    back afterward left mismatched-device tensors and crashed on first
    synthesis). Held under `_engine_load_lock` so two engines loading
    concurrently cannot see each other's patched value.
    """
    import torch
    from audiblez.engines import load_engine

    with _engine_load_lock:
        original_is_available = torch.cuda.is_available
        torch.cuda.is_available = lambda: False
        try:
            return load_engine('pocket_tts', voice)
        finally:
            torch.cuda.is_available = original_is_available


_engine_load_lock = threading.Lock()


class _PoolSlot:
    def __init__(self):
        self.in_use = False
        self.voice: str | None = None
        self.engine = None


class EnginePool:
    """A fixed number of slots, each holding at most one loaded engine.

    Reused across sessions when the voice matches (no reload cost); a slot
    is reloaded for a different voice only when it is actually claimed for
    one. The slot count is the hard concurrency cap on concurrent live
    sessions -- `acquire` raises `Busy` rather than loading more than that.
    """

    def __init__(self, size: int):
        self._slots = [_PoolSlot() for _ in range(size)]
        self._lock = threading.Lock()

    def acquire(self, voice: str) -> tuple[int, object]:
        with self._lock:
            chosen = self._pick_locked(voice)
            if chosen is None:
                raise Busy()
            self._slots[chosen].in_use = True
        # Loading (only on a voice change) happens outside the pool lock --
        # it can take seconds, and must not block other sessions' acquire().
        slot = self._slots[chosen]
        if slot.voice != voice or slot.engine is None:
            slot.engine = _load_cpu_engine(voice)
            slot.voice = voice
        return chosen, slot.engine

    def _pick_locked(self, voice: str) -> int | None:
        for i, slot in enumerate(self._slots):
            if not slot.in_use and slot.voice == voice and slot.engine is not None:
                return i
        for i, slot in enumerate(self._slots):
            if not slot.in_use:
                return i
        return None

    def release(self, slot_index: int) -> None:
        with self._lock:
            self._slots[slot_index].in_use = False


_pool_lock = threading.Lock()
_pool: EnginePool | None = None


def _get_pool() -> EnginePool:
    global _pool
    with _pool_lock:
        if _pool is None:
            from .config import get_settings
            _pool = EnginePool(get_settings().live_reading_max_sessions)
        return _pool


@dataclass
class Chunk:
    index: int
    duration_s: float
    chars: int
    text: str
    audio: bytes


@dataclass
class Session:
    session_id: str
    book_id: str
    resource_href: str
    voice: str
    sentences: list[str]
    slot_index: int
    cursor: int  # the sentence index the reader is currently at
    next_to_synthesize: int
    chunks: dict[int, Chunk] = field(default_factory=dict)
    status: str = 'running'  # running | done | error | stopped
    error: str | None = None
    last_advance_at: float = field(default_factory=time.time)
    lock: threading.RLock = field(default_factory=threading.RLock)
    wake: threading.Condition = field(init=False)
    thread: threading.Thread | None = None  # set right after construction, joined by shutdown()

    def __post_init__(self):
        self.wake = threading.Condition(self.lock)


_sessions: dict[str, Session] = {}
_sessions_lock = threading.Lock()


def _extract_sentences(epub_path, resource_href: str) -> list[str]:
    """Chapter text via the exact same, already-battle-tested extraction
    audiblez uses for a real conversion -- no new epub-parsing code.

    Resolved by resource filename, not numeric position: audiblez's own
    chapter enumeration (ebooklib's spine, filtered to ITEM_DOCUMENT) and
    Readium's `publication.readingOrder` on the Android side are two
    different parsers over two different spine representations, with no
    guarantee they enumerate identically. Matching bare filenames is the
    same reconciliation `dev.reedd.domain.PublicationHrefs.kt`'s
    `bareResourceName()` already relies on for this exact kind of
    cross-system href mismatch.
    """
    from pathlib import PurePosixPath

    from ebooklib import epub

    from audiblez.core import find_document_chapters_and_extract_texts
    from audiblez.quote_split import split_into_spans

    book = epub.read_epub(str(epub_path))
    chapters = find_document_chapters_and_extract_texts(book)
    target_name = PurePosixPath(resource_href).name
    chapter = next((c for c in chapters if PurePosixPath(c.get_name()).name == target_name), None)
    if chapter is None:
        raise UnknownChapter(f'no chapter matches resource_href={resource_href!r}')
    return [sentence for _kind, sentence in split_into_spans(chapter.extracted_text) if sentence.strip()]


def _normalize_for_anchor(text: str) -> str:
    """A light fold, Python-side counterpart to `dev.reedd.data.align.
    TextNormalizer` -- not as thorough (this only ever compares a short
    window against single sentences, not a whole chapter), but enough to
    survive the typographic differences a raw epub and Android's jsoup
    extraction commonly disagree on."""
    import re

    folded = (
        text.lower()
        .replace('‘', "'").replace('’', "'").replace('‛', "'").replace('′', "'")
        .replace('“', '"').replace('”', '"').replace('‟', '"').replace('″', '"')
        .replace('–', '-').replace('—', '-').replace('‒', '-').replace('―', '-').replace('−', '-')
        .replace('…', '...')
    )
    return re.sub(r'\s+', ' ', folded).strip()


#: Sentences shorter than this (after normalizing) are skipped as anchor
#: candidates -- too short to confidently place ("Dead!" appears more than
#: once in a novel; a real, confirmed-live false-positive risk otherwise.
_MIN_ANCHOR_SENTENCE_CHARS = 8


def _resolve_anchor(sentences: list[str], anchor_text: str, anchor_offset: int | None = None) -> int | None:
    """The sentence index whose own text sits closest to *where the reader
    actually tapped* inside [anchor_text]. Unlike `from_fraction` (a
    proportional guess that drifts when this function's sentence count
    disagrees with Android's own text-length count -- a real, confirmed-live
    gap on a resource spanning many chapters), this is an exact text match: a
    sentence is a candidate only if it appears verbatim (after light
    normalization) inside the anchor window, and the one closest to the tap
    wins -- the sentence the reader actually tapped into, not a neighbour the
    window also happened to catch.

    [anchor_offset], when given, is the tap's own raw character offset within
    [anchor_text] -- *not* generally its midpoint: Android's window is
    clamped at either edge of the resource's own text, so a tap near the
    very start or end of a resource produces a window with the tap sitting
    well off-center. Confirmed live as a real bug: a tap on a resource's
    first line, with the window clamped to `[0, tap+300)`, resolved to a
    sentence past the one actually tapped because this function assumed the
    tap sat at the window's own midpoint. Scaled proportionally from raw
    offset/length into normalized offset/length, since normalization
    (whitespace collapsing, quote/dash/ellipsis folding) can shift absolute
    positions slightly -- close enough to pick the right sentence, which is
    all this needs. Falls back to the window's own center when not given
    (e.g. an older client, or a selection with no single tap point).
    """
    normalized_anchor = _normalize_for_anchor(anchor_text)
    if not normalized_anchor:
        return None
    if anchor_offset is not None and anchor_text:
        center = anchor_offset / len(anchor_text) * len(normalized_anchor)
    else:
        center = len(normalized_anchor) / 2
    best_index: int | None = None
    best_distance: float | None = None
    for i, sentence in enumerate(sentences):
        normalized_sentence = _normalize_for_anchor(sentence)
        if len(normalized_sentence) < _MIN_ANCHOR_SENTENCE_CHARS:
            continue
        pos = normalized_anchor.find(normalized_sentence)
        if pos < 0:
            continue  # only a sentence fully inside the window counts as a match
        distance = abs((pos + len(normalized_sentence) / 2) - center)
        if best_distance is None or distance < best_distance:
            best_distance = distance
            best_index = i
    return best_index


def start(
    book_id: str,
    epub_path,
    resource_href: str,
    from_sentence_index: int,
    voice: str,
    from_fraction: float | None = None,
    anchor_text: str | None = None,
    anchor_offset: int | None = None,
) -> tuple[str, int]:
    """Claims a pool slot and starts synthesizing forward from
    `from_sentence_index`, in a background thread. Raises `Busy` if every
    slot is already in use, `UnknownChapter` if resource_href matches no
    chapter in this epub.

    `anchor_text`, when given, is tried first -- see `_resolve_anchor`'s own
    doc. It is an *exact* text match, immune to the sentence-count mismatch
    that makes `from_fraction` alone drift (confirmed live: several
    percentage points of drift on a resource spanning many chapters was
    enough to land several sentences away from the tapped word). Falls back
    to `from_fraction` (or `from_sentence_index`) if no sentence is found
    inside the anchor window -- a short or generic tapped word can produce a
    window with nothing long enough in it to confidently place.

    `from_fraction` (0..1), when given (and no `anchor_text` match), overrides
    `from_sentence_index`: resolved here, against this chapter's *real*
    sentence list, rather than estimated on the Android side against its own
    jsoup-extracted text -- that text's total length does not exactly match
    this function's own BeautifulSoup-based extraction, so only a proportion
    (not an absolute character offset) survives the two parsers disagreeing.

    @return (session_id, the actual starting sentence index) -- the caller
    needs the resolved index back (not just whatever it originally asked
    for) to keep its own chunk-ordinal bookkeeping correct.
    """
    pool = _get_pool()
    slot_index, engine = pool.acquire(voice)
    try:
        sentences = _extract_sentences(epub_path, resource_href)
    except Exception:
        pool.release(slot_index)
        raise

    anchor_index = _resolve_anchor(sentences, anchor_text, anchor_offset) if anchor_text else None
    if anchor_index is not None:
        resolved_index = anchor_index
    elif from_fraction is not None and sentences:
        resolved_index = min(len(sentences) - 1, max(0, int(from_fraction * len(sentences))))
    else:
        resolved_index = max(0, from_sentence_index)

    session_id = uuid.uuid4().hex
    session = Session(
        session_id=session_id,
        book_id=book_id,
        resource_href=resource_href,
        voice=voice,
        sentences=sentences,
        slot_index=slot_index,
        cursor=resolved_index,
        next_to_synthesize=resolved_index,
    )
    with _sessions_lock:
        _sessions[session_id] = session
    # daemon=True so a launch path that never calls shutdown() (a one-off
    # script, an unclean kill) does not hang the process forever -- but see
    # shutdown() below for why that alone is not enough for a clean stop.
    session.thread = threading.Thread(target=_worker, args=(session_id, engine), daemon=True)
    session.thread.start()
    _ensure_reaper_started()
    return session_id, resolved_index


def _buffered_seconds_locked(session: Session) -> float:
    return sum(c.duration_s for i, c in session.chunks.items() if i >= session.cursor)


def _worker(session_id: str, engine) -> None:
    session = _sessions[session_id]
    try:
        import numpy as np
        import soundfile as sf
        from audiblez.engines import engine_sample_rate

        sample_rate = engine_sample_rate('pocket_tts')
        while True:
            with session.wake:
                while (
                    session.status == 'running'
                    and session.next_to_synthesize < len(session.sentences)
                    and _buffered_seconds_locked(session) >= READ_AHEAD_TARGET_SECONDS
                ):
                    session.wake.wait(timeout=5.0)
                if session.status != 'running':
                    return
                if session.next_to_synthesize >= len(session.sentences):
                    session.status = 'done'
                    return
                index = session.next_to_synthesize
                sentence = session.sentences[index]

            # Synthesize outside the lock -- the slow, CPU-bound part must
            # not block advance()/status() calls from other threads.
            segments = engine.synthesize(sentence, session.voice, speed=1.0)
            audio = segments[0] if len(segments) == 1 else np.concatenate(segments)
            buffer = io.BytesIO()
            sf.write(buffer, audio, sample_rate, format='WAV')
            chunk = Chunk(
                index=index, duration_s=len(audio) / sample_rate, chars=len(sentence),
                text=sentence, audio=buffer.getvalue(),
            )

            with session.wake:
                if session.status != 'running':
                    return
                session.chunks[index] = chunk
                session.next_to_synthesize = index + 1
    except Exception as e:  # noqa: BLE001 -- reported to the client, not swallowed
        with session.wake:
            session.status = 'error'
            session.error = str(e)
    finally:
        _get_pool().release(session.slot_index)


def advance(session_id: str, now_at_sentence_index: int) -> dict | None:
    """Tells the session where the reader actually is now -- prunes
    already-consumed chunks (bounds memory) and wakes the worker to top the
    buffer back up if that opened room. Returns the same shape as
    `status()`, or None for an unknown session."""
    session = _sessions.get(session_id)
    if session is None:
        return None
    with session.wake:
        session.cursor = max(session.cursor, now_at_sentence_index)
        session.last_advance_at = time.time()
        for index in [i for i in session.chunks if i < session.cursor]:
            del session.chunks[index]
        session.wake.notify_all()
    return status(session_id)


def status(session_id: str) -> dict | None:
    session = _sessions.get(session_id)
    if session is None:
        return None
    with session.wake:
        return {
            'status': session.status,
            'error': session.error,
            'resource_href': session.resource_href,
            'cursor': session.cursor,
            'total_sentences': len(session.sentences),
            'chunks': [
                {'index': i, 'duration_s': c.duration_s, 'chars': c.chars, 'text': c.text}
                for i, c in sorted(session.chunks.items())
            ],
        }


def chunk_audio(session_id: str, index: int) -> bytes | None:
    session = _sessions.get(session_id)
    if session is None:
        return None
    with session.wake:
        chunk = session.chunks.get(index)
        return chunk.audio if chunk else None


def stop(session_id: str) -> None:
    """Releases the session's pool slot (once its worker notices) and
    forgets the session. Safe to call more than once."""
    with _sessions_lock:
        session = _sessions.pop(session_id, None)
    if session is None:
        return
    with session.wake:
        session.status = 'stopped'
        session.wake.notify_all()


#: How long shutdown() waits for a worker to notice it's been told to stop
#: and return -- generous relative to one sentence's synthesis time (the
#: longest a worker can be blocked before it next checks session.status).
SHUTDOWN_JOIN_TIMEOUT_SECONDS = 10.0


def shutdown() -> None:
    """Stops every active session and waits for its worker thread to
    actually exit before returning.

    Confirmed the hard way: a worker thread killed mid-synthesize() (a
    blocking native torch call) when the interpreter exits -- which is
    exactly what an unjoined daemon thread gets at process shutdown --
    aborts the whole process ("terminate called without an active
    exception"), not a catchable Python exception. Wired into FastAPI's own
    shutdown event (app.main) so `systemctl restart reedd-uvicorn` while
    someone is reading live stops cleanly instead of crashing.
    """
    with _sessions_lock:
        sessions = list(_sessions.values())
    for session in sessions:
        stop(session.session_id)
    for session in sessions:
        if session.thread is not None:
            session.thread.join(timeout=SHUTDOWN_JOIN_TIMEOUT_SECONDS)


_reaper_lock = threading.Lock()
_reaper_started = False


def _ensure_reaper_started() -> None:
    global _reaper_started
    with _reaper_lock:
        if _reaper_started:
            return
        threading.Thread(target=_reaper_loop, daemon=True).start()
        _reaper_started = True


def _reaper_loop() -> None:
    while True:
        time.sleep(_REAPER_INTERVAL_SECONDS)
        now = time.time()
        with _sessions_lock:
            stale_ids = [
                session_id for session_id, session in _sessions.items()
                if now - session.last_advance_at > IDLE_TIMEOUT_SECONDS
            ]
        for session_id in stale_ids:
            stop(session_id)
