import unittest

from audiblez.core import needs_terminal_period


class NeedsTerminalPeriodTest(unittest.TestCase):
    def test_a_bare_paragraph_needs_one(self):
        self.assertTrue(needs_terminal_period('Wedlock suits you'))

    def test_a_paragraph_already_ending_in_a_period_does_not(self):
        self.assertFalse(needs_terminal_period('He remarked.'))

    def test_dialogue_ending_in_a_curly_closing_quote_does_not(self):
        # The real, reported bug: this used to get a second, orphaned period
        # appended after the quote mark, becoming its own spurious "sentence"
        # once split -- see needs_terminal_period's own docstring.
        self.assertFalse(needs_terminal_period(
            'I think, Watson, that you have put on seven and a half pounds since I saw you.”',
        ))

    def test_an_exclamation_before_a_closing_quote_does_not(self):
        self.assertFalse(needs_terminal_period('“Seven!”'))

    def test_a_question_before_a_closing_quote_does_not(self):
        self.assertFalse(needs_terminal_period('“Then, how do you know?”'))

    def test_a_straight_quote_and_a_single_curly_quote_are_both_recognized(self):
        self.assertFalse(needs_terminal_period('She said "hello."'))
        self.assertFalse(needs_terminal_period('She said ‘hello.’'))

    def test_a_quote_with_no_terminal_punctuation_inside_still_needs_one(self):
        # Known, accepted gap -- see needs_terminal_period's own docstring.
        self.assertTrue(needs_terminal_period('“Well”'))

    def test_interrupted_dialogue_ending_in_an_em_dash_does_not(self):
        self.assertFalse(needs_terminal_period('“But your client—”'))

    def test_a_colon_introducing_a_quote_does_not(self):
        self.assertFalse(needs_terminal_period('It ran in this way:'))
