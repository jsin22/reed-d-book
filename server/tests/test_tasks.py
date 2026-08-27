# -*- coding: utf-8 -*-
"""The Celery task, driven against a fake audiblez.

Runs the task eagerly in-process, so no broker is needed, and stubs
`_load_audiblez` so no torch/kokoro/spacy import happens either. What is being
tested is the bookkeeping the Android app polls -- not the TTS, which
audiblez' own test/test_sync_integration.py covers.
"""

import io
import os
import subprocess
import sys
import unittest
from types import SimpleNamespace

from unittest import mock

from app.store import JobStore
from app.tasks import convert_epub, output_names

from .support import TempDataDirTestCase, epub_bytes


def fake_audiblez(chapters=2, events=True, wav_bytes=b'RIFFfake', make_m4b=True,
                  make_sync=True):
    """Stand in for audiblez.core.main: writes the same files, emits the same events."""

    def main(file_path, voice, pick_manually, speed, output_folder, post_event=None, **kw):
        from pathlib import Path
        out = Path(output_folder)
        stem = Path(file_path).name.replace('.epub', '')
        if post_event:
            post_event('CORE_STARTED')
        for i in range(1, chapters + 1):
            (out / f'{stem}_chapter_{i}_{voice}_c{i}.xhtml.wav').write_bytes(wav_bytes)
            (out / f'{stem}_chapter_{i}_{voice}_c{i}.xhtml.wav.sync.json').write_text('[]')
            if post_event and events:
                post_event('CORE_PROGRESS',
                           stats=SimpleNamespace(progress=i * 100 // chapters,
                                                 eta='00:00:0%d' % i))
                post_event('CORE_CHAPTER_FINISHED', chapter_index=i)
        (out / 'chapters.txt').write_text('index')
        if make_m4b:
            (out / f'{stem}.m4b').write_bytes(b'fake m4b')
        if make_sync:
            (out / f'{stem}.json').write_text('{"version": 1, "chunks": []}')
        if post_event:
            post_event('CORE_FINISHED')

    return main


class TaskTestCase(TempDataDirTestCase):
    def setUp(self):
        super().setUp()
        self.store = JobStore(self.settings.jobs_dir)

    def make_job(self, filename='Book One.epub', voice='af_heart', speed=1.0, engine='kokoro'):
        manifest = self.store.create(filename, voice, speed, engine)
        self.store.save_upload(manifest['job_id'], io.BytesIO(epub_bytes()),
                               max_bytes=1_000_000)
        return manifest['job_id']

    def run_task(self, job_id, audiblez=None):
        with mock.patch('app.tasks._load_audiblez',
                        return_value=audiblez or fake_audiblez()):
            return convert_epub.apply(args=[job_id])


class ConversionTest(TaskTestCase):
    def test_records_the_finished_audiobook_and_sync_file(self):
        job_id = self.make_job()
        result = self.run_task(job_id)
        self.assertTrue(result.successful(), result.traceback)

        manifest = self.store.read(job_id)
        self.assertEqual(manifest['status'], 'done')
        self.assertEqual(manifest['progress'], 100)
        self.assertIsNone(manifest['error'])
        self.assertIsNotNone(manifest['started_at'])
        self.assertIsNotNone(manifest['finished_at'])
        self.assertEqual(manifest['audiobook']['file'], 'Book_One.m4b')
        self.assertEqual(manifest['sync']['file'], 'Book_One.json')
        self.assertEqual(manifest['audiobook']['bytes'], len(b'fake m4b'))
        self.assertTrue((self.store.output_dir(job_id) / 'Book_One.m4b').is_file())

    def test_passes_the_requested_voice_and_speed_through(self):
        job_id = self.make_job(voice='bm_george', speed=1.25)
        seen = {}

        def spy(file_path, voice, pick_manually, speed, output_folder, post_event=None, **kw):
            seen.update(voice=voice, speed=speed, pick_manually=pick_manually,
                        file_path=file_path)
            fake_audiblez()(file_path, voice, pick_manually, speed, output_folder, post_event)

        self.run_task(job_id, audiblez=spy)
        self.assertEqual(seen['voice'], 'bm_george')
        self.assertEqual(seen['speed'], 1.25)
        self.assertFalse(seen['pick_manually'])  # a worker has no TTY to prompt on
        self.assertTrue(seen['file_path'].endswith('Book_One.epub'))

    def test_progress_reaches_the_manifest_while_running(self):
        job_id = self.make_job()
        seen = []

        def watcher(file_path, voice, pick_manually, speed, output_folder, post_event=None, **kw):
            post_event('CORE_PROGRESS', stats=SimpleNamespace(progress=25, eta='00:01:00'))
            seen.append(dict(self.store.read(job_id)))
            post_event('CORE_CHAPTER_FINISHED', chapter_index=1)
            seen.append(dict(self.store.read(job_id)))
            fake_audiblez(events=False)(file_path, voice, pick_manually, speed, output_folder)

        self.run_task(job_id, audiblez=watcher)
        self.assertEqual(seen[0]['status'], 'running')
        self.assertEqual(seen[0]['progress'], 25)
        self.assertEqual(seen[0]['eta'], '00:01:00')
        self.assertEqual(seen[1]['chapters_done'], 1)

    def test_progress_never_exceeds_one_hundred(self):
        # audiblez really does report 105 on the sample epub: the intro line it
        # prepends to chapter 1 counts as processed but not as total characters.
        job_id = self.make_job()
        seen = []

        def overshooting(file_path, voice, pick_manually, speed, output_folder,
                         post_event=None, **kw):
            post_event('CORE_PROGRESS', stats=SimpleNamespace(progress=105, eta='x'))
            seen.append(self.store.read(job_id)['progress'])
            fake_audiblez(events=False)(file_path, voice, pick_manually, speed, output_folder)

        self.run_task(job_id, audiblez=overshooting)
        self.assertEqual(seen, [100])

    def test_repeated_percentages_do_not_rewrite_the_manifest(self):
        # CORE_PROGRESS fires per sentence; a novel would otherwise hammer the disk.
        job_id = self.make_job()
        writes = []

        def watcher(file_path, voice, pick_manually, speed, output_folder, post_event=None, **kw):
            with mock.patch.object(JobStore, 'write', autospec=True,
                                   side_effect=lambda s, j, m: writes.append(m['progress'])):
                for _ in range(50):
                    post_event('CORE_PROGRESS', stats=SimpleNamespace(progress=7, eta='x'))
            fake_audiblez(events=False)(file_path, voice, pick_manually, speed, output_folder)

        self.run_task(job_id, audiblez=watcher)
        self.assertEqual(writes, [7])

    def test_a_stuck_percentage_still_refreshes_the_eta_periodically(self):
        # BUG-21 (audiblez/BUGS.md): audiblez now revises its ETA every
        # sentence, but a long book's whole-number percentage can sit at 0 (or
        # any other single value) for well over a minute -- without a
        # time-based reason to write too, that fresher ETA never reached the
        # manifest at all, and the app showed a stale one for as long as the
        # percentage did not move.
        job_id = self.make_job()
        etas = []

        def watcher(file_path, voice, pick_manually, speed, output_folder, post_event=None, **kw):
            with mock.patch.object(JobStore, 'write', autospec=True,
                                   side_effect=lambda s, j, m: etas.append(m['eta'])):
                # Same whole-number percentage throughout -- only elapsed time
                # (not a percent change) can be the reason any of these land.
                with mock.patch('app.tasks.time.monotonic', side_effect=[0.0, 0.1, 2.1, 2.2, 4.3]):
                    for i in range(5):
                        post_event('CORE_PROGRESS', stats=SimpleNamespace(progress=0, eta=f'eta-{i}'))
            fake_audiblez(events=False)(file_path, voice, pick_manually, speed, output_folder)

        self.run_task(job_id, audiblez=watcher)
        # eta-0: first call, always saved. eta-1 (t=0.1): too soon, skipped.
        # eta-2 (t=2.1): >= 2s since the last save, saved. eta-3 (t=2.2): too
        # soon again. eta-4 (t=4.3): >= 2s since eta-2's save, saved.
        self.assertEqual(etas, ['eta-0', 'eta-2', 'eta-4'])

    def test_intermediate_wavs_are_deleted_but_deliverables_are_kept(self):
        job_id = self.make_job()
        self.run_task(job_id)
        left = sorted(p.name for p in self.store.output_dir(job_id).iterdir())
        self.assertEqual(left, ['Book_One.json', 'Book_One.m4b'])

    def test_audiblez_output_is_captured_per_job(self):
        job_id = self.make_job()

        def noisy(file_path, voice, pick_manually, speed, output_folder, post_event=None, **kw):
            print('Chapter 1 read in 3.2 seconds')
            # audiblez shells out to ffmpeg, whose errors are the usual reason a
            # job ends without an .m4b, so they have to be captured too.
            subprocess.run([sys.executable, '-c', 'print("ffmpeg would say this")'],
                           check=True)
            fake_audiblez()(file_path, voice, pick_manually, speed, output_folder, post_event)

        self.run_task(job_id, audiblez=noisy)
        log = self.store.log_path(job_id).read_text()
        self.assertIn('Chapter 1 read in 3.2 seconds', log)
        self.assertIn('ffmpeg would say this', log)

    def test_the_worker_gets_its_own_stdout_back_afterwards(self):
        # The redirection is at the fd level, so a leak would silently swallow
        # every later job's output into the first job's log file.
        job_id = self.make_job()
        before_stream, before_fd = sys.stdout, os.fstat(1)
        self.run_task(job_id)
        after_fd = os.fstat(1)
        self.assertIs(sys.stdout, before_stream)
        self.assertEqual((before_fd.st_dev, before_fd.st_ino),
                         (after_fd.st_dev, after_fd.st_ino))


class KeepIntermediateTest(TaskTestCase):
    env = {'REEDD_KEEP_INTERMEDIATE': '1'}

    def test_wavs_survive_when_asked(self):
        job_id = self.make_job()
        self.run_task(job_id)
        names = [p.name for p in self.store.output_dir(job_id).glob('*.wav')]
        self.assertEqual(len(names), 2)


class FailureTest(TaskTestCase):
    def test_a_crash_is_recorded_with_its_traceback(self):
        job_id = self.make_job()

        def boom(*args, **kwargs):
            raise RuntimeError('espeak-ng not installed')

        result = self.run_task(job_id, audiblez=boom)
        self.assertTrue(result.failed())
        manifest = self.store.read(job_id)
        self.assertEqual(manifest['status'], 'error')
        self.assertIn('espeak-ng not installed', manifest['error'])
        self.assertIsNotNone(manifest['finished_at'])

    def test_missing_deliverables_fail_the_job_rather_than_reporting_done(self):
        # ffmpeg absent: audiblez writes the .wav files and returns normally,
        # but there is no .m4b for the app to download.
        job_id = self.make_job()
        result = self.run_task(job_id, audiblez=fake_audiblez(make_m4b=False))
        self.assertTrue(result.failed())
        manifest = self.store.read(job_id)
        self.assertEqual(manifest['status'], 'error')
        self.assertIn('Book_One.m4b', manifest['error'])

    def test_a_job_deleted_mid_conversion_does_not_crash_the_worker(self):
        job_id = self.make_job()

        def deleter(file_path, voice, pick_manually, speed, output_folder, post_event=None, **kw):
            post_event('CORE_PROGRESS', stats=SimpleNamespace(progress=10, eta='x'))
            self.store.delete(job_id)

        result = self.run_task(job_id, audiblez=deleter)
        self.assertTrue(result.failed())  # the outputs are gone with the job
        self.assertNotIsInstance(result.result, KeyError)


class OutputNamesTest(unittest.TestCase):
    def test_matches_how_audiblez_names_its_output(self):
        self.assertEqual(output_names('Book_One.epub'), ('Book_One.m4b', 'Book_One.json'))


if __name__ == '__main__':
    unittest.main()
