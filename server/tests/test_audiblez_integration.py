# -*- coding: utf-8 -*-
"""End-to-end through the real audiblez, opt-in.

Skipped unless REEDD_INTEGRATION=1, because it needs torch/kokoro/spacy/ffmpeg
and takes a minute or two even on the short sample. Everything else in this
suite runs against a fake. Still no broker: the task is applied in-process.

    REEDD_INTEGRATION=1 python -m unittest tests.test_audiblez_integration
"""

import json
import os
import unittest
from pathlib import Path

from app.store import JobStore
from app.tasks import convert_epub

from .support import TempDataDirTestCase

SAMPLE = Path(__file__).resolve().parent.parent.parent / 'sample-short.epub'


@unittest.skipUnless(os.environ.get('REEDD_INTEGRATION') == '1',
                     'set REEDD_INTEGRATION=1 to run (needs the TTS stack)')
class RealConversionTest(TempDataDirTestCase):
    def test_produces_a_playable_audiobook_and_a_usable_sync_file(self):
        store = JobStore(self.settings.jobs_dir)
        manifest = store.create(SAMPLE.name, 'af_heart', 1.0, 'kokoro')
        job_id = manifest['job_id']
        with open(SAMPLE, 'rb') as f:
            store.save_upload(job_id, f, max_bytes=self.settings.max_upload_bytes)

        result = convert_epub.apply(args=[job_id])
        self.assertTrue(result.successful(), store.log_path(job_id).read_text())

        manifest = store.read(job_id)
        self.assertEqual(manifest['status'], 'done')
        self.assertEqual(manifest['progress'], 100)
        self.assertGreater(manifest['chapters_done'], 0)
        self.assertGreater(manifest['audiobook']['bytes'], 1000)

        sync = json.loads((store.output_dir(job_id) / manifest['sync']['file']).read_text())
        self.assertEqual(sync['version'], 1)
        self.assertTrue(sync['chunks'])
        # The mapping the reader app highlights against: contiguous, in order.
        for previous, current in zip(sync['chunks'], sync['chunks'][1:]):
            self.assertAlmostEqual(previous['end'], current['start'], places=6)


if __name__ == '__main__':
    unittest.main()
