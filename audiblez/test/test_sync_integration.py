# -*- coding: utf-8 -*-
"""End-to-end checks that the .json mapping actually describes the audio.

The real TTS engine (audiblez.engines.load_engine) is replaced by a fake
that returns silence proportional to the text length, so these run in under
a second and are deterministic. What is being tested is the bookkeeping, and
for that a fake voice is as good as a real one -- the timestamps are derived
from frame counts either way.

Was previously patching a `core.KPipeline` attribute left over from before
audiblez supported more than one engine -- core.py hasn't constructed a TTS
pipeline directly since that refactor moved it into audiblez.engines, so
every test here had been silently erroring (AttributeError on the patch
target) rather than actually exercising anything. `workers=1` is passed
explicitly now for the same reason this needed noticing at all: the fake
engine instance lives in this process, and a parallel chapter worker is a
forked *process*, which would not see it.
"""

import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import numpy as np
import soundfile
from ebooklib import epub

from audiblez import core, sync

FRAMES_PER_CHAR = 100  # a "voice" that reads 240 chars/second


class FakeEngine:
    """Stands in for a real TTSEngine (audiblez.engines) -- silent,
    deterministic audio proportional to the text length."""

    sample_rate = 24000

    def __init__(self, voice=None, threads=None):
        self.calls = []

    def synthesize(self, text, voice, speed):
        self.calls.append(text)
        frames = max(1, len(text) * FRAMES_PER_CHAR)
        if '||' in text:
            # Exercise the case where an engine splits one sentence into
            # several segments (Pocket TTS does this for a long sentence --
            # see PocketTTSEngine's own _stable_settings_for): the
            # sentence's duration is the sum of them.
            half = frames // 2
            return [np.zeros(half, dtype=np.float32), np.zeros(frames - half, dtype=np.float32)]
        return [np.zeros(frames, dtype=np.float32)]


def make_epub(path, title='The Test Book', creator='A. Writer', chapters=3):
    book = epub.EpubBook()
    book.set_identifier('test-book')
    book.set_title(title)
    book.set_language('en')
    book.add_author(creator)
    items = []
    for i in range(1, chapters + 1):
        item = epub.EpubHtml(title=f'Chapter {i}', file_name=f'chap_{i:02d}.xhtml', lang='en')
        item.content = (
            f'<html><body><h1>Chapter {i}</h1>'
            f'<p>This is the first sentence of chapter {i}.</p>'
            f'<p>Here is a second sentence which is a little longer than the first one.</p>'
            f'<p>And a third || sentence that the fake pipeline splits in two.</p>'
            '</body></html>')
        book.add_item(item)
        items.append(item)
    book.toc = tuple(items)
    book.spine = ['nav'] + items
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    epub.write_epub(str(path), book)
    return path


class SyncIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.epub_path = make_epub(self.dir / 'book.epub')
        self.sync_path = self.dir / 'book.json'

        # Keep m4b creation out of these tests; they are about the mapping.
        patcher = mock.patch.object(core.shutil, 'which', return_value=None)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_main(self, **kwargs):
        with mock.patch.object(core, 'load_engine', return_value=FakeEngine()):
            core.main(str(self.epub_path), voice='alba', pick_manually=False,
                      speed=1.0, output_folder=str(self.dir), workers=1, **kwargs)
        return json.loads(self.sync_path.read_text(encoding='utf-8'))

    def chapter_wavs(self):
        return sorted(self.dir.glob('book_chapter_*.wav'))

    # -- the core guarantee --------------------------------------------------

    def test_json_is_written_beside_the_audio_with_the_same_basename(self):
        self.run_main()
        self.assertTrue(self.sync_path.exists())
        self.assertEqual(json.loads(self.sync_path.read_text())['audio_file'], 'book.m4b')

    def test_total_duration_matches_the_generated_audio(self):
        payload = self.run_main()
        wav_total = sum(soundfile.info(str(w)).duration for w in self.chapter_wavs())
        self.assertAlmostEqual(payload['duration'], wav_total, places=3)
        self.assertAlmostEqual(payload['chunks'][-1]['end'], wav_total, places=3)

    def test_chapter_boundaries_match_the_wav_durations(self):
        payload = self.run_main()
        expected_start = 0.0
        for chapter, wav in zip(payload['chapters'], self.chapter_wavs()):
            self.assertAlmostEqual(chapter['start'], expected_start, places=3)
            expected_start += soundfile.info(str(wav)).duration
            self.assertAlmostEqual(chapter['end'], expected_start, places=3)

    def test_chunks_are_contiguous_and_monotonic(self):
        payload = self.run_main()
        self.assertGreater(len(payload['chunks']), 3)
        for previous, following in zip(payload['chunks'], payload['chunks'][1:]):
            self.assertLess(previous['start'], previous['end'])
            self.assertAlmostEqual(previous['end'], following['start'], places=3)

    def test_chunk_durations_are_proportional_to_their_text(self):
        payload = self.run_main()
        for chunk in payload['chunks']:
            expected = max(1, len(chunk['text']) * FRAMES_PER_CHAR) / core.sample_rate
            self.assertAlmostEqual(chunk['end'] - chunk['start'], expected, places=2,
                                   msg=f'wrong duration for {chunk["text"]!r}')

    def test_multi_segment_sentence_is_one_chunk(self):
        payload = self.run_main()
        split_chunks = [c for c in payload['chunks'] if '||' in c['text']]
        self.assertEqual(len(split_chunks), 3)  # one per chapter, not two

    def test_chunks_carry_chapter_numbers_in_order(self):
        payload = self.run_main()
        chapters = [c['chapter'] for c in payload['chunks']]
        self.assertEqual(chapters, sorted(chapters))
        self.assertEqual(set(chapters), {1, 2, 3})

    def test_first_chunk_is_the_injected_intro(self):
        # audiblez prepends "<title> - <author>" to chapter 1; it is real spoken
        # audio, so it has to appear in the mapping or everything after it drifts.
        payload = self.run_main()
        self.assertIn('The Test Book', payload['chunks'][0]['text'])
        self.assertEqual(payload['chunks'][0]['start'], 0.0)

    def test_metadata_comes_from_the_epub(self):
        payload = self.run_main()
        self.assertEqual(payload['title'], 'The Test Book')
        self.assertEqual(payload['author'], 'A. Writer')
        self.assertEqual(payload['sample_rate'], core.sample_rate)

    # -- resuming ------------------------------------------------------------

    def test_rerun_reproduces_identical_timings_from_cache(self):
        first = self.run_main()
        self.sync_path.unlink()
        second = self.run_main()  # every chapter .wav already exists
        self.assertEqual(first['chunks'], second['chunks'])
        self.assertEqual(first['chapters'], second['chapters'])

    def test_partial_resume_keeps_later_chapters_aligned(self):
        full = self.run_main()
        # Throw away the last chapter's audio and regenerate only that.
        last_wav = self.chapter_wavs()[-1]
        last_wav.unlink()
        sync.chapter_sync_path(last_wav).unlink()
        self.sync_path.unlink()
        resumed = self.run_main()
        self.assertEqual(full['chunks'], resumed['chunks'])

    def test_missing_cache_falls_back_to_one_chunk_but_keeps_the_clock(self):
        full = self.run_main()
        # Simulate wavs produced by an older audiblez that wrote no sync cache.
        for wav in self.chapter_wavs():
            sync.chapter_sync_path(wav).unlink()
        self.sync_path.unlink()
        degraded = self.run_main()

        # One coarse chunk per chapter instead of per sentence...
        self.assertEqual(len(degraded['chunks']), 3)
        # ...but the chapter clock, and so the total, is still exact.
        self.assertAlmostEqual(degraded['duration'], full['duration'], places=3)
        self.assertEqual(degraded['chapters'], full['chapters'])

    def test_stale_cache_cannot_desynchronise_later_chapters(self):
        full = self.run_main()
        first_wav = self.chapter_wavs()[0]
        stale = {'duration': 0.5, 'chunks': [{'text': 'wrong', 'start': 0.0, 'end': 0.5}]}
        sync.chapter_sync_path(first_wav).write_text(json.dumps(stale))
        self.sync_path.unlink()
        payload = self.run_main()

        # The .wav on disk is what ffmpeg will concatenate, so its real duration
        # governs where chapter 2 starts -- not the lie in the cache.
        self.assertEqual(payload['chapters'], full['chapters'])
        self.assertAlmostEqual(payload['duration'], full['duration'], places=3)

    # -- truncated runs ------------------------------------------------------

    def test_max_chapters_limits_the_mapping_to_what_was_generated(self):
        payload = self.run_main(max_chapters=1)
        self.assertEqual({c['chapter'] for c in payload['chunks']}, {1})
        wav_total = sum(soundfile.info(str(w)).duration for w in self.chapter_wavs())
        self.assertAlmostEqual(payload['duration'], wav_total, places=3)


@unittest.skipIf(shutil.which('ffmpeg') is None, 'ffmpeg not installed')
class M4bTest(unittest.TestCase):
    """The mapping is only useful if the .m4b it describes really gets built."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.epub_path = make_epub(self.dir / 'book.epub', chapters=2)

    def test_m4b_and_json_are_produced_together(self):
        with mock.patch.object(core, 'load_engine', return_value=FakeEngine()):
            core.main(str(self.epub_path), voice='alba', pick_manually=False,
                      speed=1.0, output_folder=str(self.dir), workers=1)

        m4b = self.dir / 'book.m4b'
        payload = json.loads((self.dir / 'book.json').read_text(encoding='utf-8'))
        self.assertTrue(m4b.exists(), 'no .m4b was produced')
        self.assertGreater(m4b.stat().st_size, 1024)
        self.assertEqual(payload['audio_file'], m4b.name)

        # The whole point: timestamps must land inside the audio the player sees.
        m4b_duration = core.probe_duration(str(m4b))
        self.assertAlmostEqual(payload['duration'], m4b_duration, delta=0.5)
        self.assertLessEqual(payload['chunks'][-1]['end'], m4b_duration + 0.5)


if __name__ == '__main__':
    unittest.main()
