# -*- coding: utf-8 -*-
"""The HTTP contract the Android app codes against.

The queue is stubbed out: these tests cover the API's own behaviour, not
Celery's, and they must run without Redis or the TTS stack installed.
"""

import unittest
from unittest import mock

from fastapi.testclient import TestClient

from app.book_metadata import LookupUnavailable
from app.main import app
from app.store import JobStore
from app.users import UserStore

from .support import TempDataDirTestCase, epub_bytes

FAKE_TASK_ID = 'celery-task-1'


class ApiTestCase(TempDataDirTestCase):
    def setUp(self):
        super().setUp()
        self.client = TestClient(app)
        self.store = JobStore(self.settings.jobs_dir)
        self.users = UserStore(self.settings.data_dir)
        # Every route but /api/health, /api/voices, /api/engines and
        # /download/app needs a per-user token now -- see app.main.require_user
        # -- so give every test an authenticated caller by default. Tests that
        # care about auth itself (AuthTest) or about a second identity
        # (OwnershipTest, AdminTest, InviteTest) adjust self.client.headers.
        self.user, self.token = self.users.create('test@example.com')
        self.client.headers['Authorization'] = f'Bearer {self.token}'
        patcher = mock.patch('app.main.enqueue', return_value=FAKE_TASK_ID)
        self.enqueue = patcher.start()
        self.addCleanup(patcher.stop)

    def make_user(self, email='other@example.com', is_admin=False):
        return self.users.create(email, is_admin=is_admin)

    def upload(self, name='Book One.epub', content=None, **data):
        files = {'file': (name, content if content is not None else epub_bytes(),
                          'application/epub+zip')}
        return self.client.post('/api/jobs', files=files, data=data)

    def finish(self, job_id, m4b=b'fake m4b bytes', sync=b'{"chunks": []}'):
        """Fake a completed conversion, so download behaviour can be tested."""
        out = self.store.output_dir(job_id)
        (out / 'Book_One.m4b').write_bytes(m4b)
        (out / 'Book_One.json').write_bytes(sync)
        return self.store.update(
            job_id, status='done', progress=100,
            audiobook={'file': 'Book_One.m4b', 'bytes': len(m4b)},
            sync={'file': 'Book_One.json', 'bytes': len(sync)})


class UploadTest(ApiTestCase):
    def test_returns_a_job_id_immediately_and_queues_the_work(self):
        response = self.upload()
        self.assertEqual(response.status_code, 202)
        body = response.json()
        self.assertEqual(body['status'], 'queued')
        self.assertEqual(body['filename'], 'Book_One.epub')
        self.assertEqual(body['celery_task_id'], FAKE_TASK_ID)
        self.enqueue.assert_called_once_with(body['job_id'])

    def test_stores_the_upload_verbatim(self):
        payload = epub_bytes(body=b'<html><body><p>Distinct.</p></body></html>')
        job_id = self.upload(content=payload).json()['job_id']
        self.assertEqual((self.store.job_dir(job_id) / 'Book_One.epub').read_bytes(), payload)

    def test_defaults_come_from_settings(self):
        body = self.upload().json()
        self.assertEqual(body['engine'], self.settings.default_engine)
        self.assertEqual(body['voice'], 'alba')
        self.assertEqual(body['speed'], self.settings.default_speed)

    def test_accepts_explicit_voice_and_speed(self):
        body = self.upload(voice='giovanni', speed=1.25).json()
        self.assertEqual((body['voice'], body['speed']), ('giovanni', 1.25))

    def test_rejects_unknown_voice(self):
        response = self.upload(voice='not_a_voice')
        self.assertEqual(response.status_code, 400)
        self.assertIn('voice', response.json()['detail'])

    def test_rejects_out_of_range_speed(self):
        for speed in (0.1, 3.0):
            self.assertEqual(self.upload(speed=speed).status_code, 400)

    def test_rejects_wrong_extension(self):
        self.assertEqual(self.upload(name='book.pdf').status_code, 400)

    def test_rejects_a_file_that_is_not_a_zip(self):
        response = self.upload(content=b'plain text pretending to be an epub')
        self.assertEqual(response.status_code, 400)
        self.assertEqual(self.store.list(), [])  # and leaves nothing behind

    def test_reports_a_dead_queue_instead_of_stranding_the_job(self):
        self.enqueue.side_effect = OSError('Connection refused')
        response = self.upload()
        self.assertEqual(response.status_code, 503)
        # A job nothing will ever pick up is worse than no job at all.
        self.assertEqual(self.store.list(), [])


class BookMetadataOnUploadTest(ApiTestCase):
    """The category/genre lookup BackgroundTasks kicks off from create_job --
    see app.main._resolve_book_metadata. app.main.lookup_book_metadata is
    always mocked here; it does real network calls otherwise.
    """

    def test_title_and_author_are_stored_and_trigger_a_lookup(self):
        with mock.patch('app.main.lookup_book_metadata',
                        return_value={'category': 'Fiction', 'genres': ['Horror'],
                                      'source': 'open_library', 'raw': {}}) as lookup:
            job_id = self.upload(title='The Shining', author='Stephen King').json()['job_id']

        lookup.assert_called_once_with('The Shining', 'Stephen King')
        body = self.client.get(f'/api/jobs/{job_id}').json()
        self.assertEqual(body['title'], 'The Shining')
        self.assertEqual(body['author'], 'Stephen King')
        self.assertEqual(body['category'], 'Fiction')
        self.assertEqual(body['genres'], ['Horror'])

    def test_no_title_skips_the_lookup_entirely(self):
        with mock.patch('app.main.lookup_book_metadata') as lookup:
            job_id = self.upload().json()['job_id']

        lookup.assert_not_called()
        body = self.client.get(f'/api/jobs/{job_id}').json()
        self.assertIsNone(body['category'])
        self.assertEqual(body['genres'], [])

    def test_a_lookup_that_finds_nothing_leaves_the_job_usable(self):
        with mock.patch('app.main.lookup_book_metadata', return_value=None):
            response = self.upload(title='Totally Unfindable Book')

        self.assertEqual(response.status_code, 202)
        body = self.client.get(f"/api/jobs/{response.json()['job_id']}").json()
        self.assertIsNone(body['category'])
        self.assertEqual(body['genres'], [])

    def test_a_second_upload_of_the_same_book_does_not_look_it_up_again(self):
        with mock.patch('app.main.lookup_book_metadata',
                        return_value={'category': 'Fiction', 'genres': [], 'source': 'open_library', 'raw': {}}) as lookup:
            self.upload(title='The Shining', author='Stephen King')
            self.upload(name='Book Two.epub', title='The Shining', author='Stephen King')

        lookup.assert_called_once()

    def test_a_transient_failure_is_not_cached_and_gets_retried_on_the_next_upload(self):
        # Regression: an empirically-observed 429 from Google Books' keyless
        # access must not permanently mark a book as "no genre found" --
        # the next upload of the same title should try the lookup again.
        with mock.patch('app.main.lookup_book_metadata', side_effect=LookupUnavailable('boom')) as lookup:
            job_id = self.upload(title='The Shining', author='Stephen King').json()['job_id']

        body = self.client.get(f'/api/jobs/{job_id}').json()
        self.assertIsNone(body['category'])
        self.assertEqual(body['genres'], [])

        with mock.patch('app.main.lookup_book_metadata',
                        return_value={'category': 'Fiction', 'genres': ['Horror'], 'source': 'open_library', 'raw': {}}) as lookup:
            self.upload(name='Book Two.epub', title='The Shining', author='Stephen King')

        lookup.assert_called_once()  # not skipped as "already resolved"


class UploadLimitTest(ApiTestCase):
    env = {'REEDD_MAX_UPLOAD_BYTES': '100'}

    def test_rejects_and_cleans_up_an_oversized_upload(self):
        response = self.upload(content=epub_bytes(body=b'x' * 5000))
        self.assertEqual(response.status_code, 413)
        self.assertEqual(self.store.list(), [])


class EngineTest(ApiTestCase):
    """Pocket TTS is the only engine the server offers or accepts -- Kokoro
    and Supertonic were both removed (see git history on
    audiblez/engines.py and audiblez_meta.py's own module doc); nothing in
    this project's real usage ever selected either. `engine` stays a real,
    validated field regardless -- these tests are what would need a second
    case if a future engine ever gets added back.
    """

    def test_upload_gets_pocket_ttss_own_default_voice(self):
        body = self.upload(engine='pocket_tts').json()
        self.assertEqual(body['engine'], 'pocket_tts')
        self.assertEqual(body['voice'], 'alba')

    def test_accepts_an_explicit_pocket_tts_voice(self):
        body = self.upload(engine='pocket_tts', voice='giovanni').json()
        self.assertEqual(body['voice'], 'giovanni')

    def test_rejects_an_unknown_voice_for_pocket_tts(self):
        response = self.upload(engine='pocket_tts', voice='not_a_real_voice')
        self.assertEqual(response.status_code, 400)
        self.assertIn('voice', response.json()['detail'])

    def test_rejects_an_unknown_engine(self):
        response = self.upload(engine='not_an_engine')
        self.assertEqual(response.status_code, 400)
        self.assertIn('engine', response.json()['detail'])

    def test_voices_endpoint_defaults_to_pocket_tts(self):
        # The Android app locks to pocket_tts and always sends it explicitly
        # (ImportSheet.kt); this is what a request that omits the field
        # entirely falls back to.
        body = self.client.get('/api/voices').json()
        self.assertEqual(body['engine'], 'pocket_tts')
        self.assertEqual(body['default'], 'alba')
        self.assertIn('alba', body['voices'])

    def test_voices_endpoint_accepts_an_engine(self):
        body = self.client.get('/api/voices', params={'engine': 'pocket_tts'}).json()
        self.assertEqual(body['engine'], 'pocket_tts')
        self.assertEqual(body['default'], 'alba')
        self.assertIn('alba', body['voices'])

    def test_voices_endpoint_rejects_an_unknown_engine(self):
        response = self.client.get('/api/voices', params={'engine': 'not_an_engine'})
        self.assertEqual(response.status_code, 400)

    def test_engines_endpoint_lists_the_one_engine_with_its_voices_and_default(self):
        body = self.client.get('/api/engines').json()
        self.assertEqual(body['default'], self.settings.default_engine)
        by_id = {e['id']: e for e in body['engines']}
        self.assertEqual(set(by_id), {'pocket_tts'})
        self.assertEqual(by_id['pocket_tts']['default_voice'], 'alba')
        self.assertIn('alba', by_id['pocket_tts']['voices'])


class VoiceSampleTest(ApiTestCase):
    """`_synthesize_sample` is stubbed throughout: it does real TTS work
    (torch/pocket_tts), which these tests must run without, same reasoning
    as ConversionTest in test_tasks.py stubbing `_load_audiblez`.
    """

    def _fake_synth(self, engine, voice, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'RIFF....WAVEfake')

    def test_generates_and_serves_a_sample(self):
        with mock.patch('app.main._synthesize_sample', side_effect=self._fake_synth) as synth:
            response = self.client.get('/api/voices/alba/sample')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, b'RIFF....WAVEfake')
        self.assertEqual(response.headers['content-type'], 'audio/wav')
        synth.assert_called_once_with('pocket_tts', 'alba', mock.ANY)

    def test_a_cached_sample_is_served_without_resynthesizing(self):
        with mock.patch('app.main._synthesize_sample', side_effect=self._fake_synth) as synth:
            self.client.get('/api/voices/alba/sample')
            second = self.client.get('/api/voices/alba/sample')
        self.assertEqual(second.status_code, 200)
        self.assertEqual(second.content, b'RIFF....WAVEfake')
        synth.assert_called_once()

    def test_accepts_an_explicit_engine(self):
        # Only pocket_tts exists to pass here now, but the engine query
        # param itself still has to actually reach _synthesize_sample, not
        # just be silently ignored in favour of the default -- 'giovanni'
        # (not 'alba', the default) is what would catch that.
        with mock.patch('app.main._synthesize_sample', side_effect=self._fake_synth) as synth:
            self.client.get('/api/voices/giovanni/sample', params={'engine': 'pocket_tts'})
        synth.assert_called_once_with('pocket_tts', 'giovanni', mock.ANY)

    def test_rejects_an_unknown_voice(self):
        response = self.client.get('/api/voices/not_a_voice/sample')
        self.assertEqual(response.status_code, 400)

    def test_rejects_an_unknown_engine(self):
        response = self.client.get('/api/voices/alba/sample', params={'engine': 'not_an_engine'})
        self.assertEqual(response.status_code, 400)

    def test_requires_a_token(self):
        del self.client.headers['Authorization']
        response = self.client.get('/api/voices/alba/sample')
        self.assertEqual(response.status_code, 401)


class PollTest(ApiTestCase):
    def test_reports_progress_written_by_the_worker(self):
        job_id = self.upload().json()['job_id']
        self.store.update(job_id, status='running', progress=42, eta='00:03:20', chapters_done=2)
        body = self.client.get(f'/api/jobs/{job_id}').json()
        self.assertEqual(body['status'], 'running')
        self.assertEqual(body['progress'], 42)
        self.assertEqual(body['eta'], '00:03:20')
        self.assertEqual(body['chapters_done'], 2)

    def test_reports_failures(self):
        job_id = self.upload().json()['job_id']
        self.store.update(job_id, status='error', error='espeak-ng not found')
        body = self.client.get(f'/api/jobs/{job_id}').json()
        self.assertEqual(body['status'], 'error')
        self.assertIn('espeak-ng', body['error'])

    def test_unknown_id_is_404_not_a_crash(self):
        for job_id in ('9d0b6c4f-5e2a-4b3c-8a1b-000000000000', 'nonsense', '..'):
            self.assertEqual(self.client.get(f'/api/jobs/{job_id}').status_code, 404, job_id)

    def test_listing_includes_every_job(self):
        ids = {self.upload().json()['job_id'] for _ in range(3)}
        listed = {job['job_id'] for job in self.client.get('/api/jobs').json()['jobs']}
        self.assertEqual(listed, ids)


class DownloadTest(ApiTestCase):
    def test_serves_the_audiobook_and_the_sync_file(self):
        job_id = self.upload().json()['job_id']
        self.finish(job_id)

        audio = self.client.get(f'/api/jobs/{job_id}/audiobook')
        self.assertEqual(audio.status_code, 200)
        self.assertEqual(audio.content, b'fake m4b bytes')
        self.assertEqual(audio.headers['content-type'], 'audio/mp4')
        self.assertIn('Book_One.m4b', audio.headers['content-disposition'])

        sync = self.client.get(f'/api/jobs/{job_id}/sync')
        self.assertEqual(sync.json(), {'chunks': []})

    def test_audiobook_supports_range_requests(self):
        # The app downloads hundreds of megabytes over wifi; a dropped
        # connection has to be resumable rather than restarted.
        job_id = self.upload().json()['job_id']
        self.finish(job_id, m4b=b'0123456789')
        response = self.client.get(f'/api/jobs/{job_id}/audiobook',
                                   headers={'Range': 'bytes=4-6'})
        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.content, b'456')

    def test_downloading_before_the_job_finishes_is_409(self):
        job_id = self.upload().json()['job_id']
        response = self.client.get(f'/api/jobs/{job_id}/audiobook')
        self.assertEqual(response.status_code, 409)
        self.assertIn('queued', response.json()['detail'])

    def test_download_of_unknown_job_is_404(self):
        self.assertEqual(
            self.client.get('/api/jobs/9d0b6c4f-5e2a-4b3c-8a1b-000000000000/sync').status_code,
            404)

    def test_missing_output_file_is_410_not_500(self):
        job_id = self.upload().json()['job_id']
        self.finish(job_id)
        (self.store.output_dir(job_id) / 'Book_One.m4b').unlink()
        self.assertEqual(self.client.get(f'/api/jobs/{job_id}/audiobook').status_code, 410)

    def test_serves_the_epub_even_before_the_job_finishes(self):
        # Unlike the audiobook/sync, the upload exists from the moment the job
        # is created and never changes -- a device adopting an in-progress job
        # from the listing should not have to wait for it to be done.
        payload = epub_bytes(body=b'<html><body><p>Distinct.</p></body></html>')
        job_id = self.upload(content=payload).json()['job_id']
        response = self.client.get(f'/api/jobs/{job_id}/epub')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, payload)
        self.assertEqual(response.headers['content-type'], 'application/epub+zip')
        self.assertIn('Book_One.epub', response.headers['content-disposition'])

    def test_epub_supports_range_requests(self):
        job_id = self.upload(content=epub_bytes(body=b'0123456789')).json()['job_id']
        response = self.client.get(f'/api/jobs/{job_id}/epub', headers={'Range': 'bytes=4-6'})
        self.assertEqual(response.status_code, 206)

    def test_epub_of_unknown_job_is_404(self):
        self.assertEqual(
            self.client.get('/api/jobs/9d0b6c4f-5e2a-4b3c-8a1b-000000000000/epub').status_code,
            404)

    def test_missing_epub_is_410_not_500(self):
        job_id = self.upload().json()['job_id']
        (self.store.job_dir(job_id) / 'Book_One.epub').unlink()
        self.assertEqual(self.client.get(f'/api/jobs/{job_id}/epub').status_code, 410)

    def test_log_is_served_when_it_exists(self):
        job_id = self.upload().json()['job_id']
        self.assertEqual(self.client.get(f'/api/jobs/{job_id}/log').status_code, 404)
        self.store.log_path(job_id).write_text('Chapter 1 read in 3.2 seconds\n')
        response = self.client.get(f'/api/jobs/{job_id}/log')
        self.assertIn('Chapter 1', response.text)


class CoverTest(ApiTestCase):
    def test_serves_a_cover_already_recorded_on_the_manifest(self):
        job_id = self.upload().json()['job_id']
        self.finish(job_id)
        (self.store.output_dir(job_id) / 'cover').write_bytes(b'\xff\xd8\xff fake jpeg')
        self.store.update(job_id, cover={'file': 'cover', 'bytes': 11})

        with mock.patch('app.main.fetch_cover') as fetch_cover:
            response = self.client.get(f'/api/jobs/{job_id}/cover')
            fetch_cover.assert_not_called()

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, b'\xff\xd8\xff fake jpeg')
        self.assertEqual(response.headers['content-type'], 'image/jpeg')

    def test_sniffs_a_png_by_its_own_bytes_not_the_extensionless_filename(self):
        job_id = self.upload().json()['job_id']
        self.finish(job_id)
        png_header = b'\x89PNG\r\n\x1a\n' + b'rest of a fake png'
        (self.store.output_dir(job_id) / 'cover').write_bytes(png_header)
        self.store.update(job_id, cover={'file': 'cover', 'bytes': len(png_header)})

        response = self.client.get(f'/api/jobs/{job_id}/cover')
        self.assertEqual(response.headers['content-type'], 'image/png')

    def test_a_job_finished_before_this_feature_existed_fetches_lazily_on_first_request(self):
        job_id = self.upload().json()['job_id']
        self.finish(job_id)  # no cover recorded -- an "old" job
        self.store.update(job_id, title='A Real Book', author='A Real Author')

        with mock.patch('app.main.fetch_cover', return_value=b'lazily fetched') as fetch_cover:
            response = self.client.get(f'/api/jobs/{job_id}/cover')
            fetch_cover.assert_called_once_with('A Real Book', 'A Real Author')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, b'lazily fetched')
        # Cached: a second request must not look it up again.
        with mock.patch('app.main.fetch_cover') as fetch_cover:
            self.client.get(f'/api/jobs/{job_id}/cover')
            fetch_cover.assert_not_called()
        self.assertEqual(self.store.read(job_id)['cover']['bytes'], len(b'lazily fetched'))

    def test_no_cover_anywhere_is_410_not_500(self):
        job_id = self.upload().json()['job_id']
        self.finish(job_id)
        with mock.patch('app.main.fetch_cover', return_value=None):
            self.assertEqual(self.client.get(f'/api/jobs/{job_id}/cover').status_code, 410)

    def test_cover_before_the_job_finishes_is_409(self):
        job_id = self.upload().json()['job_id']
        self.assertEqual(self.client.get(f'/api/jobs/{job_id}/cover').status_code, 409)


class DeleteTest(ApiTestCase):
    def test_deletes_files_and_cancels_a_running_job(self):
        job_id = self.upload().json()['job_id']
        with mock.patch('app.main.revoke') as revoke:
            response = self.client.delete(f'/api/jobs/{job_id}')
        self.assertEqual(response.status_code, 200)
        revoke.assert_called_once_with(FAKE_TASK_ID)
        self.assertFalse(self.store.job_dir(job_id).exists())
        self.assertEqual(self.client.get(f'/api/jobs/{job_id}').status_code, 404)

    def test_finished_job_is_not_revoked(self):
        job_id = self.upload().json()['job_id']
        self.finish(job_id)
        with mock.patch('app.main.revoke') as revoke:
            self.client.delete(f'/api/jobs/{job_id}')
        revoke.assert_not_called()

    def test_deleting_an_unknown_job_is_404(self):
        self.assertEqual(
            self.client.delete('/api/jobs/9d0b6c4f-5e2a-4b3c-8a1b-000000000000').status_code,
            404)


class AuthTest(ApiTestCase):
    def test_requests_without_a_token_are_rejected(self):
        del self.client.headers['Authorization']
        self.assertEqual(self.upload().status_code, 401)
        self.assertEqual(self.client.get('/api/jobs').status_code, 401)

    def test_an_unknown_token_is_rejected(self):
        self.client.headers['Authorization'] = 'Bearer not-a-real-token'
        self.assertEqual(self.client.get('/api/jobs').status_code, 401)

    def test_a_valid_token_grants_access(self):
        response = self.upload()
        self.assertEqual(response.status_code, 202)
        job_id = response.json()['job_id']
        self.assertEqual(self.client.get(f'/api/jobs/{job_id}').status_code, 200)

    def test_health_voices_and_engines_stay_open(self):
        del self.client.headers['Authorization']
        self.assertEqual(self.client.get('/api/health').status_code, 200)
        self.assertEqual(self.client.get('/api/voices').status_code, 200)
        self.assertEqual(self.client.get('/api/engines').status_code, 200)


class MeTest(ApiTestCase):
    def test_returns_the_current_user(self):
        body = self.client.get('/api/me').json()
        self.assertEqual(body['email'], self.user['email'])
        self.assertFalse(body['is_admin'])

    def test_requires_a_token(self):
        del self.client.headers['Authorization']
        self.assertEqual(self.client.get('/api/me').status_code, 401)


class OwnershipTest(ApiTestCase):
    def test_a_job_is_invisible_to_a_different_user(self):
        job_id = self.upload().json()['job_id']
        _, other_token = self.make_user('other@example.com')
        self.client.headers['Authorization'] = f'Bearer {other_token}'
        self.assertEqual(self.client.get('/api/jobs').json()['jobs'], [])
        self.assertEqual(self.client.get(f'/api/jobs/{job_id}').status_code, 404)

    def test_marking_a_job_public_makes_it_visible_to_others(self):
        job_id = self.upload().json()['job_id']
        self.store.update(job_id, public=True)
        _, other_token = self.make_user('other@example.com')
        self.client.headers['Authorization'] = f'Bearer {other_token}'
        ids = {j['job_id'] for j in self.client.get('/api/jobs').json()['jobs']}
        self.assertIn(job_id, ids)
        self.assertEqual(self.client.get(f'/api/jobs/{job_id}').status_code, 200)

    def test_admin_sees_every_job_regardless_of_owner_or_public(self):
        job_id = self.upload().json()['job_id']
        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'
        ids = {j['job_id'] for j in self.client.get('/api/jobs').json()['jobs']}
        self.assertIn(job_id, ids)

    def test_a_public_job_can_only_be_deleted_by_its_owner_or_an_admin(self):
        job_id = self.upload().json()['job_id']
        self.store.update(job_id, public=True)
        _, other_token = self.make_user('other@example.com')
        self.client.headers['Authorization'] = f'Bearer {other_token}'
        # Visible (it's public) but not theirs to delete.
        self.assertEqual(self.client.delete(f'/api/jobs/{job_id}').status_code, 403)

    def test_jobs_from_before_owner_existed_stay_admin_visible(self):
        job_id = self.upload().json()['job_id']
        manifest = self.store.read(job_id)
        del manifest['owner']
        self.store.write(job_id, manifest)
        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'
        ids = {j['job_id'] for j in self.client.get('/api/jobs').json()['jobs']}
        self.assertIn(job_id, ids)


class AdminTest(ApiTestCase):
    def test_admin_routes_reject_a_non_admin(self):
        self.assertEqual(self.client.get('/api/admin/jobs').status_code, 403)
        self.assertEqual(self.client.get('/api/admin/users').status_code, 403)
        self.assertEqual(
            self.client.post('/api/admin/users', json={'email': 'x@example.com'}).status_code, 403)
        self.assertEqual(self.client.get('/api/admin/metadata-health').status_code, 403)

    def test_metadata_health_defaults_to_ok_before_any_lookup_has_ever_run(self):
        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'
        body = self.client.get('/api/admin/metadata-health').json()
        self.assertTrue(body['ok'])
        self.assertIsNone(body['last_error'])

    def test_metadata_health_reflects_a_recorded_failure(self):
        # Simulates what app.main._resolve_book_metadata does when
        # book_metadata.lookup() raises LookupUnavailable -- see
        # test_book_metadata.py for that path's own unit tests.
        from app.metadata_health import MetadataHealth
        MetadataHealth(self.settings.data_dir).record_failure('Gemini unreachable: connection refused')

        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'
        body = self.client.get('/api/admin/metadata-health').json()
        self.assertFalse(body['ok'])
        self.assertIn('connection refused', body['last_error'])

    def test_admin_can_list_every_job_with_owner_email(self):
        job_id = self.upload().json()['job_id']
        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'
        by_id = {j['job_id']: j for j in self.client.get('/api/admin/jobs').json()['jobs']}
        self.assertEqual(by_id[job_id]['owner_email'], self.user['email'])

    def test_admin_can_toggle_a_jobs_public_flag(self):
        job_id = self.upload().json()['job_id']
        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'
        response = self.client.post(f'/api/admin/jobs/{job_id}/public', json={'public': True})
        self.assertEqual(response.status_code, 200)
        self.assertTrue(self.store.read(job_id)['public'])

    def test_toggling_an_unknown_jobs_public_flag_is_404(self):
        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'
        response = self.client.post(
            '/api/admin/jobs/9d0b6c4f-5e2a-4b3c-8a1b-000000000000/public', json={'public': True})
        self.assertEqual(response.status_code, 404)

    def test_admin_can_list_users(self):
        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'
        emails = {u['email'] for u in self.client.get('/api/admin/users').json()['users']}
        self.assertEqual(emails, {self.user['email'], 'admin@example.com'})

    def test_admin_can_delete_a_user(self):
        admin, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'

        response = self.client.delete(f"/api/admin/users/{self.user['user_id']}")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {'user_id': self.user['user_id'], 'deleted': True})
        emails = {u['email'] for u in self.client.get('/api/admin/users').json()['users']}
        self.assertEqual(emails, {'admin@example.com'})

    def test_deleted_users_token_stops_working(self):
        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'
        self.client.delete(f"/api/admin/users/{self.user['user_id']}")

        self.client.headers['Authorization'] = f'Bearer {self.token}'
        self.assertEqual(self.client.get('/api/me').status_code, 401)

    def test_admin_cannot_delete_their_own_account(self):
        admin, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'

        response = self.client.delete(f"/api/admin/users/{admin['user_id']}")

        self.assertEqual(response.status_code, 400)
        emails = {u['email'] for u in self.client.get('/api/admin/users').json()['users']}
        self.assertIn('admin@example.com', emails)

    def test_deleting_an_unknown_user_is_404(self):
        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'
        response = self.client.delete('/api/admin/users/9d0b6c4f-5e2a-4b3c-8a1b-000000000000')
        self.assertEqual(response.status_code, 404)

    def test_non_admin_cannot_delete_a_user(self):
        _, other_token = self.make_user('other@example.com')
        self.client.headers['Authorization'] = f'Bearer {other_token}'
        response = self.client.delete(f"/api/admin/users/{self.user['user_id']}")
        self.assertEqual(response.status_code, 403)


class InviteTest(ApiTestCase):
    def setUp(self):
        super().setUp()
        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'

    def test_invite_creates_a_user_and_returns_a_token(self):
        response = self.client.post('/api/admin/users', json={'email': 'new@example.com'})
        self.assertEqual(response.status_code, 201)
        body = response.json()
        self.assertEqual(body['user']['email'], 'new@example.com')
        self.assertTrue(body['token'])
        # SMTP is not configured in these tests -- see InviteWithSmtpTest.
        self.assertFalse(body['email_sent'])

    def test_the_invited_user_can_authenticate_with_the_returned_token(self):
        token = self.client.post('/api/admin/users', json={'email': 'new@example.com'}).json()['token']
        self.client.headers['Authorization'] = f'Bearer {token}'
        self.assertEqual(self.client.get('/api/me').json()['email'], 'new@example.com')

    def test_inviting_the_same_email_twice_is_409(self):
        self.client.post('/api/admin/users', json={'email': 'new@example.com'})
        response = self.client.post('/api/admin/users', json={'email': 'new@example.com'})
        self.assertEqual(response.status_code, 409)


class InviteWithSmtpTest(ApiTestCase):
    env = {'REEDD_SMTP_USER': 'sender@gmail.com', 'REEDD_SMTP_APP_PASSWORD': 'app-password'}

    def setUp(self):
        super().setUp()
        _, admin_token = self.make_user('admin@example.com', is_admin=True)
        self.client.headers['Authorization'] = f'Bearer {admin_token}'

    def test_sends_an_invite_email_via_smtp(self):
        with mock.patch('app.mailer.smtplib.SMTP') as smtp_cls:
            response = self.client.post('/api/admin/users', json={'email': 'new@example.com'})
        self.assertEqual(response.status_code, 201)
        self.assertTrue(response.json()['email_sent'])
        smtp_cls.assert_called_once_with('smtp.gmail.com', 587)
        smtp = smtp_cls.return_value.__enter__.return_value
        smtp.login.assert_called_once_with('sender@gmail.com', 'app-password')

    def test_a_failed_send_does_not_block_user_creation(self):
        with mock.patch('app.mailer.smtplib.SMTP', side_effect=OSError('no route to host')):
            response = self.client.post('/api/admin/users', json={'email': 'new@example.com'})
        self.assertEqual(response.status_code, 201)
        self.assertFalse(response.json()['email_sent'])
        self.assertTrue(response.json()['token'])


if __name__ == '__main__':
    unittest.main()


class CrashReportTest(ApiTestCase):
    """The endpoint of last resort: an app that has just died leaving a trace here.

    The app cannot display its own stack trace after the process is gone, and the
    Android emulator does not run on the machine this is developed on, so this is
    how a phone hands one over.
    """

    TRACE = ('read-d-book crash report\n'
             'exception: java.lang.IllegalStateException: boom\n'
             '\tat dev.reedd.ui.library.LibraryViewModel.importAndUpload(LibraryViewModel.kt:133)\n')

    def test_report_is_stored_and_listed(self):
        response = self.client.post('/api/diagnostics/crash', content=self.TRACE.encode())

        self.assertEqual(202, response.status_code)
        body = response.json()
        self.assertTrue(body['stored'].startswith('crash-'))
        self.assertEqual(len(self.TRACE), body['bytes'])

        stored = list(self.settings.crashes_dir.glob('crash-*.txt'))
        self.assertEqual(1, len(stored))
        self.assertIn('IllegalStateException', stored[0].read_text())

        listing = self.client.get('/api/diagnostics/crashes')
        self.assertEqual(200, listing.status_code)
        self.assertIn('LibraryViewModel.importAndUpload', listing.text)

    def test_empty_report_is_rejected(self):
        response = self.client.post('/api/diagnostics/crash', content=b'')
        self.assertEqual(400, response.status_code)

    def test_listing_is_empty_before_any_crash(self):
        self.assertIn('no crash reports', self.client.get('/api/diagnostics/crashes').text)

    def test_newest_report_comes_first(self):
        self.client.post('/api/diagnostics/crash', content=b'the older one')
        self.client.post('/api/diagnostics/crash', content=b'the newer one')

        text = self.client.get('/api/diagnostics/crashes').text

        self.assertLess(text.index('the newer one'), text.index('the older one'))

    def test_a_crash_loop_cannot_fill_the_disk(self):
        from app import main
        with mock.patch.object(main, 'MAX_CRASH_FILES', 3):
            for i in range(6):
                self.client.post('/api/diagnostics/crash', content=f'crash {i}'.encode())

        kept = sorted(self.settings.crashes_dir.glob('crash-*.txt'))
        self.assertEqual(3, len(kept))
        # The ones kept are the newest.
        self.assertIn('crash 5', ''.join(f.read_text() for f in kept))

    def test_a_giant_report_is_truncated_rather_than_refused(self):
        from app import main
        response = self.client.post('/api/diagnostics/crash', content=b'x' * (400 * 1024))

        self.assertEqual(202, response.status_code)
        # Truncated to the cap rather than rejected: a partial trace still names
        # the exception, and refusing it would lose the report entirely.
        self.assertEqual(main.MAX_CRASH_BYTES, response.json()['bytes'])

    def test_non_utf8_bytes_do_not_break_it(self):
        response = self.client.post('/api/diagnostics/crash', content=b'trace \xff\xfe ends')
        self.assertEqual(202, response.status_code)
        self.assertIn('trace', self.client.get('/api/diagnostics/crashes').text)
