# -*- coding: utf-8 -*-
"""Best-effort cover art from Open Library, for a job whose epub had none.

audiblez itself already extracts a cover straight from the epub when there
is one (`audiblez/core.py`'s `find_cover`), written to the job's own
`out/cover` with no further help needed. This only ever runs once
`convert_epub` (app/tasks.py) sees that file is absent -- an epub with no
embedded cover at all, common for a plain-text-first or self-published
source.

Free and keyless, unlike app/book_metadata.py's category/genre lookup:
this project defaults to free/local tooling over anything that needs
billing set up, and a cover image has no equivalent to that lookup's
cataloguing-completeness problem (LLM_GENRE_ENRICHMENT.md) -- it either
exists for the searched edition or it does not, no judgment call to get
wrong.

Searched by title/author only (`/search.json`), not ISBN: an arbitrary
epub's own metadata carries an ISBN inconsistently enough that keying on
one would leave this doing nothing for exactly the books most likely to
need a fetched cover in the first place.
"""

import json
import urllib.error
import urllib.parse
import urllib.request

SEARCH_URL = 'https://openlibrary.org/search.json'
COVER_URL = 'https://covers.openlibrary.org/b/id/{cover_id}-L.jpg'
TIMEOUT_SECONDS = 8
USER_AGENT = 'read-d-book (self-hosted personal library app)'


def fetch_cover(title: str | None, author: str | None = None) -> bytes | None:
    """The cover image's raw bytes (JPEG, as Open Library serves it), or
    None for anything short of a clean success -- no title to search on, no
    match, no cover on the matched edition, a timeout, a network error.
    Never raises: a job ending up without a cover is exactly as acceptable
    as one whose epub never had one, not a conversion failure.
    """
    if not title:
        return None
    try:
        cover_id = _search_cover_id(title, author)
        if cover_id is None:
            return None
        return _get(COVER_URL.format(cover_id=cover_id))
    except (urllib.error.URLError, TimeoutError, OSError, ValueError, KeyError) as e:
        print(f'cover_lookup: skipped for {title!r} by {author!r}: {e}')
        return None


def _search_cover_id(title: str, author: str | None) -> int | None:
    params = {'limit': 1, 'fields': 'cover_i', 'title': title}
    if author:
        params['author'] = author
    body = _get(f'{SEARCH_URL}?{urllib.parse.urlencode(params)}')
    if body is None:
        return None
    docs = json.loads(body).get('docs') or []
    if not docs:
        return None
    cover_id = docs[0].get('cover_i')
    return cover_id if isinstance(cover_id, int) and cover_id > 0 else None


def _get(url: str) -> bytes | None:
    request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        if response.status != 200:
            return None
        return response.read()
