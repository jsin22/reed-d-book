import unittest

from audiblez.engines import DEFAULT_ENGINE, ENGINES, engine_sample_rate, known_voices, load_engine


class EnginesTest(unittest.TestCase):
    """Pocket TTS is the only engine left -- see git history on
    audiblez/engines.py for KokoroEngine and SupertonicEngine, removed
    once nothing in the project actually using this pipeline (reed-d-book's
    own server) ever selected either.
    """

    def test_default_engine_is_pocket_tts(self):
        self.assertEqual(DEFAULT_ENGINE, 'pocket_tts')

    def test_only_pocket_tts_is_registered(self):
        self.assertEqual(set(ENGINES), {'pocket_tts'})

    def test_known_voices_lists_pocket_tts_voices(self):
        voices = known_voices('pocket_tts')
        self.assertIn('alba', voices)
        self.assertEqual(voices, sorted(voices))

    def test_known_voices_is_empty_for_an_unknown_engine(self):
        self.assertEqual(known_voices('not_an_engine'), [])

    def test_load_engine_raises_for_an_unknown_engine(self):
        with self.assertRaises(ValueError):
            load_engine('not_an_engine', 'some_voice')

    def test_engine_sample_rate(self):
        self.assertEqual(engine_sample_rate('pocket_tts'), 24000)

    def test_engine_sample_rate_raises_for_an_unknown_engine(self):
        with self.assertRaises(ValueError):
            engine_sample_rate('not_an_engine')


if __name__ == '__main__':
    unittest.main()
