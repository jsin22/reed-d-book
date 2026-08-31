# -*- coding: utf-8 -*-
"""The category/genre cache: normalized keys, atomic writes, and caching
a "not found" result so an unfindable book isn't re-queried forever."""

import json

from app.book_metadata_store import BookMetadataStore, normalize_key

from .support import TempDataDirTestCase


class NormalizeKeyTest(TempDataDirTestCase):
    def test_case_and_whitespace_insensitive(self):
        self.assertEqual(
            normalize_key('The Shining', 'Stephen King'),
            normalize_key('  the   shining ', 'STEPHEN KING'),
        )

    def test_none_and_empty_author_normalize_the_same(self):
        # Both mean "no author known" -- treating them differently would let
        # the same book get two separate cache entries depending on which
        # falsy value happened to be passed in.
        self.assertEqual(normalize_key('Title', None), normalize_key('Title', ''))


class BookMetadataStoreTest(TempDataDirTestCase):
    def setUp(self):
        super().setUp()
        self.store = BookMetadataStore(self.settings.data_dir)

    def test_get_is_none_before_anything_is_stored(self):
        self.assertIsNone(self.store.get('Title', 'Author'))

    def test_put_then_get_round_trips(self):
        self.store.put('The Shining', 'Stephen King',
                       {'category': 'Fiction', 'genres': ['Horror'], 'source': 'gemini', 'raw': {}})
        entry = self.store.get('The Shining', 'Stephen King')
        self.assertEqual(entry['category'], 'Fiction')
        self.assertEqual(entry['genres'], ['Horror'])
        self.assertEqual(entry['source'], 'gemini')

    def test_lookup_is_keyed_regardless_of_case_or_whitespace(self):
        self.store.put('The Shining', 'Stephen King',
                       {'category': 'Fiction', 'genres': [], 'source': 'gemini', 'raw': {}})
        self.assertIsNotNone(self.store.get('  THE shining', 'stephen   king '))

    def test_a_not_found_result_is_cached_as_a_real_entry_not_left_unwritten(self):
        self.store.put('Totally Unfindable Book', None, None)
        entry = self.store.get('Totally Unfindable Book', None)
        self.assertIsNotNone(entry)
        self.assertIsNone(entry['category'])
        self.assertEqual(entry['genres'], [])
        self.assertIsNone(entry['source'])

    def test_write_leaves_no_partial_file_behind(self):
        self.store.put('Title', 'Author', None)
        leftovers = list(self.settings.data_dir.glob('.book_metadata.json*'))
        self.assertEqual(leftovers, [])
        json.loads((self.settings.data_dir / 'book_metadata.json').read_text())

    def test_a_second_lookup_for_the_same_book_overwrites_not_duplicates(self):
        self.store.put('Title', 'Author', {'category': 'Fiction', 'genres': [], 'source': 'gemini', 'raw': {}})
        self.store.put('Title', 'Author', {'category': 'Fiction', 'genres': ['Horror'], 'source': 'gemini', 'raw': {}})
        raw = json.loads((self.settings.data_dir / 'book_metadata.json').read_text())
        self.assertEqual(len(raw), 1)
        self.assertEqual(self.store.get('Title', 'Author')['genres'], ['Horror'])


if __name__ == '__main__':
    import unittest
    unittest.main()
