# -*- coding: utf-8 -*-
"""Best-effort category/genre lookup by title+author.

Gemini (app/llm_metadata.py) is the sole source -- see
LLM_GENRE_ENRICHMENT.md for why Open Library and Google Books were
removed rather than kept as a fallback: their real problem wasn't
reachability, it was cataloguing completeness, which only a model with
real knowledge of the book (not just whatever subject tags a cataloguer
happened to add) reliably closed.
"""

import urllib.error

from .config import get_settings
from .llm_metadata import query_gemini
from .metadata_health import MetadataHealth


class LookupUnavailable(Exception):
    """Gemini could not be reached, or its response could not be parsed at
    all -- network error, timeout, non-2xx status, no API key configured,
    or a malformed envelope. Deliberately distinct from a query that
    reached Gemini and got a clean "I don't recognize this book" answer:
    callers (book_metadata_store.py via main.py) must not cache this the
    same way, or a transient failure permanently poisons that book's
    entry with no way to ever retry it.
    """


def lookup(title: str, author: str | None = None, settings=None) -> dict | None:
    """Best-effort category/genre lookup.

    Returns None if Gemini was reachable and had nothing usable -- either
    it does not recognize the book, or every genre it suggested scored
    below the confidence floor. Callers should treat that as a genuine
    Category=Unknown, Genres=[], safe to cache forever (see
    book_metadata_store.py).

    Raises LookupUnavailable if Gemini could not be reached or its
    response could not be parsed. Callers must not cache this as a
    permanent negative result; the right response is to simply try again
    on some future upload of the same book.
    """
    if settings is None:
        settings = get_settings()
    health = MetadataHealth(settings.data_dir)
    try:
        result = query_gemini(title, author, settings)
    except (urllib.error.URLError, TimeoutError, OSError, ValueError, KeyError, IndexError) as e:
        health.record_failure(str(e))
        raise LookupUnavailable(f'Gemini unreachable for {title!r} by {author!r}: {e}') from e

    health.record_success()
    if result['category'] is None and not result['genres']:
        return None
    return {'category': result['category'], 'genres': result['genres'], 'source': 'gemini', 'raw': result['raw']}
