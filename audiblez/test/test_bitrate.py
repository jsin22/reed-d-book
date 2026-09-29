import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import numpy as np
import soundfile

from audiblez.core import (
    DEFAULT_BITRATE, LARGE_BOOK_BITRATE, LARGE_BOOK_HOURS, _loudnorm_filter, bitrate_for_duration, create_m4b,
    measure_loudness,
)


class BitrateForDurationTest(unittest.TestCase):
    def test_a_short_book_gets_the_default_bitrate(self):
        self.assertEqual(bitrate_for_duration(3600), DEFAULT_BITRATE)

    def test_a_book_right_at_the_threshold_gets_the_lower_bitrate(self):
        self.assertEqual(bitrate_for_duration(LARGE_BOOK_HOURS * 3600), LARGE_BOOK_BITRATE)

    def test_a_book_just_under_the_threshold_gets_the_default(self):
        self.assertEqual(bitrate_for_duration(LARGE_BOOK_HOURS * 3600 - 1), DEFAULT_BITRATE)

    def test_a_long_book_gets_the_lower_bitrate(self):
        self.assertEqual(bitrate_for_duration(40 * 3600), LARGE_BOOK_BITRATE)

    def test_unknown_duration_falls_back_to_the_default(self):
        # A caller with no timeline (only chapter files) shouldn't have a
        # book silently downgraded -- see bitrate_for_duration's own doc.
        self.assertEqual(bitrate_for_duration(None), DEFAULT_BITRATE)


@unittest.skipIf(shutil.which('ffmpeg') is None, 'ffmpeg not installed')
class MeasureLoudnessTest(unittest.TestCase):
    """`measure_loudness` -- the first pass of a proper two-pass `loudnorm`.

    Real ffmpeg, real (if synthetic) audio: what actually broke here was
    ffmpeg's own single-pass/dynamic mode continuously readjusting gain as
    it streams through the audio, confirmed live as a real reported bug
    ("muffled, like there's an echo, distorted") specific to the offline
    conversion path. A mock would just assert this function calls
    subprocess with certain arguments, not that the resulting correction
    is actually accurate -- the whole point of measuring first.
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def _sine_wav(self, name, amplitude, duration_s=5, sample_rate=24000):
        t = np.linspace(0, duration_s, int(sample_rate * duration_s), endpoint=False)
        signal = (amplitude * np.sin(2 * np.pi * 220 * t)).astype(np.float32)
        path = Path(self.tmp.name) / name
        soundfile.write(path, signal, sample_rate)
        return path

    def test_reports_every_key_a_second_pass_needs(self):
        measured = measure_loudness(self._sine_wav('mid.wav', amplitude=0.5))
        for key in ('input_i', 'input_tp', 'input_lra', 'input_thresh', 'target_offset'):
            self.assertIn(key, measured)

    def test_a_quieter_signal_measures_a_lower_integrated_loudness(self):
        loud = measure_loudness(self._sine_wav('loud.wav', amplitude=0.5))
        quiet = measure_loudness(self._sine_wav('quiet.wav', amplitude=0.05))
        self.assertLess(float(quiet['input_i']), float(loud['input_i']))

    def test_the_two_pass_correction_actually_lands_near_the_target(self):
        # The real regression check: apply create_m4b's own measure-then-
        # correct sequence end to end, then re-measure the *output* to
        # confirm the linear correction it computed actually worked --
        # not just that measure_loudness runs without crashing. Confirmed
        # by hand before this fix: single-pass loudnorm on a comparable
        # signal landed at -17.1 LUFS against a -18 target (a real, if
        # small, miss, and -- more importantly -- via continuous
        # readjustment rather than one fixed correction); two-pass here
        # should land closer, via one constant correction.
        source = self._sine_wav('source.wav', amplitude=0.5)
        measured = measure_loudness(source)
        corrected_filter = _loudnorm_filter(
            measured_I=measured['input_i'], measured_TP=measured['input_tp'],
            measured_LRA=measured['input_lra'], measured_thresh=measured['input_thresh'],
            offset=measured['target_offset'], linear='true',
        )
        corrected = Path(self.tmp.name) / 'corrected.wav'
        subprocess.run(
            ['ffmpeg', '-y', '-loglevel', 'error', '-i', str(source), '-af', corrected_filter, str(corrected)],
            check=True,
        )
        result = measure_loudness(corrected)
        self.assertAlmostEqual(float(result['input_i']), -18.0, delta=1.0)


@unittest.skipIf(shutil.which('ffmpeg') is None, 'ffmpeg not installed')
class CreateM4bSampleRateTest(unittest.TestCase):
    """Regression test for a real, confirmed bug: ffmpeg's native `aac`
    encoder silently re-tags a true 24kHz-mono source as a 96kHz stream once
    `bitrate_for_duration` drops to `LARGE_BOOK_BITRATE` (48k) for a long
    book -- the same input encodes at its real rate at 64k, but comes out at
    96000 Hz at 48k, with no `-ar` given either time. Reported live as
    "muffled, like there's an echo, distorted" on a long book, on top of (not
    explained by) the loudnorm gain-riding bug measure_loudness/
    _loudnorm_filter above already fix. `create_m4b`'s own `-ar` (from the
    engine's real `sample_rate`) is what pins this down.
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        # create_m4b expects this already written (create_index_file's job in
        # the real pipeline) -- a minimal empty chapters file is enough here.
        (Path(self.tmp.name) / 'chapters.txt').write_text(';FFMETADATA1\n')

    def _sine_wav(self, name, duration_s=2, sample_rate=24000):
        t = np.linspace(0, duration_s, int(sample_rate * duration_s), endpoint=False)
        signal = (0.5 * np.sin(2 * np.pi * 220 * t)).astype(np.float32)
        path = Path(self.tmp.name) / name
        soundfile.write(path, signal, sample_rate)
        return path

    def _m4b_sample_rate(self, path):
        proc = subprocess.run(
            ['ffprobe', '-v', 'quiet', '-select_streams', 'a:0',
             '-show_entries', 'stream=sample_rate', '-of', 'csv=p=0', str(path)],
            capture_output=True, text=True, check=True,
        )
        return int(proc.stdout.strip())

    def test_the_large_book_bitrate_keeps_the_source_sample_rate(self):
        chapter = self._sine_wav('chapter1.wav')
        create_m4b(
            [chapter], 'book.epub', None, self.tmp.name,
            duration_s=LARGE_BOOK_HOURS * 3600, sample_rate=24000,
        )
        m4b = Path(self.tmp.name) / 'book.m4b'
        self.assertTrue(m4b.exists())
        self.assertEqual(self._m4b_sample_rate(m4b), 24000)

    def test_without_an_explicit_sample_rate_the_bug_still_reproduces(self):
        # Not a desired behavior -- documents the bug this fixes: every
        # caller of create_m4b now passes sample_rate (core.main() does), but
        # if one didn't, this is what would happen.
        chapter = self._sine_wav('chapter2.wav')
        create_m4b(
            [chapter], 'book2.epub', None, self.tmp.name,
            duration_s=LARGE_BOOK_HOURS * 3600,
        )
        m4b = Path(self.tmp.name) / 'book2.m4b'
        self.assertEqual(self._m4b_sample_rate(m4b), 96000)


if __name__ == '__main__':
    unittest.main()
