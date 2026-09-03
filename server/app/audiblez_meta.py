# -*- coding: utf-8 -*-
"""The few facts about audiblez the API needs to validate a request.

Kept apart from :mod:`app.tasks` because the web process must not import
audiblez' heavy modules -- ``audiblez.pocket_tts_voices`` is a plain dict
importing nothing but the stdlib, so it is safe to read here even though
the engine that actually uses it (``audiblez.engines``, which imports
torch/pocket_tts, but only inside the engine class's __init__, not at
module level) is not.

Pocket TTS is the only engine this server ever offers or accepts --
audiblez itself once supported two more (Kokoro, Supertonic 3; see git
history on audiblez/engines.py), but nothing in this project's real usage
ever selected either, and the Android app's own picker had already locked
to Pocket TTS alone (ImportSheet.kt) by the time they were removed.
``ENGINES``/``DEFAULT_VOICE_BY_ENGINE`` stay shaped as a collection keyed
by engine name, even with one entry, so a future second engine is a new
entry here and in audiblez.engines.ENGINES, not a reshape of every call
site that reads these.
"""

from functools import lru_cache

# audiblez' CLI advertises 0.5 to 2.0 (see audiblez/cli.py). Pocket TTS
# accepts a speed value for API/manifest consistency but ignores it (see
# audiblez.engines.PocketTTSEngine) -- kept validated regardless, so the
# contract does not change out from under a client if a future engine
# actually honours it.
MIN_SPEED = 0.5
MAX_SPEED = 2.0

ENGINES = ('pocket_tts',)
DEFAULT_ENGINE = 'pocket_tts'

DEFAULT_VOICE_BY_ENGINE = {'pocket_tts': 'alba'}


@lru_cache(maxsize=None)
def known_voices(engine=DEFAULT_ENGINE):
    """Every voice the given engine accepts, sorted.

    Returns an empty list for an engine whose voice module isn't importable,
    or that isn't recognized, in which case the API skips validation rather
    than refusing every request -- the server is still useful for testing the
    upload/poll flow without the TTS stack installed.
    """
    try:
        if engine == 'pocket_tts':
            from audiblez.pocket_tts_voices import voices as voices_by_lang
        else:
            return []
    except Exception:
        return []
    return sorted(v for names in voices_by_lang.values() for v in names)
