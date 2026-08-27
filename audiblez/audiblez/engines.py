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

import tempfile
from pathlib import Path

import numpy as np
import soundfile

from audiblez import pocket_tts_voices
from audiblez import supertonic_voices
from audiblez import voices as kokoro_voices

#: A neutral, clean line synthesized once via Kokoro at ExpressiveEngine
#: construction time, to hand CosyVoice3 as its voice-cloning reference --
#: whichever Kokoro voice the book was given becomes the voice CosyVoice3
#: clones, so expressive lines and plain narration sound like the same
#: narrator. Deliberately unrelated to any book's actual content.
_REFERENCE_CLIP_TEXT = "The story continues, carrying us further into its world."


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
    CUDA here, same auto-detect KokoroEngine relies on, when one is present.
    Measured 85.5 -> 322.4 chars/sec (~3.8x) on an RTX 2060 eGPU.

    `speed` is accepted for interface symmetry with KokoroEngine but has no
    effect -- pocket_tts.TTSModel.generate_audio has no speed control.

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


class SupertonicEngine(TTSEngine):
    """Supertone's Supertonic 3 (github.com/supertone-inc/supertonic): ~99M
    params, ONNX Runtime rather than PyTorch. Ten built-in named voices
    (M1-M5, F1-F5), multilingual, and -- unlike Pocket TTS -- `speed` is
    genuinely supported by the backend itself.

    The library hardcodes `CPUExecutionProvider` in its own
    `supertonic.loader.DEFAULT_ONNX_PROVIDERS` (its own comment: "GPU support
    can be added by extending this list") and never exposes a `providers`
    argument through `TTS()`, so CUDA is enabled here by patching that list
    before constructing `TTS`, when onnxruntime actually has CUDA available
    -- same auto-detect KokoroEngine/PocketTTSEngine rely on. Measured
    44.1 -> 541.4 chars/sec (~12x) on an RTX 2060 eGPU. Needs
    `onnxruntime-gpu` (not plain `onnxruntime`) pinned to a version built
    against the CUDA/cuDNN major versions actually installed -- the latest
    onnxruntime-gpu at the time (1.29) silently fell back to CPU because it
    requires CUDA 13, while this machine's torch-provided CUDA libraries are
    12.4; onnxruntime-gpu==1.20.2 is the one confirmed working here.
    """

    sample_rate = 44100

    def __init__(self, voice, threads=None):
        import onnxruntime as ort
        import supertonic.loader as _supertonic_loader
        from supertonic import TTS
        if 'CUDAExecutionProvider' in ort.get_available_providers():
            _supertonic_loader.DEFAULT_ONNX_PROVIDERS = ['CUDAExecutionProvider', 'CPUExecutionProvider']
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


class ExpressiveEngine(TTSEngine):
    """Kokoro for plain narration, CosyVoice3 (cloned from that same Kokoro
    voice) for LLM-flagged expressive lines -- newplan.md's pipeline. See
    audiblez.literary_analysis for how sentences get flagged and
    audiblez.cosyvoice_bridge for why CosyVoice3 has to run out-of-process.

    Unlike the other engines, construction here does real, book-level work
    up front (one local-LLM call per chapter, via Ollama -- see
    audiblez.literary_analysis) rather than just loading a model, which is
    why it needs `chapter_texts` -- every other engine's __init__ only needs
    `voice`. This is also why this engine is not wired into the parallel
    chapter-worker path (_init_chapter_worker in core.py): it is only ever
    constructed once for the whole book, in the main process, matching
    resolve_worker_count()'s GPU behavior of returning 1 worker whenever CUDA
    is available -- true for both Kokoro-on-GPU and CosyVoice3-on-GPU, so the
    parallel path was never going to apply here.

    Construction order matters: the literary-analysis pass runs *before*
    CosyVoice3 loads, not after, so Ollama's model and CosyVoice3 are never
    both resident in the 2060's 6GB VRAM at once -- Ollama's own model
    unloads itself (default keep_alive) once this process stops calling it,
    well before CosyVoice3's subprocess starts up.
    """

    sample_rate = 24000  # Kokoro and CosyVoice3 both confirmed at 24000Hz -- no resampling needed to splice them.

    def __init__(self, voice, threads=None, chapter_texts=None):
        if not chapter_texts:
            raise ValueError(
                'ExpressiveEngine needs chapter_texts (the whole book\'s chapter texts, '
                'for its one-local-LLM-call-per-chapter annotation pass) -- got none. '
                'Pass engine="expressive" through core.main(), not load_engine() directly.')

        from audiblez import literary_analysis
        from audiblez.cosyvoice_bridge import CosyVoiceBridge
        from audiblez.quote_split import split_into_spans

        self.kokoro = KokoroEngine(voice, threads=threads)

        print(f'Analyzing {len(chapter_texts)} chapter(s) for expressive delivery moments...')
        self.annotations = literary_analysis.analyze_book(chapter_texts, split_into_spans)
        print(f'Expressive delivery: {len(self.annotations)} sentence(s) flagged across the whole book.')

        reference_segments = self.kokoro.synthesize(_REFERENCE_CLIP_TEXT, voice, speed=1.0)
        reference_audio = np.concatenate(reference_segments) if len(reference_segments) > 1 else reference_segments[0]
        self._reference_wav_path = tempfile.NamedTemporaryFile(suffix='.wav', delete=False).name
        soundfile.write(self._reference_wav_path, reference_audio, self.kokoro.sample_rate)

        self.cosyvoice = CosyVoiceBridge(self._reference_wav_path)

    def synthesize(self, text, voice, speed):
        instruction = self.annotations.get(text)
        if instruction is None:
            return self.kokoro.synthesize(text, voice, speed)
        return [self.cosyvoice.synthesize(text, instruction)]


# engine name -> (engine class, {lang/country code: [voice names]})
ENGINES = {
    'kokoro': (KokoroEngine, kokoro_voices.voices),
    'pocket_tts': (PocketTTSEngine, pocket_tts_voices.voices),
    'supertonic': (SupertonicEngine, supertonic_voices.voices),
    # Narrator voice is still a plain Kokoro voice name -- ExpressiveEngine
    # only changes delivery on flagged lines, never voice identity.
    'expressive': (ExpressiveEngine, kokoro_voices.voices),
}

DEFAULT_ENGINE = 'kokoro'


def load_engine(engine_name, voice, threads=None, **kwargs):
    """Construct and return the named engine, loaded and ready to synthesize.

    `**kwargs` exists only for ExpressiveEngine's `chapter_texts` -- every
    other engine's __init__ takes just (voice, threads), so passing kwargs
    for those would raise TypeError; callers should only pass kwargs when
    engine_name == 'expressive'.
    """
    entry = ENGINES.get(engine_name)
    if entry is None:
        raise ValueError(f'unknown engine: {engine_name!r}')
    cls, _voices_by_lang = entry
    return cls(voice, threads=threads, **kwargs)


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
