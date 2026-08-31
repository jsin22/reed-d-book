# -*- coding: utf-8 -*-
"""app.llm_metadata's Gemini call and response parsing.

The HTTP call is always mocked here -- see LLM_GENRE_ENRICHMENT.md's
"How we got here" section for the real numbers and real responses this
was checked against before landing on this design.
"""

import json
import types
import unittest
import urllib.error
from unittest import mock

from app.llm_metadata import _parse_response, query_gemini

_SETTINGS = types.SimpleNamespace(gemini_api_key='test-key', gemini_model='gemini-3.1-flash-lite')


def _gemini_response(response_text):
    """A fake `urlopen(...)` context manager returning Gemini's own
    envelope shape, with `response_text` as the inner text -- the part
    that is itself expected to be a JSON string (responseMimeType=
    application/json)."""
    body = json.dumps({'candidates': [{'content': {'parts': [{'text': response_text}]}}]}).encode('utf-8')
    cm = mock.MagicMock()
    cm.__enter__.return_value.read.return_value = body
    return cm


class ParseResponseTest(unittest.TestCase):
    def test_keeps_genres_at_or_above_the_confidence_floor(self):
        result = _parse_response(json.dumps({
            'category': 'Fiction',
            'genres': [
                {'genre': 'Horror', 'confidence': 10},
                {'genre': 'Mystery', 'confidence': 7},
                {'genre': 'Drama', 'confidence': 6},
            ],
        }))
        self.assertEqual(result['category'], 'Fiction')
        self.assertEqual(result['genres'], ['Horror', 'Mystery'])

    def test_a_genre_one_point_below_the_floor_is_dropped(self):
        result = _parse_response(json.dumps({
            'category': 'Fiction',
            'genres': [{'genre': 'Drama', 'confidence': 6}],
        }))
        self.assertEqual(result['genres'], [])

    def test_an_off_vocabulary_genre_is_dropped_even_at_maximum_confidence(self):
        # Regression: real Gemini output reached for "Philosophy" and
        # "Psychology" before those were added to the vocabulary, with
        # genuinely high confidence -- confidence alone must never be
        # enough to let an unknown tag through.
        result = _parse_response(json.dumps({
            'category': 'Non-fiction',
            'genres': [{'genre': 'Psychological Thriller', 'confidence': 10}],
        }))
        self.assertEqual(result['genres'], [])

    def test_philosophy_and_psychology_are_in_vocabulary(self):
        # The whole point of expanding the list: these are real tags now,
        # not off-vocabulary leaks.
        result = _parse_response(json.dumps({
            'category': 'Non-fiction',
            'genres': [
                {'genre': 'Philosophy', 'confidence': 8},
                {'genre': 'Psychology', 'confidence': 9},
            ],
        }))
        self.assertEqual(sorted(result['genres']), ['Philosophy', 'Psychology'])

    def test_an_unrecognised_category_value_is_dropped_to_none(self):
        result = _parse_response(json.dumps({'category': 'Mystery Novel', 'genres': []}))
        self.assertIsNone(result['category'])

    def test_null_category_and_empty_genres_is_a_valid_dont_know_answer(self):
        result = _parse_response(json.dumps({'category': None, 'genres': []}))
        self.assertEqual(result, {'category': None, 'genres': []})

    def test_duplicate_genres_are_deduplicated(self):
        result = _parse_response(json.dumps({
            'category': 'Fiction',
            'genres': [{'genre': 'Horror', 'confidence': 9}, {'genre': 'Horror', 'confidence': 8}],
        }))
        self.assertEqual(result['genres'], ['Horror'])

    def test_a_non_numeric_confidence_is_dropped_rather_than_crashing(self):
        result = _parse_response(json.dumps({
            'category': 'Fiction',
            'genres': [{'genre': 'Horror', 'confidence': 'high'}],
        }))
        self.assertEqual(result['genres'], [])

    def test_missing_genres_key_defaults_to_empty(self):
        result = _parse_response(json.dumps({'category': 'Fiction'}))
        self.assertEqual(result['genres'], [])

    def test_malformed_json_returns_none(self):
        self.assertIsNone(_parse_response('not json at all'))

    def test_a_json_array_instead_of_an_object_is_rejected(self):
        self.assertIsNone(_parse_response('["Fiction"]'))


class QueryGeminiTest(unittest.TestCase):
    def test_a_clean_answer_is_returned_with_the_raw_payload_attached(self):
        text = json.dumps({'category': 'Fiction', 'genres': [{'genre': 'Horror', 'confidence': 9}]})
        with mock.patch('app.llm_metadata.urllib.request.urlopen', return_value=_gemini_response(text)):
            result = query_gemini('The Shining', 'Stephen King', _SETTINGS)

        self.assertEqual(result['category'], 'Fiction')
        self.assertEqual(result['genres'], ['Horror'])
        self.assertIn('raw', result)

    def test_the_request_names_the_configured_model_and_the_api_key(self):
        text = json.dumps({'category': None, 'genres': []})
        with mock.patch('app.llm_metadata.urllib.request.urlopen', return_value=_gemini_response(text)) as urlopen:
            query_gemini('Title', 'Author', _SETTINGS)

        request = urlopen.call_args[0][0]
        self.assertIn('models/gemini-3.1-flash-lite:generateContent', request.full_url)
        self.assertIn('key=test-key', request.full_url)
        body = json.loads(request.data.decode('utf-8'))
        self.assertIn('Title', body['contents'][0]['parts'][0]['text'])
        self.assertIn('Author', body['contents'][0]['parts'][0]['text'])
        self.assertEqual(body['generationConfig']['responseMimeType'], 'application/json')

    def test_no_api_key_raises_without_making_a_request(self):
        settings = types.SimpleNamespace(gemini_api_key='', gemini_model='gemini-3.1-flash-lite')
        with mock.patch('app.llm_metadata.urllib.request.urlopen') as urlopen:
            with self.assertRaises(ValueError):
                query_gemini('Title', 'Author', settings)
        urlopen.assert_not_called()

    def test_connection_failure_raises(self):
        with mock.patch('app.llm_metadata.urllib.request.urlopen',
                        side_effect=urllib.error.URLError('connection refused')):
            with self.assertRaises(urllib.error.URLError):
                query_gemini('Title', 'Author', _SETTINGS)

    def test_timeout_raises(self):
        with mock.patch('app.llm_metadata.urllib.request.urlopen', side_effect=TimeoutError()):
            with self.assertRaises(TimeoutError):
                query_gemini('Title', 'Author', _SETTINGS)

    def test_a_response_missing_candidates_raises_rather_than_crashing_uninformatively(self):
        cm = mock.MagicMock()
        cm.__enter__.return_value.read.return_value = json.dumps({'candidates': []}).encode('utf-8')
        with mock.patch('app.llm_metadata.urllib.request.urlopen', return_value=cm):
            with self.assertRaises(IndexError):
                query_gemini('Title', 'Author', _SETTINGS)

    def test_unparseable_response_text_raises(self):
        with mock.patch('app.llm_metadata.urllib.request.urlopen',
                        return_value=_gemini_response('not json at all')):
            with self.assertRaises(ValueError):
                query_gemini('Title', 'Author', _SETTINGS)


if __name__ == '__main__':
    unittest.main()
