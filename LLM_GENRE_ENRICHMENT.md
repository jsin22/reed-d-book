# Category/genre lookup: Gemini-only

Scoped 2026-08-31 after extensive live experimentation; not yet
implemented. **Supersedes the Ollama-enrichment-on-top-of-Open-Library
design** this doc originally described (see "How we got here" below) --
Open Library and Google Books are being removed, not layered under.

## Decision

`app/book_metadata.py`'s `lookup(title, author)` becomes a single call to
`gemini-3.1-flash-lite`, asked for category plus a confidence-scored list
of genres from a fixed vocabulary. No more Open Library, no more Google
Books, no more Ollama. Genres below confidence 7 are dropped.

## How we got here

Open Library/Google Books' real problem wasn't reachability, it was
*completeness*: correct but inconsistent cataloguing (two of five uploaded
short stories never got tagged "Short Stories" despite obviously being
one). Layering an LLM enrichment pass on top (this doc's original plan)
fixed some of that, but live testing kept surfacing more that only a
genuinely capable model closed:

- **Ollama (`qwen2.5:7b-instruct`, local)**: good baseline, but hallucinated
  a "Short Stories" tag onto *Hidden Pictures* (an actual novel) -- title/
  author alone, with no way to verify.
- **DeBERTa-v3-large-zeroshot** (reads real epub text, three sampling
  strategies tried: bounded opening, full-text averaged, middle-chapter):
  fast but confidently wrong on real cases (ranked "Romance" above
  "Adventure" for *The Count of Monte Cristo*; full-text averaging
  actively erased the Short Stories signal by diluting it across chunks
  that don't carry it). Reading a whole long novel chunk-by-chunk also
  measured at ~23 minutes, competing with the same GPU as Pocket TTS.
- **Gemini 3.6 Flash (cloud, free tier)**: the best raw accuracy of
  anything tried -- correctly caught *A Scandal in Bohemia*'s missing
  Short Stories tag (nothing else did) and avoided Hidden Pictures'
  false positive entirely. But its free quota turned out to be **20
  requests/day for that specific model**, not the ~1,500/day the public
  docs suggest for "Flash" generally, and its latency was wildly
  inconsistent (1-27s, half of an 8-book batch timed out at 30s) until a
  `thinkingLevel: "minimal"` override fixed it.
- **Gemini 3.1 Flash-Lite**: same accuracy as the flagship model, none of
  the latency problems (consistently sub-second to a few seconds), and a
  genuinely usable **~1,500 free requests/day**. This is what the plan
  below uses.

Two prompt refinements, both confirmed live before locking in:

- **Explicit "map, don't invent" instruction** for anything outside the
  fixed vocabulary (e.g. "Psychological Thriller -> Thriller"). Freeform
  (no vocabulary constraint at all) produced qualitatively *richer* tags
  ("Gothic Fiction," "Revenge," "Dark Humor") but almost none of them
  matched the UI's filter chips, and it flatly refused to answer on one
  book it answered fine under the constrained version -- confirmed the
  fixed vocabulary is right for a filter chip list; freeform's richness
  wants a different feature (raw keywords on a detail screen), not this
  one.
- **Per-genre confidence score (1-10), not a flat include/exclude list.**
  A flat "at least 3 genres" rule fixed thin results but caused
  non-fiction books specifically to get padded with a weak, generic
  "Biography" tag just to hit the count (*Atomic Habits*, *Sapiens*).
  Confidence scores let the model be honest about weak fits instead of
  hiding them behind a forced count, and asking for that honesty
  revealed a real gap in the original 20-tag vocabulary: it surfaced
  "Philosophy"/"Psychology" for non-fiction books with genuine
  confidence (8 and 6) even though neither was on the list. Rather than
  just drop them, the vocabulary was expanded with 7 non-fiction
  categories (Philosophy, Psychology, Business, Politics, Religion,
  Travel, Essays) -- notably, `book_metadata.py`'s own category-detection
  heuristic already treated several of these words as non-fiction
  *signals* internally (`_NONFICTION_MARKERS`), they had just never been
  promoted to genre *tags* a user could filter by. Retested against a
  mix of fiction and non-fiction titles with the expanded list: zero
  off-vocabulary leaks, well-calibrated scores throughout (e.g. Politics
  correctly ranked as *Sapiens*' weakest fit at 5, not overstated).

## Design

### The fixed vocabulary (27 tags)

```
Science Fiction, Short Stories, Young Adult, Self-Help, Historical Fiction,
History, Biography, Horror, Mystery, Thriller, Romance, Fantasy, Science,
Poetry, Drama, Classic, Humor, Adventure, Crime, War,
Philosophy, Psychology, Business, Politics, Religion, Travel, Essays
```

The last 7 are new -- the original 20 leaned fiction-heavy and had no
good analog for a philosophy or psychology book, which is exactly what
live-testing against non-fiction titles surfaced.

### `app/llm_metadata.py`: rewritten for Gemini, not Ollama

```python
def query_gemini(title: str, author: str | None, settings) -> dict:
    """Raises on any failure to reach Gemini or parse a well-formed
    response -- network error, timeout, non-2xx, malformed JSON, no API
    key configured. The caller (book_metadata.lookup) treats that as
    LookupUnavailable: Gemini is now the only source, so its failure means
    the lookup genuinely could not happen this time, not "try the next
    source."

    A well-formed response -- even one saying "I don't recognize this
    book" ({'category': None, 'genres': []}), or one where every genre
    scored below the confidence floor -- returns normally. That is a
    genuine negative, safe to cache forever.
    """
```

- `POST https://generativelanguage.googleapis.com/v1beta/models/{model}
  :generateContent?key={api_key}`, `generationConfig.responseMimeType:
  "application/json"`. `urllib.request` only, same stdlib-only precedent
  as everything else in this module.
- Prompt: the fixed 27-tag vocabulary, the explicit map-don't-invent
  instruction with worked examples, "include Short Stories if it's a
  short story or collection, not a novel," "at least 3 genres, score
  each honestly even if that means some scores are low," and per-genre
  `{"genre": ..., "confidence": 1-10}` objects instead of a flat list.
- Response validation, independent of trusting the model: `category`
  must be exactly `"Fiction"`, `"Non-fiction"`, or `null`, else treated
  as `null`. Each genre object is kept only if **both** `genre` is in
  the fixed vocabulary **and** `confidence >= 7` -- everything else
  (off-vocabulary tags, low-confidence hedges) is silently dropped, the
  same "never trust the model to only emit what it's told to" validation
  `_parse_response` already did for the Ollama version.
- Timeout: 45s. Flash-Lite measured consistently under ~8s live, but
  this only ever runs off the request path (`BackgroundTasks`, or the
  offline backfill script), so there's no cost to a generous margin --
  same reasoning as the original Ollama timeout, just re-tuned to what
  was actually measured for this model.
- New settings (`app/config.py`): `REEDD_GEMINI_API_KEY` (required --
  see below), `REEDD_GEMINI_MODEL` (default `gemini-3.1-flash-lite`).
  Replaces `REEDD_OLLAMA_URL`/`REEDD_OLLAMA_MODEL`, which are removed.

### `app/book_metadata.py`: gutted, not just edited

Everything Open-Library/Google-Books-specific is deleted: `_query_open_
library`, `_query_google_books`, `_tags_from_open_library`, `_tags_from_
google_books`, `_map_category`, `_map_genres`, `_GENRE_KEYWORDS`,
`_FICTION_MARKERS`/`_NONFICTION_MARKERS`. `lookup()` keeps its exact
signature and its `LookupUnavailable` exception (nothing calling it needs
to change), but the body becomes a thin wrapper:

```python
def lookup(title, author=None, settings=None) -> dict | None:
    if settings is None:
        settings = get_settings()
    try:
        result = query_gemini(title, author, settings)
    except (urllib.error.URLError, TimeoutError, ValueError, OSError) as e:
        raise LookupUnavailable(f'Gemini unreachable for {title!r}: {e}') from e
    if result['category'] is None and not result['genres']:
        return None
    return {'category': result['category'], 'genres': result['genres'],
            'source': 'gemini', 'raw': result['raw']}
```

`source` becomes a plain string (`'gemini'`), not a list -- the
multi-source merge that shape existed for doesn't happen anymore.

### The API key is a real secret, unlike everything before it

Ollama needed no credential at all; Open Library/Google Books needed
none either. This is the first source that does. `REEDD_GEMINI_API_KEY`
goes in the systemd unit's environment the same way `REEDD_SMTP_APP_
PASSWORD` already does -- never logged, never committed. If unset,
`query_gemini` raises immediately (treated as unavailable, not a
permanent negative), so a server that hasn't been configured yet just
never resolves anything rather than crashing.

### Re-enriching what's already there

The 8 already-uploaded books currently hold results from the old Ollama-
merge pipeline (`source: ["open_library", "llm"]`-shaped entries, no
confidence filtering, the smaller 20-tag vocabulary). `backfill_metadata.
py --recheck` (already built, needs no changes -- it just calls `lookup()`
and was written source-agnostic) refreshes all 8 with the new pipeline in
one run.

## Known risk, worth stating plainly

This trades a dependency-free local model and two free-forever public
APIs for a single external, credentialed, cloud dependency -- one that,
across a single afternoon of testing this session, already: deprecated
two model versions out from under us (`gemini-2.0-flash`, `gemini-2.5-
flash` both fully 404 now), changed its thinking-config parameter name
between generations (`thinkingBudget` -> `thinkingLevel`), and enforces a
daily quota that varies by 75x between models (20/day vs. ~1,500/day)
with no reliable public documentation -- AI Studio's live quota page is
the only source of truth. `LookupUnavailable`'s existing "don't cache a
transient failure" handling absorbs day-to-day hiccups fine, but a
`gemini-3.1-flash-lite` deprecation is a "when," not an "if," and will
need `REEDD_GEMINI_MODEL` bumped by hand when it happens -- there is no
automatic fallback to a different source anymore, because there is no
other source.

Real daily volume should never be a practical concern regardless: the
existing cache means each unique (title, author) pair is ever queried
once, period, no matter how many devices or re-uploads reference it.

## Testing

- `test_llm_metadata.py`: fully rewritten for Gemini's request/response
  shape (mocked HTTP, same as before) -- prompt includes the full 27-tag
  vocabulary and the configured model name; category validated to
  exactly Fiction/Non-fiction/null; a genre is kept only when both in-
  vocabulary and confidence >= 7 (tests for: exactly at the boundary,
  one point below, an off-vocabulary tag at confidence 10 still dropped);
  network failure/timeout/malformed JSON/no API key all raise rather
  than returning a value.
- `test_book_metadata.py`: `MapCategoryTest`/`MapGenresTest` deleted
  outright (the functions no longer exist). `LookupTest` rewritten to
  mock `query_gemini` instead of the two structured-source functions:
  a real result returns normally, an unrecognized-book result caches as
  `None`, a `query_gemini` failure raises `LookupUnavailable`.
- `test_backfill_metadata.py`: unaffected -- it already mocks `app.
  backfill_metadata.lookup` directly, never the structured-source
  internals, so nothing here depends on which sources exist underneath.
- One real pass against the actual Gemini API on a mix of fiction and
  non-fiction titles before calling this done, same as every other step
  in this investigation -- mocks alone caught none of what real output
  did across this whole session.

## Suggested build order

1. `app/config.py`: `REEDD_GEMINI_API_KEY`/`REEDD_GEMINI_MODEL` settings,
   remove the Ollama ones.
2. Rewrite `app/llm_metadata.py` for Gemini; unit tested with the HTTP
   call mocked.
3. Gut `app/book_metadata.py` down to the thin wrapper; delete the
   Open-Library/Google-Books code and its tests.
4. Confirm live against a few real books (reuse the ones already tested
   this session, plus the non-fiction titles) before trusting it.
5. `backfill_metadata.py --recheck` once against the live server, to
   replace all 8 already-uploaded books' stale pre-Gemini results.
