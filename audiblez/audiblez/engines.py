# -*- coding: utf-8 -*-
"""TTS backends audiblez can synthesize with.

Pocket TTS is the only one left -- see git history for KokoroEngine and
SupertonicEngine, removed once the Android app's own picker had been
locked to Pocket TTS alone for a while (ImportSheet.kt) and nothing in
this project's real usage ever selected the other two. `TTSEngine` and
`ENGINES`/`load_engine` stay as the shape they were: the chapter loop in
core.py still goes through `synthesize()` rather than calling Pocket TTS
directly, so a future second engine (or reviving one from history) is a
new subclass and a new `ENGINES` entry, not a rewrite of the dispatch.

The backend import (torch, pocket_tts -- both heavy) is deferred to inside
the class's `__init__`, not made at module level, so importing this module
-- e.g. just to list the voice names, as server/app/audiblez_meta.py
effectively does via pocket_tts_voices.py below -- never pulls in the TTS
stack. pocket_tts_voices.py follows the same discipline for the same
reason.
"""

from pathlib import Path

from audiblez import pocket_tts_voices


def split_long_sentence(text, max_length=400):
    """Split a long sentence around the 500 chars, picking the first whitespace after the 500th character."""
    if len(text) <= max_length:
        return [text]
    parts = []
    while len(text) > max_length:
        split_index = text.rfind(' ', 0, max_length)
        if split_index == -1:
            split_index = max_length
        parts.append(text[:split_index].strip())
        text = text[split_index:].strip()
    if text:
        parts.append(text)
    return parts


class TTSEngine:
    """One loaded model, reused across every chapter/sentence a worker handles.

    Constructing one loads the model -- expensive (tens of seconds to
    minutes on a cold cache) -- which is why core.py builds exactly one
    per pipeline (sequential run) or per worker process (parallel run),
    never per chapter or per sentence.
    """

    #: Sample rate every `synthesize()` call returns audio at, as a *class*
    #: attribute -- fixed per model, and readable via `engine_sample_rate()`
    #: below without constructing (expensive) an instance. audiblez uses one
    #: rate for a whole book's sync timeline and output .wav files, resolved
    #: from the selected engine's class before anything is synthesized (see
    #: main() in core.py).
    sample_rate: int

    def __init__(self, voice, threads=None):
        """`threads` is a hint, not a guarantee: a worker process running
        several engines in parallel (core.py's _run_chapters_parallel) passes
        its fair share of CPU cores so this instance does not oversubscribe
        them, but an engine with no way to bound its own thread pool (Pocket
        TTS relies on the process-wide torch.set_num_threads() the worker
        already set instead) can simply ignore it.
        """
        raise NotImplementedError

    def synthesize(self, text, voice, speed):
        """Return a list of numpy audio chunks for one sentence of text, at self.sample_rate."""
        raise NotImplementedError


#: Named voices resolved to a local reference clip rather than one of Pocket
#: TTS's own HF-hosted ones -- see pocket_tts_voices.CLONED_VOICES. Pocket
#: TTS's own name resolution (TTSModel.get_state_for_audio_prompt) only
#: special-cases names in its *own* catalog and otherwise treats a bare str as
#: a URL to fetch, so a local file has to be handed in as a Path to hit its
#: "read this audio and clone it" branch instead.
_CLONED_VOICE_PROMPTS = {
    'af_heart_clone': Path(__file__).parent / 'voice_prompts' / 'af_heart.wav',
}


class PocketTTSEngine(TTSEngine):
    """Kyutai's Pocket TTS (github.com/kyutai-labs/pocket-tts): ~100M params,
    designed to run well on CPU rather than needing a GPU to be usable at all.
    `TTSModel.load_model()` always loads onto CPU regardless -- moved onto
    CUDA here, auto-detected, when one is present.
    Measured 85.5 -> 322.4 chars/sec (~3.8x) on an RTX 2060 eGPU.

    `speed` is accepted for interface symmetry with other engines this
    project has used but has no effect -- pocket_tts.TTSModel.generate_audio
    has no speed control.

    Long sentences get more conservative generation settings than short ones
    (see `_stable_settings_for`): Pocket TTS silently re-splits anything over
    `MAX_TOKEN_PER_CHUNK` tokens into independently-generated chunks with no
    continuity between them (its own source has a TODO acknowledging this),
    and that seam is where a repeated/garbled word has been confirmed, twice,
    on real long sentences (BUGS.md). Short sentences never reach that seam
    and keep the library's own default temperature/noise for full
    expressiveness; only sentences long enough to risk it trade some of that
    away for stability -- confirmed by an A/B listen not to sound bad, just
    less varied.
    """

    sample_rate = 24000

    #: More conservative than the library's own defaults (temp=0.7, no
    #: clamp). Chosen from one A/B comparison, not tuned further -- see
    #: BUGS.md for the samples that led here.
    _STABLE_TEMP = 0.5
    _STABLE_NOISE_CLAMP = 2.5

    def __init__(self, voice, threads=None):
        import torch
        from pocket_tts import TTSModel
        from pocket_tts.default_parameters import MAX_TOKEN_PER_CHUNK
        self.model = TTSModel.load_model()
        if torch.cuda.is_available():
            self.model = self.model.to('cuda')
        audio_prompt = _CLONED_VOICE_PROMPTS.get(voice, voice)
        self.voice_state = self.model.get_state_for_audio_prompt(audio_prompt)
        self._default_temp = self.model.temp
        self._default_noise_clamp = self.model.noise_clamp
        self._tokenizer = self.model.flow_lm.conditioner.tokenizer
        self._max_tokens_per_chunk = MAX_TOKEN_PER_CHUNK

    def synthesize(self, text, voice, speed):
        # self.model.temp/.noise_clamp are read fresh on every generation
        # step (tts_model.py's _run_flow_lm), so mutating them here on the
        # one shared model instance takes effect for this call without
        # needing a second model loaded.
        n_tokens = len(self._tokenizer(text).tokens[0].tolist())
        if n_tokens > self._max_tokens_per_chunk:
            self.model.temp = self._STABLE_TEMP
            self.model.noise_clamp = self._STABLE_NOISE_CLAMP
        else:
            self.model.temp = self._default_temp
            self.model.noise_clamp = self._default_noise_clamp
        audio = self.model.generate_audio(self.voice_state, text)
        return [audio.cpu().numpy()]


# engine name -> (engine class, {lang/country code: [voice names]})
ENGINES = {
    'pocket_tts': (PocketTTSEngine, pocket_tts_voices.voices),
}

DEFAULT_ENGINE = 'pocket_tts'


def load_engine(engine_name, voice, threads=None):
    """Construct and return the named engine, loaded and ready to synthesize."""
    entry = ENGINES.get(engine_name)
    if entry is None:
        raise ValueError(f'unknown engine: {engine_name!r}')
    cls, _voices_by_lang = entry
    return cls(voice, threads=threads)


def known_voices(engine_name):
    """Every voice name the given engine accepts, sorted. Empty list for an unknown engine."""
    entry = ENGINES.get(engine_name)
    if entry is None:
        return []
    _cls, voices_by_lang = entry
    return sorted(v for names in voices_by_lang.values() for v in names)


def engine_sample_rate(engine_name):
    """The sample rate the given engine's audio comes out at, without loading it.

    `sample_rate` is a class attribute precisely so this can be read before
    paying to construct (and for some engines, download) the actual model --
    see main() in core.py, which needs this to build the book's sync timeline
    before any chapter (and so any engine instance) exists yet.
    """
    entry = ENGINES.get(engine_name)
    if entry is None:
        raise ValueError(f'unknown engine: {engine_name!r}')
    cls, _voices_by_lang = entry
    return cls.sample_rate
