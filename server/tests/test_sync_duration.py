# -*- coding: utf-8 -*-
"""A finished job's duration_s, read from its sync file so a device knows a
book's length before downloading it."""
import json
import tempfile
import unittest
from pathlib import Path

from app.tasks import sync_duration


class SyncDurationTest(unittest.TestCase):
    def test_reads_the_duration_from_the_sync_file(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'book.json'
            path.write_text(json.dumps({'version': 1, 'duration': 7652.88, 'chunks': []}))
            self.assertEqual(7652.88, sync_duration(path))

    def test_a_missing_or_malformed_file_is_none_not_an_error(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertIsNone(sync_duration(Path(d) / 'missing.json'))
            bad = Path(d) / 'bad.json'
            bad.write_text('{"no_duration": true}')
            self.assertIsNone(sync_duration(bad))


if __name__ == '__main__':
    unittest.main()
