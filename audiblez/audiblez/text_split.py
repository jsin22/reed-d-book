# -*- coding: utf-8 -*-
"""Sentence splitting, shared between core.py's synthesis loop and
literary_analysis.py's per-chapter annotation call.

Both need to agree on exactly the same sentence boundaries for a chapter --
literary_analysis.py's output keys sentences by their exact text, and that
only lines up with what gen_audio_segments() actually synthesizes if both
call the same splitter. Pulled out of core.py's gen_audio_segments() (which
used to inline this) for that reason, not for its own sake.
"""
import re

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


#: Titles that are always followed by a name, never the end of a sentence.
#: spaCy's rule-based sentencizer ends a sentence at every period, so "Mr.
#: Utterson" came out as two sentences -- synthesized separately, with an
#: audible pause between them (167 such splits in Dr. Jekyll and Mr. Hyde).
#: Deliberately only titles: "No." and single initials ("said I.") end real
#: sentences too often to merge blindly.
_TITLE_ABBREVIATIONS = (
    'Mr', 'Mrs', 'Ms', 'Messrs', 'Dr', 'Prof', 'Rev', 'Revd', 'Fr', 'St',
    'Sr', 'Jr', 'Mt', 'Capt', 'Col', 'Gen', 'Lt', 'Sgt', 'Cpl', 'Maj',
    'Adm', 'Gov', 'Sen', 'Rep', 'Hon', 'Mme', 'Mlle', 'Mssrs',
)
_ENDS_WITH_TITLE = re.compile(r'(?:^|[\s(\[\'"])(?:' + '|'.join(_TITLE_ABBREVIATIONS) + r')\.\s*$')


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
    spans = [(s.start_char, s.end_char) for s in doc.sents if s.text.strip()]
    return [text[start:end] for start, end in _merge_title_splits(text, spans)]


def _merge_title_splits(text, spans):
    """Rejoin a sentence that was cut right after a title ("...and Mr.")
    to the one that follows it, by character position so the whitespace
    between them is kept exactly as written."""
    merged = []
    for start, end in spans:
        if merged and _ENDS_WITH_TITLE.search(text[merged[-1][0]:merged[-1][1]]):
            merged[-1] = (merged[-1][0], end)
        else:
            merged.append((start, end))
    return merged
