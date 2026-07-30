# -*- coding: utf-8 -*-
"""Tests for the sync timeline. Deliberately free of torch/kokoro/spacy imports
so they run fast anywhere: `python -m unittest test.test_sync`."""

import json
import tempfile
import unittest
from pathlib import Path

from audiblez import sync

SR = 24000


class NumFramesTest(unittest.TestCase):
    def test_plain_sequence(self):
        self.assertEqual(sync.num_frames([0.0] * 100), 100)

    def test_shaped_object(self):
        class Fake:
            shape = (48000,)
        self.assertEqual(sync.num_frames(Fake()), 48000)

    def test_leading_channel_dimension(self):
        class Fake:
            shape = (1, 12000)
        self.assertEqual(sync.num_frames(Fake()), 12000)


class TimelineTest(unittest.TestCase):
    def test_running_tally_matches_the_worked_example(self):
        # Sentence 1 takes 4.5s, so sentence 2 starts at 4.5.
        t = sync.SyncTimeline(SR)
        t.begin_chapter(1)
        t.add_chunk('Sentence one.', int(4.5 * SR))
        t.add_chunk('Sentence two.', int(2.0 * SR))
        t.end_chapter()

        self.assertEqual(
            [(c['text'], c['start'], c['end']) for c in t.chunks],
            [('Sentence one.', 0.0, 4.5), ('Sentence two.', 4.5, 6.5)])
        self.assertEqual(t.current_time, 6.5)

    def test_no_gaps_or_overlaps_across_chapters(self):
        t = sync.SyncTimeline(SR)
        for chapter in range(1, 4):
            t.begin_chapter(chapter)
            for _ in range(3):
                t.add_chunk('x', SR)  # 1s each
            t.end_chapter()

        self.assertEqual(len(t.chunks), 9)
        for previous, following in zip(t.chunks, t.chunks[1:]):
            self.assertEqual(previous['end'], following['start'])
        self.assertEqual(t.current_time, 9.0)

    def test_timestamps_are_absolute_not_chapter_relative(self):
        t = sync.SyncTimeline(SR)
        t.begin_chapter(1)
        t.add_chunk('first', 10 * SR)
        t.end_chapter()
        t.begin_chapter(2)
        first_of_second = t.add_chunk('second', SR)
        t.end_chapter()

        self.assertEqual(first_of_second['start'], 10.0)
        self.assertEqual(t.chapters[1]['start'], 10.0)
        self.assertEqual(t.chapters[1]['end'], 11.0)

    def test_chunks_carry_their_chapter(self):
        t = sync.SyncTimeline(SR)
        t.begin_chapter(7)
        chunk = t.add_chunk('x', SR)
        t.end_chapter()
        self.assertEqual(chunk['chapter'], 7)

    def test_end_chapter_returns_chapter_relative_chunks(self):
        t = sync.SyncTimeline(SR)
        t.begin_chapter(1)
        t.add_chunk('a', 5 * SR)
        t.end_chapter()
        t.begin_chapter(2)
        t.add_chunk('b', 2 * SR)
        _, relative = t.end_chapter()

        self.assertEqual(relative, [{'text': 'b', 'start': 0.0, 'end': 2.0}])

    def test_unbalanced_chapter_calls_are_errors(self):
        t = sync.SyncTimeline(SR)
        with self.assertRaises(RuntimeError):
            t.end_chapter()
        t.begin_chapter(1)
        with self.assertRaises(RuntimeError):
            t.begin_chapter(2)


class CachedChapterTest(unittest.TestCase):
    def test_cached_chapter_is_offset_onto_the_timeline(self):
        t = sync.SyncTimeline(SR)
        t.begin_chapter(1)
        t.add_chunk('live', 3 * SR)
        t.end_chapter()
        t.add_cached_chapter(2, 4.0, [
            {'text': 'cached one', 'start': 0.0, 'end': 1.5},
            {'text': 'cached two', 'start': 1.5, 'end': 4.0},
        ])

        self.assertEqual(
            [(c['text'], c['start'], c['end']) for c in t.chunks[1:]],
            [('cached one', 3.0, 4.5), ('cached two', 4.5, 7.0)])
        self.assertEqual(t.current_time, 7.0)

    def test_wav_duration_wins_over_the_cache(self):
        # A truncated or stale cache must not shift everything after it: the
        # timeline advances by the real duration of the .wav on disk.
        t = sync.SyncTimeline(SR)
        t.add_cached_chapter(1, 30.0, [{'text': 'only', 'start': 0.0, 'end': 2.0}])
        t.begin_chapter(2)
        after = t.add_chunk('next chapter', SR)
        t.end_chapter()

        self.assertEqual(after['start'], 30.0)


class OutputFileTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def test_write_sync_file(self):
        t = sync.SyncTimeline(SR)
        t.begin_chapter(1, title='Chapter 1', source='Text/Chap01.xhtml')
        t.add_chunk('Il pleut sur la ville.', int(1.5 * SR))
        t.end_chapter()

        path = sync.write_sync_file(
            self.dir / 'book.json', t, title='Book', author='Someone',
            audio_file='book.m4b')
        payload = json.loads(path.read_text(encoding='utf-8'))

        self.assertEqual(payload['version'], sync.SYNC_FORMAT_VERSION)
        self.assertEqual(payload['title'], 'Book')
        self.assertEqual(payload['author'], 'Someone')
        self.assertEqual(payload['audio_file'], 'book.m4b')
        self.assertEqual(payload['sample_rate'], SR)
        self.assertEqual(payload['duration'], 1.5)
        self.assertEqual(payload['chapters'], [
            {'index': 1, 'title': 'Chapter 1', 'source': 'Text/Chap01.xhtml',
             'start': 0.0, 'end': 1.5}])
        self.assertEqual(payload['chunks'], [
            {'text': 'Il pleut sur la ville.', 'start': 0.0, 'end': 1.5, 'chapter': 1}])

    def test_timestamps_are_rounded_to_milliseconds(self):
        t = sync.SyncTimeline(SR)
        t.begin_chapter(1)
        t.add_chunk('x', 1)  # 1/24000 == 0.0000416...
        t.end_chapter()
        path = sync.write_sync_file(self.dir / 'book.json', t)
        self.assertEqual(json.loads(path.read_text())['chunks'][0]['end'], 0.0)

    def test_chapter_cache_round_trip(self):
        wav = self.dir / 'book_chapter_1_af_heart_Chap01.xhtml.wav'
        chunks = [{'text': 'a', 'start': 0.0, 'end': 1.0}]
        path = sync.save_chapter_sync(wav, 1.0, chunks)

        self.assertEqual(path, self.dir / (wav.name + '.sync.json'))
        self.assertEqual(sync.load_chapter_sync(wav), chunks)

    def test_missing_cache_reads_as_none(self):
        self.assertIsNone(sync.load_chapter_sync(self.dir / 'nope.wav'))

    def test_corrupt_cache_reads_as_none(self):
        wav = self.dir / 'book.wav'
        sync.chapter_sync_path(wav).write_text('{not json')
        self.assertIsNone(sync.load_chapter_sync(wav))


if __name__ == '__main__':
    unittest.main()
