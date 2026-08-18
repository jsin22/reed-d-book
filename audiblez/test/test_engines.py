import unittest

from audiblez.engines import DEFAULT_ENGINE, ENGINES, engine_sample_rate, known_voices, load_engine

ALL_ENGINES = ('kokoro', 'pocket_tts', 'supertonic')


class EnginesTest(unittest.TestCase):
    def test_default_engine_is_kokoro(self):
        self.assertEqual(DEFAULT_ENGINE, 'kokoro')

    def test_every_engine_is_registered(self):
        self.assertEqual(set(ENGINES), set(ALL_ENGINES))

    def test_known_voices_lists_kokoro_voices(self):
        voices = known_voices('kokoro')
        self.assertIn('af_heart', voices)
        self.assertEqual(voices, sorted(voices))

    def test_known_voices_lists_pocket_tts_voices(self):
        voices = known_voices('pocket_tts')
        self.assertIn('alba', voices)
        self.assertEqual(voices, sorted(voices))

    def test_known_voices_lists_supertonic_voices(self):
        voices = known_voices('supertonic')
        self.assertIn('M1', voices)
        self.assertEqual(voices, sorted(voices))

    def test_voice_catalogs_do_not_overlap(self):
        catalogs = [set(known_voices(e)) for e in ALL_ENGINES]
        for a, b in zip(catalogs, catalogs[1:]):
            self.assertEqual(a & b, set())

    def test_known_voices_is_empty_for_an_unknown_engine(self):
        self.assertEqual(known_voices('not_an_engine'), [])

    def test_load_engine_raises_for_an_unknown_engine(self):
        with self.assertRaises(ValueError):
            load_engine('not_an_engine', 'some_voice')

    def test_sample_rates_are_not_silently_assumed_equal(self):
        # Kokoro and Pocket TTS happen to agree (24000); Supertonic does not
        # (44100). main() in core.py depends on this being read per engine,
        # not hardcoded -- see its use of engine_sample_rate().
        self.assertEqual(engine_sample_rate('kokoro'), 24000)
        self.assertEqual(engine_sample_rate('pocket_tts'), 24000)
        self.assertEqual(engine_sample_rate('supertonic'), 44100)

    def test_engine_sample_rate_raises_for_an_unknown_engine(self):
        with self.assertRaises(ValueError):
            engine_sample_rate('not_an_engine')


if __name__ == '__main__':
    unittest.main()
