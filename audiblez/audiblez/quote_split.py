# -*- coding: utf-8 -*-
"""Splits chapter text into (kind, sentence) pairs, kind in {"narration", "quote"},
by finding quoted spans in the *raw* text before sentence-splitting.

Why raw-text-first, not sentence-first: spaCy's sentence boundaries don't
line up with quote boundaries in this kind of dialogue-heavy prose --
"With that I was out of his office, gainfully employed. “See you later,
Ronda,” I said. “Mmmmmm-hmmmmmm,” Ronda replied..." sentence-splits into
fragments that straddle narration and dialogue mid-sentence (a trailing open
quote at the end of a narration sentence, a dialogue tag stuck onto the next
line's quote, etc). Extracting quote spans from the raw text first, then
sentence-splitting *within* each span, means a sentence is never ambiguous
about which side of a quote mark it's on -- and a short interjection like
"Mmmmmm-hmmmmmm," is always its own clean quote span, not one fragment among
many in a sentence a classifier might skim past.
"""
import re

from audiblez.text_split import split_sentences as _split_sentences_plain

# Handles both this project's typical curly-quote epubs (“...”) and a
# plain-ASCII-quote fallback ("..."), non-greedy so back-to-back quotes in
# rapid dialogue ("A" "B" "C") split into three spans, not one.
_QUOTE_RE = re.compile(r'“([^”]*)”|"([^"]*)"')


def split_into_spans(text):
    """Returns [(kind, sentence), ...] covering all of `text` in order,
    kind = "quote" for text inside quote marks, "narration" otherwise.
    Each span is itself sentence-split (via text_split.split_sentences), so
    a multi-sentence quoted monologue yields one (kind, sentence) pair per
    sentence, not one pair for the whole monologue.
    """
    spans = []
    pos = 0
    for m in _QUOTE_RE.finditer(text):
        if m.start() > pos:
            spans.append(('narration', text[pos:m.start()]))
        quoted_text = m.group(1) if m.group(1) is not None else m.group(2)
        spans.append(('quote', quoted_text))
        pos = m.end()
    if pos < len(text):
        spans.append(('narration', text[pos:]))

    result = []
    for kind, span_text in spans:
        for sentence in _split_sentences_plain(span_text):
            result.append((kind, sentence))
    return result
