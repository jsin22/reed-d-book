import unittest
from types import SimpleNamespace

from audiblez.core import chapter_title


class ChapterTitleTest(unittest.TestCase):
    def chapter(self, heading_text):
        return SimpleNamespace(heading_text=heading_text)

    def test_a_word_heading_is_used_as_is(self):
        # The real, reported bug: The Cuckoo's Egg selects a genuine
        # Acknowledgments section as a chapter ahead of its real chapter 1,
        # and every chapter after it was displayed one number off from what
        # the book itself calls it, because nothing looked at the chapter's
        # own heading at all.
        self.assertEqual('Acknowledgments', chapter_title(1, self.chapter('Acknowledgments')))

    def test_a_bare_number_heading_is_filled_out_to_chapter_n(self):
        self.assertEqual('Chapter 1', chapter_title(2, self.chapter('1')))

    def test_a_full_chapter_label_is_used_as_is(self):
        self.assertEqual(
            "Chapter 8. The Château d'If",
            chapter_title(8, self.chapter("Chapter 8. The Château d'If")),
        )

    def test_no_heading_falls_back_to_the_generic_label(self):
        self.assertEqual('Chapter 5', chapter_title(5, self.chapter(None)))

    def test_a_blank_heading_falls_back_the_same_as_no_heading(self):
        self.assertEqual('Chapter 5', chapter_title(5, self.chapter('')))

    def test_a_chapter_object_with_no_heading_text_attribute_at_all_falls_back(self):
        # find_document_chapters_and_extract_texts always sets this, but a
        # cached/reused chapter object from an older run might not have it.
        self.assertEqual('Chapter 3', chapter_title(3, SimpleNamespace()))
