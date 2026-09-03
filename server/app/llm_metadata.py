# -*- coding: utf-8 -*-
"""Category/genre lookup via the Gemini API -- the sole source now (see
LLM_GENRE_ENRICHMENT.md for the full investigation behind this: Open
Library/Google Books were removed, not layered under, after live testing
across several models/approaches showed `gemini-3.1-flash-lite`, with a
constrained genre vocabulary and per-genre confidence scores, was clearly
the most accurate and most reliably fast of everything tried).

stdlib `urllib.request` only, same precedent as every other module here --
Gemini's REST API is plain HTTP+JSON, no client library needed.
"""

import json
import urllib.error
import urllib.request

# Confirmed live: gemini-3.1-flash-lite answers in well under a few
# seconds once warm. This only ever runs off the request path
# (BackgroundTasks, or the offline backfill script), so there is no cost
# to a generous margin over what was actually measured.
_TIMEOUT_SECONDS = 45

# A genre is kept only at this confidence or above (1-10 scale, the model
# is asked to score honestly). Chosen live: at confidence 7+, every tag
# observed across a real mixed fiction/non-fiction test batch was a
# correct, specific fit; scores of 5-6 included real but weak/generic
# associations (e.g. "Politics" at 5 for a big-history book) not worth
# surfacing as a filterable tag.
_CONFIDENCE_THRESHOLD = 7

# The fixed vocabulary the filter UI's chips understand. The last 7 were
# added after live testing against non-fiction titles kept surfacing
# "Philosophy"/"Psychology" with real confidence even under an explicit
# "only use this list" instruction -- the original 20 leaned fiction-heavy
# and had no good analog for either. Also promotes several words the
# pre-Gemini, Open-Library/Google-Books-based lookup only ever treated as
# non-fiction *signals* (see LLM_GENRE_ENRICHMENT.md) into actual genre
# *tags* here.
_GENRES = (
    'Science Fiction', 'Short Stories', 'Young Adult', 'Self-Help',
    'Historical Fiction', 'History', 'Biography', 'Horror', 'Mystery',
    'Thriller', 'Romance', 'Fantasy', 'Science', 'Poetry', 'Drama',
    'Classic', 'Humor', 'Adventure', 'Crime', 'War',
    'Philosophy', 'Psychology', 'Business', 'Politics', 'Religion',
    'Travel', 'Essays',
)

_PROMPT = """You are a librarian. Given a book's title and author, respond \
with ONLY a JSON object, no other text: {{"category": "Fiction" or \
"Non-fiction" or null, "genres": [{{"genre": one of {genres}, \
"confidence": an integer 1-10, where 10 means you are certain this genre \
precisely applies and 1 means it is a weak/loose association}}]}}. You \
must only use genres from that exact list -- if a genre you would \
naturally use for this book is not on the list, map it to the closest \
one that is instead of inventing a new tag (for example: Psychological \
Thriller -> Thriller, Detective Fiction -> Mystery, Gothic Fiction -> \
Horror, Suspense -> Thriller, Classic Literature -> Classic, Contemporary \
Fiction -> Drama). Include Short Stories if the work is a short story or \
a short story collection, not a novel. You must include at least 3 \
genres in your answer -- think broadly about every angle the book fits, \
not just the single most obvious one, and score each one honestly even \
if that means some scores are low. If you do not recognize the book at \
all, respond exactly {{"category": null, "genres": []}} -- never guess.

Title: {title}
Author: {author}"""


def _prompt_for(title: str, author: str | None) -> str:
    return _PROMPT.format(genres=', '.join(_GENRES), title=title, author=author or 'unknown')


def _parse_response(text: str) -> dict | None:
    """The model's response text, expected to be exactly one JSON object
    (responseMimeType="application/json") -- still validated by hand, not
    trusted: a category or genre outside the known set would otherwise
    leak straight into the filter UI's options, and a below-threshold
    genre would defeat the whole point of asking for confidence scores.

    Returns None if the text is not a well-formed object at all --
    treated as a parse failure by the caller (see query_gemini), not a
    legitimate "nothing found" answer.
    """
    try:
        parsed = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        return None
    if not isinstance(parsed, dict):
        return None

    category = parsed.get('category')
    if category not in ('Fiction', 'Non-fiction', None):
        category = None

    genres = []
    for entry in parsed.get('genres') or []:
        if not isinstance(entry, dict):
            continue
        genre = entry.get('genre')
        confidence = entry.get('confidence')
        if genre not in _GENRES or not isinstance(confidence, (int, float)):
            continue
        if confidence >= _CONFIDENCE_THRESHOLD and genre not in genres:
            genres.append(genre)

    return {'category': category, 'genres': genres}


def query_gemini(title: str, author: str | None, settings) -> dict:
    """Raises on any failure to reach Gemini or parse a well-formed
    response: network error, timeout, non-2xx status, no API key
    configured, or a response that isn't the expected JSON shape at all.
    The caller (book_metadata.lookup) treats that as LookupUnavailable --
    Gemini is the only source now, so its failure means the lookup
    genuinely could not happen this time, not "try the next source."

    A well-formed response -- even one saying "I don't recognize this
    book" ({'category': None, 'genres': []}), or one where every genre
    scored below the confidence floor -- returns normally. That is a
    genuine negative, safe to cache forever.
    """
    if not settings.gemini_api_key:
        raise ValueError('no Gemini API key configured (REEDD_GEMINI_API_KEY)')

    body = json.dumps({
        'contents': [{'parts': [{'text': _prompt_for(title, author)}]}],
        'generationConfig': {'responseMimeType': 'application/json'},
    }).encode('utf-8')
    url = (
        'https://generativelanguage.googleapis.com/v1beta/models/'
        f'{settings.gemini_model}:generateContent?key={settings.gemini_api_key}'
    )
    request = urllib.request.Request(url, data=body, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=_TIMEOUT_SECONDS) as response:
        payload = json.loads(response.read().decode('utf-8'))

    text = payload['candidates'][0]['content']['parts'][0]['text']
    result = _parse_response(text)
    if result is None:
        raise ValueError(f'Gemini returned an unparseable response for {title!r}')
    result['raw'] = payload
    return result
