import unittest

from audiblez.core import DEFAULT_BITRATE, LARGE_BOOK_BITRATE, LARGE_BOOK_HOURS, bitrate_for_duration


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


if __name__ == '__main__':
    unittest.main()
