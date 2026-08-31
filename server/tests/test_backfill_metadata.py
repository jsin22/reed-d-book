# -*- coding: utf-8 -*-
import io
from unittest import mock

from app.backfill_metadata import backfill
from app.store import JobStore

from .support import TempDataDirTestCase, epub_with_metadata

FICTION_HORROR = {'category': 'Fiction', 'genres': ['Horror'], 'source': 'gemini', 'raw': {}}


class BackfillMetadataTest(TempDataDirTestCase):
    def _job_with_epub(self, jobs, title=None, author=None, filename='book.epub'):
        manifest = jobs.create(filename, voice='v', speed=1.0, engine='pocket_tts')
        job_id = manifest['job_id']
        jobs.save_upload(job_id, io.BytesIO(epub_with_metadata(title=title, author=author)), max_bytes=10_000_000)
        return job_id

    def test_backfills_title_author_category_and_genres_from_the_epub(self):
        jobs = JobStore(self.settings.jobs_dir)
        job_id = self._job_with_epub(jobs, title='The Shining', author='Stephen King')

        with mock.patch('app.backfill_metadata.lookup', return_value=FICTION_HORROR):
            backfill()

        manifest = jobs.read(job_id)
        self.assertEqual(manifest['title'], 'The Shining')
        self.assertEqual(manifest['author'], 'Stephen King')
        self.assertEqual(manifest['category'], 'Fiction')
        self.assertEqual(manifest['genres'], ['Horror'])

    def test_dry_run_reports_but_does_not_write(self):
        jobs = JobStore(self.settings.jobs_dir)
        job_id = self._job_with_epub(jobs, title='Dune', author='Frank Herbert')

        with mock.patch('app.backfill_metadata.lookup', return_value=FICTION_HORROR):
            backfill(dry_run=True)

        manifest = jobs.read(job_id)
        self.assertIsNone(manifest['title'])
        self.assertIsNone(manifest['category'])
        self.assertEqual(manifest['genres'], [])

    def test_a_job_already_resolved_is_skipped_without_looking_up_again(self):
        jobs = JobStore(self.settings.jobs_dir)
        job_id = self._job_with_epub(jobs, title='Already Done', author='Someone')
        jobs.update(job_id, category='Fiction', genres=['Romance'])

        with mock.patch('app.backfill_metadata.lookup') as lookup_mock:
            backfill()

        lookup_mock.assert_not_called()

    def test_a_job_missing_its_epub_file_is_skipped_rather_than_raising(self):
        jobs = JobStore(self.settings.jobs_dir)
        manifest = jobs.create('never-uploaded.epub', voice='v', speed=1.0, engine='pocket_tts')

        with mock.patch('app.backfill_metadata.lookup') as lookup_mock:
            backfill()  # must not raise

        lookup_mock.assert_not_called()
        self.assertIsNone(jobs.read(manifest['job_id'])['category'])

    def test_two_jobs_of_the_same_book_share_one_lookup(self):
        # The whole point of BookMetadataStore: two uploads of the same book
        # trigger the external lookup at most once, ever.
        jobs = JobStore(self.settings.jobs_dir)
        job_a = self._job_with_epub(jobs, title='Same Book', author='Same Author', filename='a.epub')
        job_b = self._job_with_epub(jobs, title='Same Book', author='Same Author', filename='b.epub')

        with mock.patch('app.backfill_metadata.lookup', return_value=FICTION_HORROR) as lookup_mock:
            backfill()

        lookup_mock.assert_called_once()
        self.assertEqual(jobs.read(job_a)['category'], 'Fiction')
        self.assertEqual(jobs.read(job_b)['category'], 'Fiction')

    def test_recheck_re_processes_an_already_resolved_job(self):
        # The whole point of --recheck: a job "resolved" before the LLM
        # enrichment pass existed is otherwise permanently skipped by the
        # ordinary already-resolved check.
        jobs = JobStore(self.settings.jobs_dir)
        job_id = self._job_with_epub(jobs, title='Lamb to the Slaughter', author='Roald Dahl')
        jobs.update(job_id, title='Lamb to the Slaughter', author='Roald Dahl',
                    category='Fiction', genres=['Horror', 'Mystery'])
        enriched = {'category': 'Fiction', 'genres': ['Horror', 'Mystery', 'Short Stories'], 'source': [], 'raw': {}}

        with mock.patch('app.backfill_metadata.lookup', return_value=enriched) as lookup_mock:
            backfill(recheck=True)

        lookup_mock.assert_called_once_with('Lamb to the Slaughter', 'Roald Dahl')
        self.assertEqual(jobs.read(job_id)['genres'], ['Horror', 'Mystery', 'Short Stories'])

    def test_recheck_bypasses_the_cache_rather_than_reusing_a_stale_entry(self):
        jobs = JobStore(self.settings.jobs_dir)
        job_id = self._job_with_epub(jobs, title='Some Book', author='Some Author')
        jobs.update(job_id, title='Some Book', author='Some Author', category='Fiction', genres=[])

        with mock.patch('app.backfill_metadata.lookup', return_value=FICTION_HORROR):
            backfill()  # populates the cache with the old (pre-enrichment) result

        with mock.patch('app.backfill_metadata.lookup', return_value=FICTION_HORROR | {'genres': ['Horror', 'Adventure']}):
            backfill(recheck=True)

        self.assertEqual(jobs.read(job_id)['genres'], ['Horror', 'Adventure'])

    def test_without_recheck_a_resolved_job_stays_untouched_even_if_lookup_would_differ(self):
        jobs = JobStore(self.settings.jobs_dir)
        job_id = self._job_with_epub(jobs, title='Already Done', author='Someone')
        jobs.update(job_id, category='Fiction', genres=['Romance'])

        with mock.patch('app.backfill_metadata.lookup', return_value=FICTION_HORROR):
            backfill()  # no --recheck

        self.assertEqual(jobs.read(job_id)['genres'], ['Romance'])

    def test_a_job_that_already_has_title_author_skips_reading_the_epub(self):
        # Should not happen in practice (a job with title/author but no
        # category/genres would already have been picked up by create_job's
        # own background task) but the manifest's own title/author is
        # trusted over the epub's if both are somehow present.
        jobs = JobStore(self.settings.jobs_dir)
        job_id = self._job_with_epub(jobs, title='Epub Title', author='Epub Author')
        jobs.update(job_id, title='Manifest Title', author='Manifest Author')

        with mock.patch('app.backfill_metadata.lookup', return_value=FICTION_HORROR) as lookup_mock:
            backfill()

        lookup_mock.assert_called_once_with('Manifest Title', 'Manifest Author')


if __name__ == '__main__':
    import unittest
    unittest.main()
