# -*- coding: utf-8 -*-
"""GET /api/dictionary/{word}: the full-dictionary fallback for words the
app's own frequency-cut copy does not have (E-8). Uses a tiny dictionary with
the real build script's schema, not the real multi-hundred-MB build."""
import sqlite3

from .test_api import ApiTestCase

SCHEMA = """
    CREATE TABLE parts_of_speech (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
    CREATE TABLE senses (id INTEGER PRIMARY KEY, word TEXT NOT NULL, pos INTEGER NOT NULL,
                         rank INTEGER NOT NULL, gloss TEXT NOT NULL, ipa TEXT);
    CREATE TABLE synonyms (sense_id INTEGER NOT NULL, synonym TEXT NOT NULL);
    CREATE TABLE forms (form TEXT PRIMARY KEY, base TEXT NOT NULL);
"""


class DictionaryTest(ApiTestCase):
    def setUp(self):
        super().setUp()
        path = self.settings.dictionary_path
        path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(path)
        db.executescript(SCHEMA)
        db.executemany('INSERT INTO parts_of_speech VALUES (?, ?)',
                       [(1, 'noun'), (2, 'adjective'), (3, 'proper noun'), (4, 'verb')])
        db.executemany('INSERT INTO senses VALUES (?, ?, ?, ?, ?, ?)', [
            (1, 'catholicity', 1, 0, 'The quality of being universal or all-embracing.', '/ˌkæθəˈlɪsɪti/'),
            (2, 'quotidian', 2, 0, 'Occurring every day.', None),
            (3, 'go', 3, 0, 'Abbreviation of Gorontalo: a province of Indonesia.', None),
            (5, 'go', 1, 0, 'Initialism of graphene oxide.', None),
            (6, 'go', 4, 0, 'To move through space.', None),
            (7, 'go', 1, 1, 'A turn at something.', None),
            (4, 'lugubrious', 2, 0, 'Gloomy, mournful.', None),
        ])
        db.execute('INSERT INTO synonyms VALUES (?, ?)', (1, 'universality'))
        db.execute('INSERT INTO forms VALUES (?, ?)', ('went', 'go'))
        db.commit()
        db.close()

    def test_a_rare_word_is_defined(self):
        response = self.client.get('/api/dictionary/catholicity')
        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual('catholicity', body['word'])
        sense = body['senses'][0]
        self.assertEqual('noun', sense['part_of_speech'])
        self.assertIn('universal', sense['definition'])
        self.assertEqual(['universality'], sense['synonyms'])
        self.assertEqual('/ˌkæθəˈlɪsɪti/', sense['pronunciation'])

    def test_the_apps_candidates_are_tried_in_order(self):
        # "lugubriously" is not a headword; the app's own suffix rules offer
        # "lugubrious" and the server just tries what it is given.
        response = self.client.get('/api/dictionary/lugubriously', params={'candidates': ['lugubrious']})
        self.assertEqual(200, response.status_code)
        self.assertEqual('lugubrious', response.json()['word'])
        self.assertEqual('lugubriously', response.json()['queried'])

    def test_the_servers_own_irregular_forms_are_used(self):
        response = self.client.get('/api/dictionary/went')
        self.assertEqual(200, response.status_code)
        self.assertEqual('go', response.json()['word'])

    def test_ordinary_meanings_come_before_names_and_abbreviations(self):
        senses = self.client.get('/api/dictionary/go').json()['senses']
        self.assertEqual(['To move through space.', 'A turn at something.'],
                         [s['definition'] for s in senses[:2]])
        self.assertIn('Gorontalo', senses[-1]['definition'] + senses[-2]['definition'])

    def test_case_is_folded(self):
        self.assertEqual(200, self.client.get('/api/dictionary/Quotidian').status_code)

    def test_an_unknown_word_is_404(self):
        response = self.client.get('/api/dictionary/zzxyq', params={'candidates': ['zzxy']})
        self.assertEqual(404, response.status_code)

    def test_requires_a_signed_in_user(self):
        del self.client.headers['Authorization']
        self.assertEqual(401, self.client.get('/api/dictionary/catholicity').status_code)

    def test_a_server_without_the_dictionary_says_so(self):
        self.settings.dictionary_path.unlink()
        # A fresh thread-local connection is opened per path, so point at the
        # now-missing file from a clean state.
        from app import dictionary
        dictionary._local.__dict__.clear()
        self.assertEqual(503, self.client.get('/api/dictionary/catholicity').status_code)
