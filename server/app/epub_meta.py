# -*- coding: utf-8 -*-
"""Read title/author directly out of an epub's own OPF metadata.

`ebooklib` only, not `audiblez.core` -- the web process (and
`backfill_metadata.py`, which imports this) must not import audiblez' heavy
per-engine modules (torch/pocket_tts); see
`audiblez_meta.py`'s own docstring for the same rule. `ebooklib` itself is
just a zip/XML reader, safe to import here.

Only needed for jobs uploaded before the app started sending title/author on
`POST /api/jobs` (see `SORT_GROUP_LIBRARY.md`, "Known limitation: existing
books") -- every job since then already has both on its manifest.
"""

from ebooklib import epub


def read_title_author(epub_path) -> tuple[str | None, str | None]:
    """Best-effort; returns (None, None) if the epub has no DC metadata at
    all rather than raising -- a book missing one of these is not a reason
    to abort a backfill run over the rest of the library."""
    try:
        book = epub.read_epub(str(epub_path))
    except Exception:
        return None, None
    titles = book.get_metadata('DC', 'title')
    creators = book.get_metadata('DC', 'creator')
    title = titles[0][0].strip() if titles and titles[0][0] else None
    author = creators[0][0].strip() if creators and creators[0][0] else None
    return title, author
