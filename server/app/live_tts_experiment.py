# -*- coding: utf-8 -*-
"""Experimental: synthesize text sentence-by-sentence with Pocket TTS and make
each chunk available as soon as it's ready, to test whether synthesis can
stay ahead of playback for a live/streaming reading mode -- not a real
feature, just something to try from the "Live TTS" tab on the local
diagnostics dashboard (app.main's /admin/dashboard).

Runs in the web process, not the Celery worker, on purpose: this is a
one-off experiment a person triggers and watches, not a durable job that
needs Celery's own retry/persistence machinery. The heavy audiblez imports
(torch, pocket_tts) stay lazy, inside _run, for the same reason
app.main._synthesize_sample keeps them lazy -- importing this module must
never cost the web process a torch import it does not otherwise need.

Sessions live in memory only (an in-process dict), same lifetime as the
uvicorn process -- a restart loses in-flight experiments, which is fine for
something meant to be watched live, not resumed later.
"""

import io
import threading
import time
import uuid
from dataclasses import dataclass, field

#: Bounds memory: each session holds every synthesized chunk's raw audio in
#: memory until evicted, so a long-running dashboard left open must not
#: accumulate sessions forever.
_MAX_SESSIONS = 20

_sessions: dict[str, 'Session'] = {}
_sessions_lock = threading.Lock()

#: Serializes engine construction when device='cpu' needs to hide the GPU
#: for the duration of `_load_engine` below -- see its own doc.
_engine_load_lock = threading.Lock()


def _load_engine(voice: str, device: str):
    """Loads a Pocket TTS engine, optionally forced onto CPU.

    PocketTTSEngine.__init__ moves its model to CUDA *and* bakes the voice's
    audio-prompt state on whatever device the model is on at that moment
    (get_state_for_audio_prompt), both before this function ever gets a
    handle to the engine -- confirmed live: moving `engine.model` back to
    CPU *afterward* left that already-baked state on CUDA, producing "found
    at least two devices, cuda:0 and cpu" the moment synthesis ran. The only
    way to get a genuinely CPU-only engine is to make CUDA invisible before
    construction, not after -- hence the temporary monkeypatch of
    `torch.cuda.is_available`, held under a lock so two sessions loading
    concurrently (one 'auto', one 'cpu') cannot see each other's patched
    value.
    """
    from audiblez.engines import load_engine

    if device != 'cpu':
        return load_engine('pocket_tts', voice)

    import torch

    with _engine_load_lock:
        original_is_available = torch.cuda.is_available
        torch.cuda.is_available = lambda: False
        try:
            return load_engine('pocket_tts', voice)
        finally:
            torch.cuda.is_available = original_is_available


@dataclass
class Chunk:
    index: int
    ready_at_s: float
    duration_s: float
    chars: int
    audio: bytes


@dataclass
class Session:
    voice: str
    device: str
    started_at: float = field(default_factory=time.time)
    chunks: list[Chunk] = field(default_factory=list)
    status: str = 'running'  # running | done | error
    error: str | None = None
    lock: threading.Lock = field(default_factory=threading.Lock)


def start(text: str, voice: str, device: str = 'auto') -> str:
    """Kicks off synthesis in a background thread and returns a session id
    immediately -- the caller polls `status()` for chunks as they appear.

    `device`: 'auto' (PocketTTSEngine's own default -- GPU if one is visible,
    same as a real conversion) or 'cpu' (forced, for comparing against the
    GPU number from the same machine).
    """
    session_id = uuid.uuid4().hex
    with _sessions_lock:
        _sessions[session_id] = Session(voice=voice, device=device)
        _evict_oldest_locked()
    threading.Thread(target=_run, args=(session_id, text, voice, device), daemon=True).start()
    return session_id


def _evict_oldest_locked() -> None:
    if len(_sessions) <= _MAX_SESSIONS:
        return
    oldest = sorted(_sessions, key=lambda key: _sessions[key].started_at)[: len(_sessions) - _MAX_SESSIONS]
    for key in oldest:
        del _sessions[key]


def _run(session_id: str, text: str, voice: str, device: str) -> None:
    session = _sessions[session_id]
    try:
        import numpy as np
        import soundfile as sf
        from audiblez.engines import engine_sample_rate
        from audiblez.quote_split import split_into_spans

        sample_rate = engine_sample_rate('pocket_tts')
        engine = _load_engine(voice, device)
        sentences = [sentence for _kind, sentence in split_into_spans(text) if sentence.strip()]

        for index, sentence in enumerate(sentences):
            segments = engine.synthesize(sentence, voice, speed=1.0)
            audio = segments[0] if len(segments) == 1 else np.concatenate(segments)
            buffer = io.BytesIO()
            sf.write(buffer, audio, sample_rate, format='WAV')
            chunk = Chunk(
                index=index,
                ready_at_s=time.time() - session.started_at,
                duration_s=len(audio) / sample_rate,
                chars=len(sentence),
                audio=buffer.getvalue(),
            )
            with session.lock:
                session.chunks.append(chunk)
        with session.lock:
            session.status = 'done'
    except Exception as e:  # noqa: BLE001 -- reported to the dashboard, not swallowed
        with session.lock:
            session.status = 'error'
            session.error = str(e)


def status(session_id: str) -> dict | None:
    session = _sessions.get(session_id)
    if session is None:
        return None
    with session.lock:
        return {
            'status': session.status,
            'error': session.error,
            'device': session.device,
            'chunks': [
                {'index': c.index, 'ready_at_s': c.ready_at_s, 'duration_s': c.duration_s, 'chars': c.chars}
                for c in session.chunks
            ],
        }


def chunk_audio(session_id: str, index: int) -> bytes | None:
    session = _sessions.get(session_id)
    if session is None:
        return None
    with session.lock:
        return next((c.audio for c in session.chunks if c.index == index), None)
