# -*- coding: utf-8 -*-
"""book_metadata.lookup()'s thin wrapper over the Gemini call, and the
LookupUnavailable/None distinction that governs what BookMetadataStore is
allowed to cache.

query_gemini itself is always mocked here -- see test_llm_metadata.py for
its own unit tests, and LLM_GENRE_ENRICHMENT.md for why Open Library and
Google Books no longer exist in this module at all.
"""

from unittest import mock

from app.book_metadata import LookupUnavailable, lookup

from .support import TempDataDirTestCase


class LookupTest(TempDataDirTestCase):
    def test_a_real_result_is_returned_with_gemini_as_the_source(self):
        with mock.patch('app.book_metadata.query_gemini',
                        return_value={'category': 'Fiction', 'genres': ['Horror'], 'raw': {}}):
            result = lookup('The Shining', 'Stephen King')

        self.assertEqual(result['category'], 'Fiction')
        self.assertEqual(result['genres'], ['Horror'])
        self.assertEqual(result['source'], 'gemini')

    def test_an_unrecognised_book_is_a_genuine_none_not_an_error(self):
        with mock.patch('app.book_metadata.query_gemini',
                        return_value={'category': None, 'genres': [], 'raw': {}}):
            result = lookup('Totally Unfindable Book', None)

        self.assertIsNone(result)

    def test_every_genre_below_the_confidence_floor_is_also_a_genuine_none(self):
        # query_gemini itself already drops these (see test_llm_metadata.py);
        # this just confirms lookup() doesn't second-guess an empty list.
        with mock.patch('app.book_metadata.query_gemini',
                        return_value={'category': None, 'genres': [], 'raw': {}}):
            result = lookup('Title', 'Author')

        self.assertIsNone(result)

    def test_a_query_gemini_failure_raises_lookup_unavailable(self):
        # Regression: this must not be cached the same way a genuine
        # "nothing found" is, or a transient outage permanently poisons
        # that book's entry with no way to ever retry it.
        with mock.patch('app.book_metadata.query_gemini', side_effect=ValueError('no API key configured')):
            with self.assertRaises(LookupUnavailable):
                lookup('Title', 'Author')

    def test_lookup_unavailable_message_names_the_book(self):
        with mock.patch('app.book_metadata.query_gemini', side_effect=ValueError('boom')):
            with self.assertRaises(LookupUnavailable) as ctx:
                lookup('A Specific Title', 'A Specific Author')

        self.assertIn('A Specific Title', str(ctx.exception))


if __name__ == '__main__':
    import unittest
    unittest.main()
