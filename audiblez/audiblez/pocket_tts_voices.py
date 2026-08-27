# -*- coding: utf-8 -*-
"""Pocket TTS's built-in named voices, with no imports beyond the stdlib.

Mirrors voices.py's role for Kokoro: kept separate from anything that imports
torch, so the web process (see server/app/audiblez_meta.py) can list voices
without paying for the TTS stack.

Copied from Pocket TTS's own catalog (its
pocket_tts.utils.utils._ORIGINS_OF_PREDEFINED_VOICES, cross-checked against
the project README) rather than imported from there, since that module pulls
in torch and huggingface_hub at import time.
"""

#: Not one of Pocket TTS's own catalog above -- Kokoro's af_heart cloned into
#: Pocket TTS via its voice-cloning support (gated on HuggingFace; see
#: PocketTTSEngine in engines.py), so the two engines can offer the same voice
#: identity. Kept as its own name rather than merged into 'en' below so this
#: file's docstring claim -- everything else here really is copied from
#: Pocket TTS's own catalog -- stays true.
CLONED_VOICES = ['af_heart_clone']

voices = {
    'en': ['alba', 'anna', 'azelma', 'bill_boerst', 'caro_davy', 'charles', 'cosette', 'eponine', 'eve',
           'fantine', 'george', 'jane', 'javert', 'jean', 'marius', 'mary', 'michael', 'paul',
           'peter_yearsley', 'stuart_bell', 'vera', *CLONED_VOICES],
    'it': ['giovanni'],
    'es': ['lola'],
    'de': ['juergen'],
    'pt': ['rafael'],
    'fr': ['estelle'],
}
