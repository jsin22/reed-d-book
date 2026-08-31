# -*- coding: utf-8 -*-
"""Cached category/genre lookups, keyed by a normalized (title, author) pair.

One JSON file, same shape as UserStore -- the whole point is that two
different users uploading the same book only ever triggers one external
API call (app/book_metadata.py), ever. A "not found" result is cached too,
so an unfindable book isn't re-queried on every future upload.
"""

import json
import os
import re
import threading
from datetime import datetime, timezone
from pathlib import Path

FILENAME = 'book_metadata.json'

# Guards the read-modify-write in put() -- same reasoning as users.py's
# _lock: unlikely to matter with one uvicorn process, but cheap insurance
# against two near-simultaneous lookups clobbering each other's write.
_lock = threading.Lock()

_WHITESPACE = re.compile(r'\s+')


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def normalize_key(title: str, author: str | None) -> str:
    def clean(value):
        return _WHITESPACE.sub(' ', (value or '').strip().lower())
    return f'{clean(title)}|{clean(author)}'


class BookMetadataStore:
    def __init__(self, data_dir: Path):
        self.path = Path(data_dir) / FILENAME

    def _read_all(self) -> dict:
        try:
            with open(self.path, encoding='utf-8') as f:
                return json.load(f)
        except FileNotFoundError:
            return {}

    def _write_all(self, entries: dict) -> None:
        """Same atomic-write shape as JobStore.write/UserStore._write_all:
        temp file + os.replace, so a concurrent reader never sees a
        half-written file."""
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(f'.{FILENAME}.{os.getpid()}.tmp')
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(entries, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self.path)

    def get(self, title: str, author: str | None) -> dict | None:
        """None means "never looked up" -- the caller should call
        book_metadata.lookup() and put() the result. A dict with
        category=None and genres=[] means "looked up, found nothing" --
        already resolved, do not look up again.
        """
        return self._read_all().get(normalize_key(title, author))

    def put(self, title: str, author: str | None, result: dict | None) -> None:
        """`result` is book_metadata.lookup()'s return value -- including
        None, which is cached as the "found nothing" entry rather than left
        unwritten, so a genuinely unfindable book is asked about once."""
        with _lock:
            entries = self._read_all()
            entries[normalize_key(title, author)] = {
                'category': (result or {}).get('category'),
                'genres': (result or {}).get('genres', []),
                'source': (result or {}).get('source'),
                'raw': (result or {}).get('raw'),
                'looked_up_at': utcnow(),
            }
            self._write_all(entries)
