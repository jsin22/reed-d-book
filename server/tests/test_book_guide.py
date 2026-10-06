# -*- coding: utf-8 -*-
"""The book guide (app/book_guide.py): built chapter by chapter, in order,
spoiler-safe by construction, resumable. The model and the epub extraction
are both stubbed -- these test the guide's own logic, not Gemini."""
import json
from unittest import mock

from app import book_ai, book_guide

from .test_api import ApiTestCase

CHAPTERS = [
    {'source': 'title.xhtml', 'title': 'Title page', 'text': 'A Book'},
    {'source': 'c1.xhtml', 'title': 'Chapter One', 'text': 'ALPHA-TEXT ' + 'x' * 500},
    {'source': 'c2.xhtml', 'title': 'Chapter Two', 'text': 'BRAVO-TEXT ' + 'y' * 500},
    {'source': 'c3.xhtml', 'title': 'Chapter Three', 'text': 'CHARLIE-TEXT ' + 'z' * 500},
]


def reply(summary, *characters):
    return json.dumps({'summary': summary, 'characters': [
        {'name': n, 'aliases': a, 'description': d} for n, a, d in characters]})


class BookGuideTest(ApiTestCase):
    def setUp(self):
        super().setUp()
        self.job_id = self.upload().json()['job_id']
        patcher = mock.patch('app.book_guide.book_chapters', return_value=CHAPTERS)
        patcher.start()
        self.addCleanup(patcher.stop)
        sleep = mock.patch('app.book_guide.time.sleep')
        sleep.start()
        self.addCleanup(sleep.stop)
        self.prompts = []

    def model(self, replies):
        def generate(system, prompt, json_output=False):
            self.prompts.append(prompt)
            result = replies.pop(0)
            if isinstance(result, Exception):
                raise result
            return result
        return generate

    def test_each_chapter_sees_only_itself_and_earlier_characters(self):
        guide = book_guide.build(self.job_id, generate=self.model([
            reply('Utterson walks.', ('Utterson', ['the lawyer'], 'A lawyer.')),
            reply('Hyde appears.', ('Hyde', [], 'A cruel man.'), ('Utterson', [], 'A worried lawyer.')),
            reply('A letter.', ),
        ]))
        # The title page is too short to be worth a model call.
        self.assertEqual(3, len(self.prompts))
        first, second, third = self.prompts
        self.assertIn('ALPHA-TEXT', first)
        for later in ('BRAVO-TEXT', 'CHARLIE-TEXT'):
            self.assertNotIn(later, first)
        self.assertNotIn('CHARLIE-TEXT', second)
        self.assertIn('(none yet)', first)
        self.assertIn('Utterson (also: the lawyer): A lawyer.', second)
        self.assertNotIn('Hyde', first + second.split('CHAPTER')[0])
        self.assertIn('Hyde', third.split('CHAPTER')[0])

        self.assertEqual(['done'] * 4, [c['status'] for c in guide['chapters']])
        self.assertEqual('', guide['chapters'][0]['summary'])
        manifest = self.store.read(self.job_id)
        self.assertEqual({'status': 'done', 'chapters_done': 4, 'total': 4, 'error': None}, manifest['guide'])

    def test_known_characters_merge_latest_description_and_all_aliases(self):
        chapters = [
            {'characters': [{'name': 'Utterson', 'aliases': ['the lawyer'], 'description': 'A lawyer.'}]},
            {'characters': [{'name': 'utterson', 'aliases': ['Gabriel'], 'description': 'A worried lawyer.'}]},
        ]
        merged = book_guide.known_characters(chapters, 1)
        self.assertEqual(1, len(merged))
        self.assertEqual('A worried lawyer.', merged[0]['description'])
        self.assertEqual(['the lawyer', 'Gabriel'], merged[0]['aliases'])
        self.assertEqual('A lawyer.', book_guide.known_characters(chapters, 0)[0]['description'])

    def test_a_character_renamed_later_merges_through_an_alias(self):
        # Live, Jekyll and Hyde: the last chapter's "Henry Jekyll" is the
        # earlier "Dr. Jekyll", carrying the old name as an alias.
        chapters = [
            {'characters': [{'name': 'Dr. Jekyll', 'aliases': ['Henry Jekyll'], 'description': 'A doctor.'}]},
            {'characters': [{'name': 'Henry Jekyll', 'aliases': ['Dr. Jekyll', 'H. J.'], 'description': 'Confessed.'}]},
        ]
        merged = book_guide.known_characters(chapters, 1)
        self.assertEqual(1, len(merged))
        self.assertEqual('Dr. Jekyll', merged[0]['name'])
        self.assertEqual('Confessed.', merged[0]['description'])
        self.assertEqual(['Henry Jekyll', 'H. J.'], merged[0]['aliases'])

    def test_an_interrupted_build_resumes_without_redoing_finished_chapters(self):
        with self.assertRaises(book_ai.BookAIError):
            book_guide.build(self.job_id, generate=self.model([
                reply('One.'), book_ai.BookAIError('Gemini HTTP 429')]))
        manifest = self.store.read(self.job_id)
        self.assertEqual('error', manifest['guide']['status'])
        self.assertEqual(2, manifest['guide']['chapters_done'])  # title page + chapter one
        self.assertIn('429', manifest['guide']['error'])

        self.prompts.clear()
        guide = book_guide.build(self.job_id, generate=self.model([reply('Two.'), reply('Three.')]))
        self.assertEqual(2, len(self.prompts))
        self.assertIn('BRAVO-TEXT', self.prompts[0])
        self.assertEqual(['', 'One.', 'Two.', 'Three.'], [c['summary'] for c in guide['chapters']])

    def test_two_unusable_replies_in_a_row_stop_the_build_with_an_error(self):
        with self.assertRaises(book_ai.BookAIError):
            book_guide.build(self.job_id, generate=self.model(['Sure! Here is a summary...', 'Still not JSON']))
        self.assertEqual('error', self.store.read(self.job_id)['guide']['status'])

    def test_one_unusable_reply_is_retried(self):
        guide = book_guide.build(self.job_id, generate=self.model(
            ['oops', reply('One.'), reply('Two.'), reply('Three.')]))
        self.assertEqual(['', 'One.', 'Two.', 'Three.'], [c['summary'] for c in guide['chapters']])

    def test_trailing_junk_after_a_valid_object_is_ignored(self):
        # Live, Supermarket chapter 7: a complete object, then stray brackets.
        parsed = book_guide._parse_chapter_reply(reply('Flynn rests.') + '\n}\n]\n}')
        self.assertEqual('Flynn rests.', parsed['summary'])
        fenced = book_guide._parse_chapter_reply('```json\n' + reply('Fenced.') + '\n```')
        self.assertEqual('Fenced.', fenced['summary'])

    def test_bad_fields_in_a_reply_are_dropped_not_trusted(self):
        parsed = book_guide._parse_chapter_reply(json.dumps({
            'summary': 42,
            'characters': [{'name': ''}, {'name': 'Poole', 'aliases': ['butler', 7], 'description': None}, 'junk'],
        }))
        self.assertEqual('', parsed['summary'])
        self.assertEqual([{'name': 'Poole', 'aliases': ['butler'], 'description': ''}], parsed['characters'])

    def test_the_guide_endpoint_serves_it_to_whoever_can_see_the_book(self):
        self.assertEqual(404, self.client.get(f'/api/jobs/{self.job_id}/guide').status_code)
        book_guide.build(self.job_id, generate=self.model([reply('One.'), reply('Two.'), reply('Three.')]))

        response = self.client.get(f'/api/jobs/{self.job_id}/guide')
        self.assertEqual(200, response.status_code)
        self.assertEqual(4, len(response.json()['chapters']))

        _, other_token = self.make_user('stranger@example.com')
        self.client.headers['Authorization'] = f'Bearer {other_token}'
        self.assertEqual(404, self.client.get(f'/api/jobs/{self.job_id}/guide').status_code)

    def test_an_upload_starts_a_guide_build(self):
        self.start_guide.assert_called_with(self.job_id)
