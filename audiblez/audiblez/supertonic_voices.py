# -*- coding: utf-8 -*-
"""Supertonic 3's built-in named voices, with no imports beyond the stdlib.

Mirrors voices.py's role for Kokoro (and pocket_tts_voices.py's for Pocket
TTS): kept separate from anything that imports onnxruntime, so the web
process (see server/app/audiblez_meta.py) can list voices without paying for
the TTS stack.

Supertonic is multilingual (31 languages via its own `lang=` parameter at
synthesis time, not a separate voice per language), so unlike the other two
catalogs this one is not split by language -- these ten names work across all
of them.
"""

voices = {
    'multi': ['M1', 'M2', 'M3', 'M4', 'M5', 'F1', 'F2', 'F3', 'F4', 'F5'],
}
