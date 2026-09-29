# -*- coding: utf-8 -*-
"""app.live_tts_experiment, driven against a fake Pocket TTS engine -- no
torch/pocket_tts import happens here, same discipline as test_tasks.py."""

import time
import unittest
from unittest import mock

import numpy as np

from app import live_tts_experiment as experiment


class FakeEngine:
    """One chunk of silence per sentence -- `duration_s` is what these tests
    actually check, not the audio content."""

    def __init__(self, seconds_per_sentence=0.2, sample_rate=24000):
        self.seconds_per_sentence = seconds_per_sentence
        self.sample_rate = sample_rate

    def synthesize(self, text, voice, speed):
        frames = int(self.seconds_per_sentence * self.sample_rate)
        return [np.zeros(frames, dtype='float32')]


def _await_completion(session_id, timeout=5.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = experiment.status(session_id)
        if result['status'] != 'running':
            return result
        time.sleep(0.02)
    raise AssertionError('session did not finish in time')


class LiveTtsExperimentTest(unittest.TestCase):
    def setUp(self):
        experiment._sessions.clear()

    @mock.patch('audiblez.quote_split.split_into_spans')
    @mock.patch('audiblez.engines.load_engine')
    @mock.patch('audiblez.engines.engine_sample_rate', return_value=24000)
    def test_produces_one_chunk_per_sentence_with_increasing_ready_at(
        self, _sample_rate, load_engine, split_into_spans,
    ):
        load_engine.return_value = FakeEngine()
        split_into_spans.return_value = [
            ('narration', 'Sentence one.'),
            ('narration', 'Sentence two.'),
            ('narration', 'Sentence three.'),
        ]

        session_id = experiment.start('irrelevant, split_into_spans is mocked', 'alba')
        result = _await_completion(session_id)

        self.assertEqual(result['status'], 'done')
        self.assertIsNone(result['error'])
        self.assertEqual([c['index'] for c in result['chunks']], [0, 1, 2])
        self.assertEqual([c['chars'] for c in result['chunks']],
                          [len('Sentence one.'), len('Sentence two.'), len('Sentence three.')])
        for chunk in result['chunks']:
            self.assertAlmostEqual(chunk['duration_s'], 0.2, places=2)
        ready_ats = [c['ready_at_s'] for c in result['chunks']]
        self.assertEqual(ready_ats, sorted(ready_ats))

    @mock.patch('audiblez.quote_split.split_into_spans', return_value=[('narration', 'One sentence.')])
    @mock.patch('audiblez.engines.load_engine')
    def test_device_cpu_hides_cuda_only_for_the_duration_of_engine_construction(
        self, load_engine, _split_into_spans,
    ):
        import torch

        seen_is_available = []

        def fake_load_engine(_name, _voice):
            seen_is_available.append(torch.cuda.is_available())
            return FakeEngine()

        load_engine.side_effect = fake_load_engine
        original_is_available = torch.cuda.is_available

        session_id = experiment.start('text', 'alba', device='cpu')
        result = _await_completion(session_id)

        self.assertEqual(seen_is_available, [False])
        self.assertEqual(result['device'], 'cpu')
        # Not left patched afterward -- a later 'auto' session must see the
        # real value again, not a permanently-hidden GPU.
        self.assertIs(torch.cuda.is_available, original_is_available)

    @mock.patch('audiblez.quote_split.split_into_spans', return_value=[('narration', 'One sentence.')])
    @mock.patch('audiblez.engines.load_engine')
    def test_default_device_is_auto_and_never_touches_cuda_is_available(self, load_engine, _split_into_spans):
        import torch

        seen_is_available = []

        def fake_load_engine(_name, _voice):
            seen_is_available.append(torch.cuda.is_available())
            return FakeEngine()

        load_engine.side_effect = fake_load_engine
        real_value = torch.cuda.is_available()

        session_id = experiment.start('text', 'alba')
        result = _await_completion(session_id)

        self.assertEqual(seen_is_available, [real_value])
        self.assertEqual(result['device'], 'auto')

    @mock.patch('audiblez.quote_split.split_into_spans')
    @mock.patch('audiblez.engines.load_engine')
    @mock.patch('audiblez.engines.engine_sample_rate', return_value=24000)
    def test_chunk_audio_is_a_real_wav_and_absent_indices_return_none(
        self, _sample_rate, load_engine, split_into_spans,
    ):
        load_engine.return_value = FakeEngine()
        split_into_spans.return_value = [('narration', 'Only one sentence.')]

        session_id = experiment.start('irrelevant', 'alba')
        _await_completion(session_id)

        audio = experiment.chunk_audio(session_id, 0)
        self.assertIsNotNone(audio)
        self.assertTrue(audio.startswith(b'RIFF'))
        self.assertIsNone(experiment.chunk_audio(session_id, 99))

    @mock.patch('audiblez.engines.load_engine', side_effect=RuntimeError('model failed to load'))
    @mock.patch('audiblez.engines.engine_sample_rate', return_value=24000)
    def test_a_load_failure_is_reported_not_swallowed(self, _sample_rate, _load_engine):
        session_id = experiment.start('some text.', 'alba')
        result = _await_completion(session_id)
        self.assertEqual(result['status'], 'error')
        self.assertIn('model failed to load', result['error'])

    def test_unknown_session_returns_none_not_a_crash(self):
        self.assertIsNone(experiment.status('does-not-exist'))
        self.assertIsNone(experiment.chunk_audio('does-not-exist', 0))

    @mock.patch('audiblez.quote_split.split_into_spans', return_value=[('narration', 'One sentence.')])
    @mock.patch('audiblez.engines.load_engine')
    @mock.patch('audiblez.engines.engine_sample_rate', return_value=24000)
    def test_old_sessions_are_evicted_once_the_cap_is_exceeded(
        self, _sample_rate, load_engine, _split_into_spans,
    ):
        load_engine.return_value = FakeEngine(seconds_per_sentence=0.01)
        first_session_id = experiment.start('text', 'alba')
        _await_completion(first_session_id)

        for _ in range(experiment._MAX_SESSIONS):
            session_id = experiment.start('text', 'alba')
            _await_completion(session_id)

        self.assertIsNone(experiment.status(first_session_id))
        self.assertLessEqual(len(experiment._sessions), experiment._MAX_SESSIONS)


if __name__ == '__main__':
    unittest.main()
