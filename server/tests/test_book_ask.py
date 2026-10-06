# -*- coding: utf-8 -*-
"""POST /api/books/{id}/ask and app/book_ask.py: answers may only ever be
built from the text up to the reader's position. The model is stubbed and
records the prompt it was given -- the thing these tests inspect."""
import json
from unittest import mock

from app import book_ai, book_ask, book_guide

from .test_api import ApiTestCase

CH1 = ('Utterson the lawyer was a man of a rugged countenance. He walked with Enfield on Sunday. '
       'Enfield told the story of the door. ' + 'Filler sentence about the street. ' * 15)
CH2 = ('Utterson read the will of his friend Jekyll that night. It named a man called Hyde. '
       'He went to see Lanyon about it. LATER-SECRET Hyde and Jekyll are the same man. '
       + 'More filler about the evening. ' * 15)
CHAPTERS = [
    {'source': 'c1.xhtml', 'title': 'Story of the Door', 'text': CH1},
    {'source': 'c2.xhtml', 'title': 'Search for Mr. Hyde', 'text': CH2},
]


class BookAskTest(ApiTestCase):
    def setUp(self):
        super().setUp()
        self.job_id = self.upload().json()['job_id']
        patcher = mock.patch('app.book_guide.book_chapters', side_effect=lambda _p: [dict(c) for c in CHAPTERS])
        patcher.start()
        self.addCleanup(patcher.stop)
        book_ask._chapters.cache_clear()
        self.addCleanup(book_ask._chapters.cache_clear)
        self.prompts = []
        model = mock.patch('app.book_ask.book_ai.generate', side_effect=self.fake_model)
        model.start()
        self.addCleanup(model.stop)

    def fake_model(self, system, prompt, json_output=False):
        self.prompts.append(prompt)
        return 'An answer.'

    def ask(self, **position):
        body = {'question': 'Who is Hyde?', 'position': {'resource_href': 'OEBPS/c2.xhtml', **position}}
        return self.client.post(f'/api/books/{self.job_id}/ask', json=body)

    def write_guide(self):
        guide = {'version': 1, 'chapters': [
            {'index': 0, 'source': 'c1.xhtml', 'title': 'Story of the Door', 'status': 'done',
             'summary': 'Enfield tells Utterson about a door.',
             'characters': [{'name': 'Utterson', 'aliases': ['the lawyer'], 'description': 'A lawyer.'}]},
            {'index': 1, 'source': 'c2.xhtml', 'title': 'Search for Mr. Hyde', 'status': 'done',
             'summary': 'GUIDE-SPOILER the reveal.',
             'characters': [{'name': 'Hyde', 'aliases': [], 'description': 'GUIDE-SPOILER is Jekyll.'}]},
        ]}
        book_guide.guide_path(self.job_id).write_text(json.dumps(guide))

    def test_text_after_the_readers_position_never_reaches_the_model(self):
        response = self.ask(anchor_text='He went to see Lanyon about it.', anchor_offset=10)
        self.assertEqual({'answer': 'An answer.'}, response.json())
        prompt = self.prompts[0]
        self.assertIn('He went to see Lanyon about it.', prompt)
        self.assertNotIn('LATER-SECRET', prompt)
        self.assertNotIn('More filler about the evening', prompt)

    def test_guide_recaps_stop_before_the_current_chapter(self):
        self.write_guide()
        self.ask(anchor_text='It named a man called Hyde.', anchor_offset=5)
        prompt = self.prompts[0]
        self.assertIn('Enfield tells Utterson about a door.', prompt)
        self.assertIn('Utterson (also: the lawyer): A lawyer.', prompt)
        self.assertNotIn('GUIDE-SPOILER', prompt)

    def test_without_a_guide_the_earlier_raw_text_stands_in(self):
        self.ask(anchor_text='It named a man called Hyde.', anchor_offset=5)
        self.assertIn('Enfield told the story of the door.', self.prompts[0])
        self.assertNotIn('LATER-SECRET', self.prompts[0])

    def test_an_unmatched_anchor_falls_back_to_progression_rounded_down(self):
        self.ask(anchor_text='text the server cannot find anywhere', progression=0.1)
        prompt = self.prompts[0].split('UP TO WHERE THE READER IS:')[1]
        self.assertIn('Utterson read the will', prompt)
        self.assertNotIn('LATER-SECRET', prompt)

    def test_no_usable_position_includes_none_of_the_current_chapter(self):
        self.ask(anchor_text='text the server cannot find anywhere')
        current = self.prompts[0].split('UP TO WHERE THE READER IS:')[1]
        self.assertIn('start of this chapter', current)
        self.assertNotIn('Utterson read the will', current)

    def test_the_conversation_so_far_is_included(self):
        self.client.post(f'/api/books/{self.job_id}/ask', json={
            'question': 'And Enfield?', 'position': {'resource_href': 'c2.xhtml', 'progression': 0.0},
            'history': [{'q': 'Who is Utterson?', 'a': 'A lawyer.'}]})
        self.assertIn('Reader: Who is Utterson?\nYou: A lawyer.', self.prompts[0])
        self.assertTrue(self.prompts[0].endswith('QUESTION: And Enfield?'))

    def test_errors(self):
        self.assertEqual(400, self.client.post(f'/api/books/{self.job_id}/ask', json={
            'question': 'Who?', 'position': {'resource_href': 'nope.xhtml'}}).status_code)
        self.assertEqual(400, self.client.post(f'/api/books/{self.job_id}/ask', json={
            'question': '  ', 'position': {'resource_href': 'c2.xhtml'}}).status_code)
        with mock.patch('app.book_ask.book_ai.generate', side_effect=book_ai.BookAIError('Gemini HTTP 429')):
            self.assertEqual(502, self.ask(progression=0.5).status_code)

    def test_only_someone_who_can_see_the_book_can_ask(self):
        _, other_token = self.make_user('stranger@example.com')
        self.client.headers['Authorization'] = f'Bearer {other_token}'
        self.assertEqual(404, self.ask(progression=0.5).status_code)
        self.assertEqual([], self.prompts)
