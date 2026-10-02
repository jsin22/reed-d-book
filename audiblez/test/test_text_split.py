import unittest

from audiblez.text_split import split_sentences


class TitleAbbreviationTest(unittest.TestCase):
    """spaCy's sentencizer ends a sentence at every period, so "Mr. Utterson"
    was synthesized as two sentences with a pause between them."""

    def test_a_title_stays_with_the_name_after_it(self):
        self.assertEqual(
            split_sentences('Mr. Utterson the lawyer was a man. He was austere.'),
            ['Mr. Utterson the lawyer was a man.', 'He was austere.'],
        )

    def test_several_titles_in_one_sentence(self):
        self.assertEqual(
            split_sentences('The strange case of Dr. Jekyll and Mr. Hyde.'),
            ['The strange case of Dr. Jekyll and Mr. Hyde.'],
        )

    def test_the_whitespace_between_title_and_name_is_kept(self):
        self.assertEqual(split_sentences('Ask Mrs.\nPoole first.'), ['Ask Mrs.\nPoole first.'])

    def test_no_still_ends_a_sentence(self):
        # Not a title: "No." ends real sentences too often to merge.
        self.assertEqual(
            split_sentences('"No." She left. Then it rained.'),
            ['"No."', 'She left.', 'Then it rained.'],
        )

    def test_a_word_merely_ending_in_a_title_is_not_a_title(self):
        # "Dr" inside "Sandr." is not the title Dr.
        self.assertEqual(split_sentences('Meet Sandr. Then go.'), ['Meet Sandr.', 'Then go.'])


if __name__ == '__main__':
    unittest.main()
