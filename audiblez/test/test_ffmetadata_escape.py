import unittest

from audiblez.core import _ffmetadata_escape


class FfmetadataEscapeTest(unittest.TestCase):
    def test_a_plain_title_is_unchanged(self):
        self.assertEqual('Acknowledgments', _ffmetadata_escape('Acknowledgments'))

    def test_each_special_character_is_backslash_escaped(self):
        self.assertEqual(r'a\=b', _ffmetadata_escape('a=b'))
        self.assertEqual(r'a\;b', _ffmetadata_escape('a;b'))
        self.assertEqual(r'a\#b', _ffmetadata_escape('a#b'))
        self.assertEqual('a\\\\b', _ffmetadata_escape('a\\b'))
        self.assertEqual('a\\\nb', _ffmetadata_escape('a\nb'))

    def test_a_real_title_with_a_colon_and_an_apostrophe_needs_no_escaping(self):
        # Confirmed real: "Chapter 8. The Château d'If" -- neither character
        # this format actually cares about.
        title = "Chapter 8. The Château d'If"
        self.assertEqual(title, _ffmetadata_escape(title))

    def test_multiple_special_characters_are_all_escaped(self):
        self.assertEqual(r'a\=b\;c\#d', _ffmetadata_escape('a=b;c#d'))


if __name__ == '__main__':
    unittest.main()
