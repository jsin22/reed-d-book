# -*- coding: utf-8 -*-
"""Sentence splitting, shared between core.py's synthesis loop and
literary_analysis.py's per-chapter annotation call.

Both need to agree on exactly the same sentence boundaries for a chapter --
literary_analysis.py's output keys sentences by their exact text, and that
only lines up with what gen_audio_segments() actually synthesizes if both
call the same splitter. Pulled out of core.py's gen_audio_segments() (which
used to inline this) for that reason, not for its own sake.
"""
import spacy

#: Loaded once per process, not once per call. `spacy.load()` measured at
#: ~2.6s -- fine for a single call, but split_into_spans() (quote_split.py)
#: calls split_sentences() once per narration/quote span, and a
#: dialogue-heavy chapter can have hundreds of those (536, measured, for one
#: chapter of "A Scandal in Bohemia"). Reloading the model that many times
#: turned a few seconds of real work into 20+ minutes of pure model-loading
#: before any synthesis even started -- invisible to the ETA/progress
#: tracking in core.py, since it all happens before gen_audio_segments()'s
#: own per-sentence loop begins.
_nlp = None


def split_sentences(text):
    """Split `text` into non-empty sentences, in order."""
    global _nlp
    if _nlp is None:
        _nlp = spacy.load('xx_ent_wiki_sm')
        _nlp.add_pipe('sentencizer')
    doc = _nlp(text)
    # spaCy's sentencizer occasionally yields a whitespace-only fragment (a
    # stray newline/punctuation artifact from how the epub's text was
    # extracted); there is no actual sentence there, so it is dropped.
    return [s.text for s in doc.sents if s.text.strip()]
