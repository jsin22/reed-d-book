# -*- coding: utf-8 -*-
"""Which feedback submissions an admin has marked fixed, for the
diagnostics dashboard's Feedback tab.

The submissions themselves stay the plain, write-once text files
`submit_feedback` leaves in `feedback_dir`; this is one JSON file beside
them mapping a submission's filename to when it was marked fixed. Its name
deliberately doesn't match the `feedback-*.txt` glob every reader of that
directory uses. Same atomic-write pattern as `metadata_health.py`.
"""

import json
import os
import threading
from datetime import datetime, timezone
from pathlib import Path

FILENAME = 'status.json'

_lock = threading.Lock()


class FeedbackStatus:
    def __init__(self, feedback_dir: Path):
        self.path = Path(feedback_dir) / FILENAME

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

    def fixed_at(self) -> dict:
        """{filename: ISO timestamp} for every submission marked fixed."""
        return {name: entry['fixed_at'] for name, entry in self._read().items()}

    def set_fixed(self, name: str, fixed: bool) -> str | None:
        """Mark or unmark one submission; returns its new fixed_at (None once reopened)."""
        with _lock:
            state = self._read()
            if fixed:
                state[name] = {'fixed_at': datetime.now(timezone.utc).isoformat(timespec='seconds')}
            else:
                state.pop(name, None)
            self._write(state)
            return state.get(name, {}).get('fixed_at')

    def forget_missing(self, existing: set[str]) -> None:
        """Drop entries for submissions pruned off disk, so this file can't outgrow them."""
        with _lock:
            state = self._read()
            kept = {name: entry for name, entry in state.items() if name in existing}
            if kept != state:
                self._write(kept)
