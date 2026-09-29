import unittest

from audiblez.quote_split import split_into_spans

NBSP = ' '
SOFT_HYPHEN = '­'


class SplitIntoSpansPunctuationNormalizationTest(unittest.TestCase):
    """Pocket TTS's tokenizer has no vocabulary entry for curly/typographic
    punctuation -- confirmed by direct tokenizer inspection, a curly
    apostrophe falls back to raw UTF-8 byte-tokens the model was never
    meaningfully trained on, and it just drops the sound ("can't" typed with
    a curly apostrophe is read aloud as "can"). split_into_spans folds these
    to their plain-ASCII equivalents before anything reaches the engine.
    """

    def test_a_curly_apostrophe_in_a_contraction_is_folded_to_straight(self):
        self.assertEqual(
            split_into_spans('Narration with can’t in it.'),
            [('narration', "Narration with can't in it.")],
        )

    def test_curly_double_quotes_still_split_into_a_quote_span(self):
        self.assertEqual(
            split_into_spans('He said, “can’t you see?” she asked.'),
            [
                ('narration', 'He said,'),
                ('quote', "can't you see?"),
                ('narration', ' she asked.'),
            ],
        )

    def test_a_curly_ellipsis_is_folded_to_three_periods(self):
        self.assertEqual(
            split_into_spans('Wait… what?'),
            [('narration', 'Wait... what?')],
        )

    def test_a_non_breaking_space_is_folded_to_a_plain_space(self):
        spans = split_into_spans(f'Left{NBSP}alone she left.')
        joined = ' '.join(sentence for _kind, sentence in spans)
        self.assertNotIn(NBSP, joined)
        self.assertIn('Left alone', joined)

    def test_a_soft_hyphen_is_dropped(self):
        self.assertEqual(
            split_into_spans(f'extra{SOFT_HYPHEN}ordinary'),
            [('narration', 'extraordinary')],
        )

    def test_a_straight_apostrophe_is_left_alone(self):
        self.assertEqual(
            split_into_spans("Narration with can't in it."),
            [('narration', "Narration with can't in it.")],
        )


if __name__ == '__main__':
    unittest.main()
