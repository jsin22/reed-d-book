# -*- coding: utf-8 -*-
"""The few facts about audiblez the API needs to validate a request.

Kept apart from :mod:`app.tasks` because the web process must not import
audiblez' heavy modules -- ``audiblez.voices`` is a plain dict and imports
nothing but ``platform``, so it is safe to read here.
"""

from functools import lru_cache

# audiblez' CLI advertises 0.5 to 2.0 (see audiblez/cli.py).
MIN_SPEED = 0.5
MAX_SPEED = 2.0


@lru_cache(maxsize=1)
def known_voices():
    """Every Kokoro voice audiblez accepts, sorted.

    Returns an empty list if audiblez isn't importable, in which case the API
    skips validation rather than refusing every request -- the server is still
    useful for testing the upload/poll flow without the TTS stack installed.
    """
    try:
        from audiblez.voices import voices as voices_by_lang
    except Exception:
        return []
    return sorted(v for names in voices_by_lang.values() for v in names)
