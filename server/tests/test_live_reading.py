# -*- coding: utf-8 -*-
"""app.live_reading, driven against a fake Pocket TTS engine and a mocked
chapter extractor -- no epub parsing or torch/pocket_tts import happens
here, same discipline as test_live_tts_experiment.py."""

import os
import tempfile
import time
import unittest
from unittest import mock

import numpy as np

from app import live_reading


def _real_epub_path(tmp_dir: str, resource_name: str = 'OEBPS/chap1.xhtml') -> str:
    """A real, ebooklib-readable epub (unlike tests.support.epub_bytes' bare
    zip) with one chapter at a known, nested path -- for testing that
    _extract_sentences resolves by bare filename rather than the exact
    path, which is the whole point of the resource_href change."""
    from ebooklib import epub

    book = epub.EpubBook()
    book.set_title('A Real Book')
    book.set_language('en')
    chapter = epub.EpubHtml(title='Chapter 1', file_name=resource_name, lang='en')
    chapter.content = '<html><body><p>The first sentence. The second sentence.</p></body></html>'
    book.add_item(chapter)
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = ['nav', chapter]
    path = os.path.join(tmp_dir, 'book.epub')
    epub.write_epub(path, book)
    return path


class FakeEngine:
    def __init__(self, seconds_per_sentence=0.05):
        self.seconds_per_sentence = seconds_per_sentence

    def synthesize(self, text, voice, speed):
        frames = int(self.seconds_per_sentence * 24000)
        return [np.zeros(frames, dtype='float32')]


def _await(predicate, timeout=5.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return
        time.sleep(0.02)
    raise AssertionError('condition not met in time')


class LiveReadingTest(unittest.TestCase):
    def setUp(self):
        live_reading._sessions.clear()
        live_reading._pool = None
        live_reading._reaper_started = False
        live_reading.READ_AHEAD_TARGET_SECONDS = 0.15
        live_reading.IDLE_TIMEOUT_SECONDS = 120.0
        self.addCleanup(self._restore_constants)

        self.load_engine_patcher = mock.patch('audiblez.engines.load_engine', return_value=FakeEngine())
        self.load_engine = self.load_engine_patcher.start()
        self.addCleanup(self.load_engine_patcher.stop)

        self.extract_patcher = mock.patch(
            'app.live_reading._extract_sentences',
            return_value=['Sentence one.', 'Sentence two.', 'Sentence three.', 'Sentence four.'],
        )
        self.extract_sentences = self.extract_patcher.start()
        self.addCleanup(self.extract_patcher.stop)

    def _restore_constants(self):
        live_reading.READ_AHEAD_TARGET_SECONDS = 45.0
        live_reading.IDLE_TIMEOUT_SECONDS = 120.0

    def _pool_of(self, size):
        if live_reading._pool is None:
            live_reading._pool = live_reading.EnginePool(size)
        return live_reading._pool

    def start_session(self, size=3, **kwargs):
        with mock.patch('app.live_reading._get_pool', side_effect=lambda: self._pool_of(size)):
            session_id, _resolved_index = live_reading.start(
                book_id=kwargs.get('book_id', 'book-1'),
                epub_path=kwargs.get('epub_path', '/does/not/matter.epub'),
                resource_href=kwargs.get('resource_href', 'c1.xhtml'),
                from_sentence_index=kwargs.get('from_sentence_index', 0),
                voice=kwargs.get('voice', 'alba'),
                from_fraction=kwargs.get('from_fraction'),
            )
            return session_id

    def test_synthesizes_forward_and_stops_buffering_once_ahead_enough(self):
        session_id = self.start_session()
        _await(lambda: len(live_reading.status(session_id)['chunks']) > 0)

        # With a 0.15s read-ahead target and 0.05s per fake sentence, the
        # worker should buffer a few sentences then pause rather than racing
        # through the whole (mocked) chapter immediately.
        time.sleep(0.3)
        result = live_reading.status(session_id)
        self.assertEqual(result['status'], 'running')
        self.assertEqual(result['resource_href'], 'c1.xhtml')
        self.assertLess(len(result['chunks']), 4)
        self.assertGreater(len(result['chunks']), 0)
        self.assertEqual(result['chunks'][0]['text'], 'Sentence one.')

    def test_anchor_text_finds_the_exact_sentence_and_overrides_from_fraction(self):
        # from_fraction alone would resolve to a wrong index here (0.99 of 4
        # sentences rounds to the last one) -- anchor_text must win instead.
        with mock.patch('app.live_reading._get_pool', side_effect=lambda: self._pool_of(3)):
            session_id, resolved_index = live_reading.start(
                book_id='book-1', epub_path='/does/not/matter.epub', resource_href='c1.xhtml',
                from_sentence_index=0, voice='alba', from_fraction=0.99,
                anchor_text='... window of text around the tap ... Sentence two. ... more text after ...',
            )
        self.assertEqual(resolved_index, 1)
        self.assertEqual(live_reading.status(session_id)['cursor'], 1)

    def test_anchor_text_falls_back_to_from_fraction_when_nothing_matches(self):
        with mock.patch('app.live_reading._get_pool', side_effect=lambda: self._pool_of(3)):
            _session_id, resolved_index = live_reading.start(
                book_id='book-1', epub_path='/does/not/matter.epub', resource_href='c1.xhtml',
                from_sentence_index=0, voice='alba', from_fraction=0.5,
                anchor_text='completely unrelated text that appears nowhere in this chapter',
            )
        self.assertEqual(resolved_index, 2)  # 0.5 of 4 sentences

    def test_from_fraction_resolves_to_a_real_sentence_index_and_overrides_from_sentence_index(self):
        with mock.patch('app.live_reading._get_pool', side_effect=lambda: self._pool_of(3)):
            session_id, resolved_index = live_reading.start(
                book_id='book-1', epub_path='/does/not/matter.epub', resource_href='c1.xhtml',
                from_sentence_index=99,  # must be ignored -- from_fraction wins
                voice='alba', from_fraction=0.5,
            )
        # 4 fake sentences (see setUp): 0.5 of the way through is index 2.
        self.assertEqual(resolved_index, 2)
        self.assertEqual(live_reading.status(session_id)['cursor'], 2)

    def test_from_fraction_clamps_into_range(self):
        with mock.patch('app.live_reading._get_pool', side_effect=lambda: self._pool_of(3)):
            _session_id, resolved_index = live_reading.start(
                book_id='book-1', epub_path='/does/not/matter.epub', resource_href='c1.xhtml',
                from_sentence_index=0, voice='alba', from_fraction=1.0,
            )
        self.assertEqual(resolved_index, 3)  # len(sentences) - 1, never out of range

    def test_advance_prunes_consumed_chunks_and_lets_it_finish(self):
        session_id = self.start_session()
        _await(lambda: len(live_reading.status(session_id)['chunks']) > 0)

        for cursor in range(4):
            live_reading.advance(session_id, cursor)
            time.sleep(0.05)

        _await(lambda: live_reading.status(session_id)['status'] == 'done', timeout=5.0)
        result = live_reading.status(session_id)
        self.assertEqual(result['status'], 'done')
        # Every chunk at or after the final cursor position should still be
        # there; nothing before it should have survived pruning.
        self.assertTrue(all(c['index'] >= 3 for c in result['chunks']))

    def test_chunk_audio_is_a_real_wav_and_absent_indices_return_none(self):
        session_id = self.start_session()
        _await(lambda: len(live_reading.status(session_id)['chunks']) > 0)
        audio = live_reading.chunk_audio(session_id, 0)
        self.assertIsNotNone(audio)
        self.assertTrue(audio.startswith(b'RIFF'))
        self.assertIsNone(live_reading.chunk_audio(session_id, 999))

    def test_unknown_session_operations_return_none_not_a_crash(self):
        self.assertIsNone(live_reading.status('nope'))
        self.assertIsNone(live_reading.advance('nope', 0))
        self.assertIsNone(live_reading.chunk_audio('nope', 0))
        live_reading.stop('nope')  # must not raise

    def test_busy_once_the_pool_is_exhausted(self):
        # A long fake chapter with no read-ahead cap: keeps the worker
        # actively synthesizing (holding the slot) well past this test's own
        # synchronous assertion below, unlike the short 4-sentence default.
        live_reading.READ_AHEAD_TARGET_SECONDS = 1000.0
        self.extract_sentences.return_value = ['A sentence.'] * 1000
        with mock.patch('app.live_reading._get_pool', side_effect=lambda: self._pool_of(1)):
            live_reading.start(book_id='b1', epub_path='x', resource_href='c1.xhtml', from_sentence_index=0, voice='alba')
            with self.assertRaises(live_reading.Busy):
                live_reading.start(book_id='b2', epub_path='x', resource_href='c1.xhtml', from_sentence_index=0, voice='alba')

    def test_stop_releases_the_slot_for_a_new_session(self):
        with mock.patch('app.live_reading._get_pool', side_effect=lambda: self._pool_of(1)):
            first, _ = live_reading.start(book_id='b1', epub_path='x', resource_href='c1.xhtml', from_sentence_index=0, voice='alba')
            _await(lambda: len(live_reading.status(first)['chunks']) > 0)
            live_reading.stop(first)
            _await(lambda: live_reading._pool._slots[0].in_use is False)
            second, _ = live_reading.start(book_id='b2', epub_path='x', resource_href='c1.xhtml', from_sentence_index=0, voice='alba')
            self.assertIsNotNone(live_reading.status(second))

    def test_an_unresolvable_resource_href_releases_the_slot_rather_than_leaking_it(self):
        self.extract_sentences.side_effect = live_reading.UnknownChapter("no chapter matches 'nope.xhtml'")
        with mock.patch('app.live_reading._get_pool', side_effect=lambda: self._pool_of(1)):
            with self.assertRaises(live_reading.UnknownChapter):
                live_reading.start(book_id='b1', epub_path='x', resource_href='nope.xhtml', from_sentence_index=0, voice='alba')
            self.assertFalse(live_reading._pool._slots[0].in_use)

    def test_a_synthesis_failure_is_reported_and_releases_the_slot(self):
        broken_engine = mock.Mock()
        broken_engine.synthesize.side_effect = RuntimeError('boom')
        with mock.patch('audiblez.engines.load_engine', return_value=broken_engine), \
                mock.patch('app.live_reading._get_pool', side_effect=lambda: self._pool_of(1)):
            session_id, _ = live_reading.start(book_id='b1', epub_path='x', resource_href='c1.xhtml', from_sentence_index=0, voice='alba')
            _await(lambda: live_reading.status(session_id)['status'] == 'error')
            self.assertIn('boom', live_reading.status(session_id)['error'])
            _await(lambda: live_reading._pool._slots[0].in_use is False)

    def test_shutdown_stops_every_session_and_joins_its_worker(self):
        with mock.patch('app.live_reading._get_pool', side_effect=lambda: self._pool_of(2)):
            a, _ = live_reading.start(book_id='a', epub_path='x', resource_href='c1.xhtml', from_sentence_index=0, voice='alba')
            b, _ = live_reading.start(book_id='b', epub_path='x', resource_href='c1.xhtml', from_sentence_index=0, voice='alba')
            _await(lambda: len(live_reading.status(a)['chunks']) > 0)
            _await(lambda: len(live_reading.status(b)['chunks']) > 0)
            threads = [live_reading._sessions[a].thread, live_reading._sessions[b].thread]

            live_reading.shutdown()

            self.assertIsNone(live_reading.status(a))
            self.assertIsNone(live_reading.status(b))
            for thread in threads:
                self.assertFalse(thread.is_alive())
            self.assertFalse(any(slot.in_use for slot in live_reading._pool._slots))

    def test_idle_session_is_reaped_and_releases_its_slot(self):
        live_reading.IDLE_TIMEOUT_SECONDS = 0.05
        with mock.patch('app.live_reading._REAPER_INTERVAL_SECONDS', 0.05), \
                mock.patch('app.live_reading._get_pool', side_effect=lambda: self._pool_of(1)):
            session_id, _ = live_reading.start(book_id='b1', epub_path='x', resource_href='c1.xhtml', from_sentence_index=0, voice='alba')
            _await(lambda: len(live_reading.status(session_id)['chunks']) > 0)
            _await(lambda: live_reading.status(session_id) is None, timeout=3.0)
            _await(lambda: live_reading._pool._slots[0].in_use is False)


class ExtractSentencesResourceResolutionTest(unittest.TestCase):
    """Real epub parsing, no mocks -- the bare-filename resolution is the
    actual new behavior this phase depends on, worth testing for real."""

    def test_resolves_by_bare_filename_even_when_the_full_path_differs(self):
        with tempfile.TemporaryDirectory() as tmp:
            epub_path = _real_epub_path(tmp, resource_name='OEBPS/chap1.xhtml')
            # The href a client would send (Readium's own path convention)
            # need not match ebooklib's internal one exactly -- only the
            # bare filename has to agree.
            sentences = live_reading._extract_sentences(epub_path, 'EPUB/chap1.xhtml')
        self.assertEqual(sentences, ['The first sentence.', 'The second sentence.'])

    def test_an_unmatched_resource_href_raises_unknown_chapter(self):
        with tempfile.TemporaryDirectory() as tmp:
            epub_path = _real_epub_path(tmp)
            with self.assertRaises(live_reading.UnknownChapter):
                live_reading._extract_sentences(epub_path, 'does-not-exist.xhtml')


class ResolveAnchorTest(unittest.TestCase):
    """Pure text matching, no engine/pool involved -- see `_resolve_anchor`'s
    own doc for why this exists at all (from_fraction alone drifts when the
    two extractions' sentence counts disagree)."""

    sentences = [
        'He picked up the flowers.',
        'She had looked at me-or so he fancied.',
        'It was a quiet evening in the garden.',
    ]

    def test_picks_the_sentence_closest_to_the_windows_own_center(self):
        # Both the first and second sentence appear in this window, but the
        # tap (the window's center) sits inside the second one.
        anchor = 'He picked up the flowers. She had looked at me-or so he fancied. It was quiet.'
        self.assertEqual(1, live_reading._resolve_anchor(self.sentences, anchor))

    def test_survives_typographic_differences_between_anchor_and_sentence(self):
        anchor = 'she had looked at me—or so he fancied.'  # em dash vs. the sentence's own plain hyphen
        self.assertEqual(1, live_reading._resolve_anchor(self.sentences, anchor))

    def test_no_match_returns_none(self):
        self.assertIsNone(live_reading._resolve_anchor(self.sentences, 'nothing like this appears anywhere'))

    def test_a_sentence_shorter_than_the_minimum_is_not_a_candidate(self):
        short_sentences = ['Dead!', 'A much longer sentence that easily clears the minimum length.']
        anchor = 'He said "Dead!" and then A much longer sentence that easily clears the minimum length.'
        # "Dead!" appears in the anchor too, but is too short to trust --
        # the real, confirmed-live risk this guards against (a short
        # exclamation recurring more than once in a novel).
        self.assertEqual(1, live_reading._resolve_anchor(short_sentences, anchor))

    def test_empty_anchor_returns_none(self):
        self.assertIsNone(live_reading._resolve_anchor(self.sentences, '   '))

    def test_anchor_offset_finds_the_tapped_sentence_even_when_the_window_is_off_center(self):
        # Reproduces a real, confirmed-live bug: a tap on a resource's first
        # line clamps the anchor window to [0, tap+300), so the window's own
        # midpoint sits well past the actual tap -- resolving (with no
        # anchor_offset) to a later sentence than the one tapped.
        anchor = 'He picked up the flowers. She had looked at me-or so he fancied. It was quiet.'
        # Without anchor_offset, the window's own center (index ~40) lands
        # inside the second sentence.
        self.assertEqual(1, live_reading._resolve_anchor(self.sentences, anchor))
        # With anchor_offset pointing at the actual tap -- right at the very
        # start, matching a first-line tap -- the first sentence wins instead.
        self.assertEqual(0, live_reading._resolve_anchor(self.sentences, anchor, anchor_offset=0))


if __name__ == '__main__':
    unittest.main()
