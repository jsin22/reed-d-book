# -*- coding: utf-8 -*-
"""Whether the category/genre lookup (app/book_metadata.py) is currently
working, for the admin screen's warning banner.

Gemini is now the *only* source for category/genre (see
LLM_GENRE_ENRICHMENT.md) -- unlike Open Library/Google Books, there is no
second source to quietly fall back to if it breaks, and unlike everything
else this project talks to, it needs a real credential that can expire,
get revoked, or hit a quota with no advance warning. A silent failure here
just looks like "books never get tagged" with nothing pointing at why, so
every real attempt records its own outcome here for `GET /api/admin/
metadata-health` to report.

One JSON file, same shape and atomic-write pattern as every other store in
this app (`store.py`, `users.py`, `book_metadata_store.py`).
"""

import json
import os
from datetime import datetime, timezone
from pathlib import Path

FILENAME = 'metadata_health.json'


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


class MetadataHealth:
    def __init__(self, data_dir: Path):
        self.path = Path(data_dir) / FILENAME

    def _read(self) -> dict:
        try:
            with open(self.path, encoding='utf-8') as f:
                return json.load(f)
        except FileNotFoundError:
            return {}

    def _write(self, state: dict) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(f'.{FILENAME}.{os.getpid()}.tmp')
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(state, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self.path)

    def record_success(self) -> None:
        """A real lookup reached Gemini and got a well-formed response --
        even one saying "I don't recognize this book" counts; that means
        the source itself is working. Clears any prior failure."""
        state = self._read()
        state['last_success_at'] = utcnow()
        state['last_error'] = None
        state['last_error_at'] = None
        self._write(state)

    def record_failure(self, reason: str) -> None:
        state = self._read()
        state['last_error'] = reason
        state['last_error_at'] = utcnow()
        self._write(state)

    def status(self) -> dict:
        """`ok` is false only once a failure has actually been recorded --
        a server that has never attempted a lookup yet (no title ever
        sent, or no books uploaded since a fresh deploy) is not "broken,"
        it just has nothing to report."""
        state = self._read()
        return {
            'ok': state.get('last_error') is None,
            'last_error': state.get('last_error'),
            'last_error_at': state.get('last_error_at'),
            'last_success_at': state.get('last_success_at'),
        }
