# -*- coding: utf-8 -*-
"""The few facts about audiblez the API needs to validate a request.

Kept apart from :mod:`app.tasks` because the web process must not import
audiblez' heavy modules -- ``audiblez.voices``, ``audiblez.pocket_tts_voices``
and ``audiblez.supertonic_voices`` are plain dicts importing nothing but the
stdlib, so they are safe to read here even though the engines that actually
use them (``audiblez.engines``, which imports kokoro/torch/pocket_tts/
supertonic, but only inside each engine class's __init__, not at module
level) are not.
"""

from functools import lru_cache

# audiblez' CLI advertises 0.5 to 2.0 (see audiblez/cli.py). Only meaningful
# for engines that support speed control -- Kokoro and Supertonic do; Pocket
# TTS accepts a speed value for API/manifest consistency but ignores it (see
# audiblez.engines.PocketTTSEngine).
MIN_SPEED = 0.5
MAX_SPEED = 2.0

ENGINES = ('kokoro', 'pocket_tts', 'supertonic')
DEFAULT_ENGINE = 'kokoro'

# Kokoro's default voice comes from Settings.default_voice (env-configurable,
# 'af_heart'), since that setting predates multi-engine support and there is
# no reason to add a second knob for the same thing. Every other engine never
# shared that setting, so each gets its own fixed fallback here instead.
DEFAULT_VOICE_BY_ENGINE = {'pocket_tts': 'alba', 'supertonic': 'M1'}


@lru_cache(maxsize=None)
def known_voices(engine=DEFAULT_ENGINE):
    """Every voice the given engine accepts, sorted.

    Returns an empty list for an engine whose voice module isn't importable,
    or that isn't recognized, in which case the API skips validation rather
    than refusing every request -- the server is still useful for testing the
    upload/poll flow without the TTS stack installed.
    """
    try:
        if engine == 'kokoro':
            from audiblez.voices import voices as voices_by_lang
        elif engine == 'pocket_tts':
            from audiblez.pocket_tts_voices import voices as voices_by_lang
        elif engine == 'supertonic':
            from audiblez.supertonic_voices import voices as voices_by_lang
        else:
            return []
    except Exception:
        return []
    return sorted(v for names in voices_by_lang.values() for v in names)
