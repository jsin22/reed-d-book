# -*- coding: utf-8 -*-
"""TTS backends audiblez can synthesize with.

Each engine wraps one backend's own generate-a-sentence call behind the same
small interface (`synthesize`), so the chapter loop in core.py does not need
to know or care which backend actually produced the audio -- see
_run_chapters_sequential / _run_chapters_parallel there.

Backend imports (kokoro, pocket_tts, supertonic -- torch or onnxruntime, both
heavy) are deferred to inside each class's __init__, not made at module
level, so importing this module -- e.g. just to list engine/voice names, as
server/app/audiblez_meta.py effectively does via the voices modules below --
never pulls in the TTS stack. voices.py, pocket_tts_voices.py and
supertonic_voices.py follow the same discipline for the same reason.
"""

from audiblez import pocket_tts_voices
from audiblez import supertonic_voices
from audiblez import voices as kokoro_voices


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

    Constructing one loads the model -- expensive (seconds for Kokoro,
    tens of seconds to minutes on a cold cache for others) -- which is why
    core.py builds exactly one per pipeline (sequential run) or per worker
    process (parallel run), never per chapter or per sentence.
    """

    #: Sample rate every `synthesize()` call returns audio at, as a *class*
    #: attribute -- fixed per model, and readable via `engine_sample_rate()`
    #: below without constructing (expensive) an instance. audiblez uses one
    #: rate for a whole book's sync timeline and output .wav files, resolved
    #: from the selected engine's class before anything is synthesized (see
    #: main() in core.py) -- Kokoro and Pocket TTS both happen to use 24000,
    #: but Supertonic uses 44100, which is exactly the kind of assumption
    #: that would otherwise have gone unnoticed.
    sample_rate: int

    def __init__(self, voice, threads=None):
        """`threads` is a hint, not a guarantee: a worker process running
        several engines in parallel (core.py's _run_chapters_parallel) passes
        its fair share of CPU cores so this instance does not oversubscribe
        them, but an engine with no way to bound its own thread pool (Kokoro
        and Pocket TTS rely on the process-wide torch.set_num_threads() the
        worker already set instead) can simply ignore it.
        """
        raise NotImplementedError

    def synthesize(self, text, voice, speed):
        """Return a list of numpy audio chunks for one sentence of text, at self.sample_rate."""
        raise NotImplementedError


class KokoroEngine(TTSEngine):
    sample_rate = 24000

    def __init__(self, voice, threads=None):
        from kokoro import KPipeline

        from audiblez.espeak import set_espeak_library
        set_espeak_library()
        self.pipeline = KPipeline(lang_code=voice[0])

    def synthesize(self, text, voice, speed):
        # Kokoro truncates long sentences for non-English languages (English
        # voice codes 'a'/'b' are never affected), so those are pre-split.
        if voice[0] not in 'ab' and len(text) > 400:
            print(f'Warning: Sentence too long ({len(text)} chars), splitting into smaller sentences.')
            parts = split_long_sentence(text, 400)
        else:
            parts = [text]
        segments = []
        for part in parts:
            for _gs, _ps, audio in self.pipeline(part, voice=voice, speed=speed, split_pattern=r'\n\n\n'):
                segments.append(audio)
        return segments


class PocketTTSEngine(TTSEngine):
    """Kyutai's Pocket TTS (github.com/kyutai-labs/pocket-tts): ~100M params,
    designed to run well on CPU rather than needing a GPU to be usable at all.

    `speed` is accepted for interface symmetry with KokoroEngine but has no
    effect -- pocket_tts.TTSModel.generate_audio has no speed control.
    """

    sample_rate = 24000

    def __init__(self, voice, threads=None):
        from pocket_tts import TTSModel
        self.model = TTSModel.load_model()
        self.voice_state = self.model.get_state_for_audio_prompt(voice)

    def synthesize(self, text, voice, speed):
        audio = self.model.generate_audio(self.voice_state, text)
        return [audio.numpy()]


class SupertonicEngine(TTSEngine):
    """Supertone's Supertonic 3 (github.com/supertone-inc/supertonic): ~99M
    params, ONNX Runtime rather than PyTorch. Ten built-in named voices
    (M1-M5, F1-F5), multilingual, and -- unlike Pocket TTS -- `speed` is
    genuinely supported by the backend itself.
    """

    sample_rate = 44100

    def __init__(self, voice, threads=None):
        from supertonic import TTS
        # Not torch: torch.set_num_threads() (what constrains the other two
        # engines' CPU usage per parallel worker) has no effect on an
        # onnxruntime session. This is the equivalent knob for this engine.
        kwargs = {}
        if threads:
            kwargs['intra_op_num_threads'] = threads
            kwargs['inter_op_num_threads'] = 1
        self.tts = TTS(auto_download=True, **kwargs)
        self.voice_style = self.tts.get_voice_style(voice_name=voice)

    def synthesize(self, text, voice, speed):
        wav, _duration = self.tts.synthesize(
            text=text, voice_style=self.voice_style, speed=speed, lang='en')
        # supertonic returns shape (1, samples) -- a leading channel
        # dimension -- rather than the flat (samples,) array Kokoro and
        # Pocket TTS both return. sync.num_frames() already tolerates either
        # shape (it reads shape[-1]), but np.concatenate()'ing several
        # (1, samples) chunks of *different* lengths fails outright, since
        # dimension 0 no longer broadcasts once dimension 1 does not match.
        # Flattened here so every engine hands core.py the same shape.
        return [wav.reshape(-1)]


# engine name -> (engine class, {lang/country code: [voice names]})
ENGINES = {
    'kokoro': (KokoroEngine, kokoro_voices.voices),
    'pocket_tts': (PocketTTSEngine, pocket_tts_voices.voices),
    'supertonic': (SupertonicEngine, supertonic_voices.voices),
}

DEFAULT_ENGINE = 'kokoro'


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
