# -*- coding: utf-8 -*-
import tempfile
import unittest
from pathlib import Path

from app.epub_meta import read_title_author

from .support import epub_with_metadata


class ReadTitleAuthorTest(unittest.TestCase):
    def _write(self, tmp, title=None, author=None):
        path = Path(tmp) / 'book.epub'
        path.write_bytes(epub_with_metadata(title=title, author=author))
        return path

    def test_reads_title_and_author_from_real_opf_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = self._write(tmp, title='Hidden Pictures', author='Jason Rekulak')
            title, author = read_title_author(path)
        self.assertEqual(title, 'Hidden Pictures')
        self.assertEqual(author, 'Jason Rekulak')

    def test_missing_metadata_is_none_not_an_empty_string(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = self._write(tmp)  # no title/author set at all
            title, author = read_title_author(path)
        self.assertIsNone(title)
        self.assertIsNone(author)

    def test_a_file_that_is_not_a_real_epub_returns_none_rather_than_raising(self):
        # A backfill run over a whole library must not abort on one bad file.
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'not-a-book.epub'
            path.write_bytes(b'not a zip at all')
            title, author = read_title_author(path)
        self.assertIsNone(title)
        self.assertIsNone(author)


if __name__ == '__main__':
    unittest.main()
