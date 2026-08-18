# -*- coding: utf-8 -*-
"""The HTTP contract the Android app codes against.

The queue is stubbed out: these tests cover the API's own behaviour, not
Celery's, and they must run without Redis or the TTS stack installed.
"""

import unittest
from unittest import mock

from fastapi.testclient import TestClient

from app.main import app
from app.store import JobStore

from .support import TempDataDirTestCase, epub_bytes

FAKE_TASK_ID = 'celery-task-1'


class ApiTestCase(TempDataDirTestCase):
    def setUp(self):
        super().setUp()
        self.client = TestClient(app)
        self.store = JobStore(self.settings.jobs_dir)
        patcher = mock.patch('app.main.enqueue', return_value=FAKE_TASK_ID)
        self.enqueue = patcher.start()
        self.addCleanup(patcher.stop)

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
        self.assertEqual(body['voice'], self.settings.default_voice)
        self.assertEqual(body['speed'], self.settings.default_speed)

    def test_accepts_explicit_voice_and_speed(self):
        body = self.upload(voice='bf_emma', speed=1.25).json()
        self.assertEqual((body['voice'], body['speed']), ('bf_emma', 1.25))

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


class UploadLimitTest(ApiTestCase):
    env = {'REEDD_MAX_UPLOAD_BYTES': '100'}

    def test_rejects_and_cleans_up_an_oversized_upload(self):
        response = self.upload(content=epub_bytes(body=b'x' * 5000))
        self.assertEqual(response.status_code, 413)
        self.assertEqual(self.store.list(), [])


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
    env = {'REEDD_API_TOKEN': 's3cret'}

    def test_requests_without_the_token_are_rejected(self):
        self.assertEqual(self.upload().status_code, 401)
        self.assertEqual(self.client.get('/api/jobs').status_code, 401)

    def test_the_token_grants_access(self):
        self.client.headers['Authorization'] = 'Bearer s3cret'
        response = self.upload()
        self.assertEqual(response.status_code, 202)
        job_id = response.json()['job_id']
        self.assertEqual(self.client.get(f'/api/jobs/{job_id}').status_code, 200)

    def test_health_stays_open_so_the_app_can_find_the_server(self):
        self.assertEqual(self.client.get('/api/health').status_code, 200)


if __name__ == '__main__':
    unittest.main()


class CrashReportTest(ApiTestCase):
    """The endpoint of last resort: an app that has just died leaving a trace here.

    The app cannot display its own stack trace after the process is gone, and the
    Android emulator does not run on the machine this is developed on, so this is
    how a phone hands one over.
    """

    TRACE = ('reed-d-book crash report\n'
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
