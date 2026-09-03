# -*- coding: utf-8 -*-
"""The job store: filename safety, id validation, manifest round trips."""

import io
import json
import threading
import unittest
from pathlib import Path

from app.store import (JobNotFound, JobStore, UploadTooLarge, is_job_id, looks_like_epub,
                       safe_filename)

from .support import TempDataDirTestCase, epub_bytes


class SafeFilenameTest(unittest.TestCase):
    def test_keeps_ordinary_names(self):
        self.assertEqual(safe_filename('Moby Dick.epub'), 'Moby_Dick.epub')

    def test_strips_directory_components(self):
        # audiblez builds every output path from this name, so a name that
        # escapes the job directory would let an upload write anywhere.
        self.assertEqual(safe_filename('../../etc/passwd.epub'), 'passwd.epub')
        self.assertEqual(safe_filename('/absolute/book.epub'), 'book.epub')
        self.assertNotIn('/', safe_filename('a/b/c.epub'))

    def test_always_ends_in_epub(self):
        for name in ('book', 'book.epub', 'book.EPUB', '', None):
            self.assertTrue(safe_filename(name).endswith('.epub'), name)

    def test_falls_back_when_nothing_survives(self):
        self.assertEqual(safe_filename('..epub'), 'book.epub')
        self.assertEqual(safe_filename('***.epub'), 'book.epub')

    def test_caps_length(self):
        self.assertLessEqual(len(safe_filename('x' * 500 + '.epub')), 90)


class JobIdTest(unittest.TestCase):
    def test_rejects_anything_not_generated_by_the_store(self):
        for bad in ('..', '../..', 'foo', '', None, 'x' * 36, 1):
            self.assertFalse(is_job_id(bad), bad)

    def test_accepts_a_uuid(self):
        self.assertTrue(is_job_id('7ef4a0d0-3c7f-4b2a-9d0b-6c4f5e2a1b3c'))


class JobStoreTest(TempDataDirTestCase):
    def setUp(self):
        super().setUp()
        self.store = JobStore(self.settings.jobs_dir)

    def test_create_makes_a_queued_job_with_an_output_dir(self):
        manifest = self.store.create('Book One.epub', 'alba', 1.0, 'pocket_tts')
        self.assertEqual(manifest['status'], 'queued')
        self.assertEqual(manifest['filename'], 'Book_One.epub')
        self.assertTrue(self.store.output_dir(manifest['job_id']).is_dir())
        self.assertEqual(self.store.read(manifest['job_id']), manifest)

    def test_create_records_the_owner_and_starts_private(self):
        manifest = self.store.create('Book One.epub', 'alba', 1.0, 'pocket_tts', owner='user-1')
        self.assertEqual(manifest['owner'], 'user-1')
        self.assertFalse(manifest['public'])

    def test_create_without_an_owner_is_admin_only(self):
        # Jobs made without an authenticated caller (a script, or a job from
        # before per-user accounts existed) have no owner -- see app.main._visible.
        manifest = self.store.create('Book One.epub', 'alba', 1.0, 'pocket_tts')
        self.assertIsNone(manifest['owner'])

    def test_unknown_and_malformed_ids_both_raise(self):
        self.assertRaises(JobNotFound, self.store.read, '9d0b6c4f-5e2a-4b3c-8a1b-000000000000')
        self.assertRaises(JobNotFound, self.store.read, '../../../etc')
        self.assertRaises(JobNotFound, self.store.job_dir, '..')

    def test_update_merges_and_persists(self):
        job_id = self.store.create('b.epub', 'alba', 1.0, 'pocket_tts')['job_id']
        self.store.update(job_id, status='running', progress=12)
        self.store.update(job_id, progress=40)
        manifest = self.store.read(job_id)
        self.assertEqual((manifest['status'], manifest['progress']), ('running', 40))
        self.assertEqual(manifest['voice'], 'alba')

    def test_update_serializes_a_racing_reader_instead_of_losing_a_write(self):
        # Confirmed live: the FastAPI handler's `jobs.update(job_id,
        # celery_task_id=...)` right after enqueueing raced the Celery task's
        # own `status=RUNNING` write -- whichever read the manifest first but
        # wrote it back second silently clobbered the other's change with
        # the stale value it had read, leaving a job stuck at
        # `status: queued` forever while `progress` climbed underneath it.
        # This reproduces that shape: one caller's read is held open (as if
        # paused mid read-modify-write) while a second caller updates a
        # different field and completes -- without the lock in `update`,
        # the first caller's eventual write would overwrite that second
        # field's change with a stale copy.
        job_id = self.store.create('b.epub', 'alba', 1.0, 'pocket_tts')['job_id']
        started_reading = threading.Event()
        allow_write = threading.Event()
        original_read = self.store.read

        def slow_read(jid):
            result = original_read(jid)
            if jid == job_id and not started_reading.is_set():
                started_reading.set()
                allow_write.wait(timeout=5)
            return result

        self.store.read = slow_read
        try:
            racer = threading.Thread(
                target=lambda: self.store.update(job_id, celery_task_id='task-123'))
            racer.start()
            self.assertTrue(started_reading.wait(timeout=5))
            self.store.update(job_id, status='running')
            allow_write.set()
            racer.join(timeout=5)
        finally:
            self.store.read = original_read

        manifest = self.store.read(job_id)
        self.assertEqual(manifest['status'], 'running')
        self.assertEqual(manifest['celery_task_id'], 'task-123')

    def test_write_leaves_no_partial_file_behind(self):
        job_id = self.store.create('b.epub', 'alba', 1.0, 'pocket_tts')['job_id']
        self.store.update(job_id, progress=50)
        # The temp file used for the atomic replace must not survive.
        leftovers = list(self.store.job_dir(job_id).glob('.job.json*'))
        self.assertEqual(leftovers, [])
        json.loads(self.store.manifest_path(job_id).read_text())

    def test_save_upload_streams_the_whole_file(self):
        job_id = self.store.create('b.epub', 'alba', 1.0, 'pocket_tts')['job_id']
        payload = epub_bytes()
        written = self.store.save_upload(job_id, io.BytesIO(payload), max_bytes=10_000,
                                         chunk_size=16)
        path = self.store.job_dir(job_id) / 'b.epub'
        self.assertEqual(written, len(payload))
        self.assertEqual(path.read_bytes(), payload)
        self.assertTrue(looks_like_epub(path))

    def test_save_upload_refuses_oversized_and_deletes_the_partial(self):
        job_id = self.store.create('b.epub', 'alba', 1.0, 'pocket_tts')['job_id']
        with self.assertRaises(UploadTooLarge):
            self.store.save_upload(job_id, io.BytesIO(b'x' * 5000), max_bytes=100,
                                   chunk_size=16)
        self.assertFalse((self.store.job_dir(job_id) / 'b.epub').exists())

    def test_delete_removes_everything(self):
        job_id = self.store.create('b.epub', 'alba', 1.0, 'pocket_tts')['job_id']
        self.store.save_upload(job_id, io.BytesIO(epub_bytes()), max_bytes=10_000)
        self.store.delete(job_id)
        self.assertFalse(self.store.job_dir(job_id).exists())
        self.assertRaises(JobNotFound, self.store.delete, job_id)

    def test_list_is_newest_first_and_ignores_junk(self):
        ids = []
        for i in range(3):
            manifest = self.store.create(f'b{i}.epub', 'alba', 1.0, 'pocket_tts')
            # created_at has second resolution; make the order unambiguous.
            self.store.update(manifest['job_id'], created_at=f'2026-07-0{i + 1}T00:00:00+00:00')
            ids.append(manifest['job_id'])
        (self.settings.jobs_dir / 'not-a-job').mkdir()
        listed = [m['job_id'] for m in self.store.list()]
        self.assertEqual(listed, list(reversed(ids)))
        self.assertEqual(len(self.store.list(limit=1)), 1)

    def test_looks_like_epub_rejects_non_zip(self):
        path = Path(self.data_dir) / 'fake.epub'
        path.write_bytes(b'this is just text')
        self.assertFalse(looks_like_epub(path))
        self.assertFalse(looks_like_epub(Path(self.data_dir) / 'missing.epub'))


if __name__ == '__main__':
    unittest.main()
