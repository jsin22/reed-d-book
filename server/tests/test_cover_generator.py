# -*- coding: utf-8 -*-
import io
import unittest

from PIL import Image

from app.cover_generator import (
    HEIGHT,
    WIDTH,
    _contrast_ratio,
    _random_contrasting_colors,
    generate_placeholder_cover,
)


class GeneratePlaceholderCoverTest(unittest.TestCase):
    def test_no_title_produces_nothing(self):
        self.assertIsNone(generate_placeholder_cover(None))
        self.assertIsNone(generate_placeholder_cover(''))

    def test_produces_a_real_png_of_the_expected_size(self):
        png_bytes = generate_placeholder_cover('The Unexpected')
        self.assertTrue(png_bytes.startswith(b'\x89PNG\r\n\x1a\n'))
        image = Image.open(io.BytesIO(png_bytes))
        self.assertEqual(image.size, (WIDTH, HEIGHT))

    def test_a_title_too_long_to_fit_still_renders_without_raising(self):
        long_title = 'A Very Long Subtitle That Goes On and On ' * 5
        png_bytes = generate_placeholder_cover(long_title)
        self.assertTrue(png_bytes.startswith(b'\x89PNG\r\n\x1a\n'))


class RandomContrastingColorsTest(unittest.TestCase):
    def test_background_and_text_are_different_and_readable(self):
        for _ in range(50):
            background, text = _random_contrasting_colors()
            self.assertNotEqual(background, text)
            self.assertGreaterEqual(_contrast_ratio(background, text), 4.5)


if __name__ == '__main__':
    unittest.main()
