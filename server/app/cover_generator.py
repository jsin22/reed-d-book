# -*- coding: utf-8 -*-
"""A generated placeholder cover: the title on a random background, for a
book with no cover anywhere -- not embedded in the epub (audiblez's own
find_cover) and not found on Open Library either (app.cover_lookup).

Always succeeds given a non-empty title, unlike both of those: there is no
"searched and found nothing" case here, only "drew it". Kept as the last
fallback in the chain (app.tasks.convert_epub, app.main.download_cover) so a
real cover is still preferred whenever one actually exists.
"""

import io
import random

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT = 600, 900

_FONT_PATH = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
_MIN_CONTRAST_RATIO = 4.5  # WCAG AA for normal text
_MAX_CONTRAST_ATTEMPTS = 30


def generate_placeholder_cover(title: str | None) -> bytes | None:
    """A PNG cover with `title` centered on a random background, using a
    second random color for the text -- chosen for contrast against the
    background rather than purely at random, since an unreadable title
    would defeat the point. None if there is no title to draw at all.
    """
    if not title:
        return None
    background, text_color = _random_contrasting_colors()
    image = Image.new('RGB', (WIDTH, HEIGHT), color=background)
    draw = ImageDraw.Draw(image)
    margin = int(WIDTH * 0.12)
    max_width, max_height = WIDTH - 2 * margin, HEIGHT - 2 * margin
    font, lines, line_height = _fit_title(draw, title, max_width, max_height)
    total_height = line_height * len(lines)
    y = margin + (max_height - total_height) // 2
    for line in lines:
        line_width = draw.textbbox((0, 0), line, font=font)[2]
        draw.text(((WIDTH - line_width) // 2, y), line, font=font, fill=text_color)
        y += line_height
    buffer = io.BytesIO()
    image.save(buffer, format='PNG')
    return buffer.getvalue()


def _random_color() -> tuple[int, int, int]:
    return (random.randint(0, 255), random.randint(0, 255), random.randint(0, 255))


def _relative_luminance(rgb: tuple[int, int, int]) -> float:
    def channel(c):
        c /= 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (channel(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def _contrast_ratio(rgb1, rgb2) -> float:
    lighter, darker = sorted((_relative_luminance(rgb1), _relative_luminance(rgb2)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)


def _random_contrasting_colors() -> tuple[tuple[int, int, int], tuple[int, int, int]]:
    background = _random_color()
    for _ in range(_MAX_CONTRAST_ATTEMPTS):
        text = _random_color()
        if _contrast_ratio(background, text) >= _MIN_CONTRAST_RATIO:
            return background, text
    # Vanishingly unlikely (30 random tries against a mid-range background),
    # but a guaranteed-readable pairing beats looping forever. Picking
    # whichever of pure black/white contrasts more, not just "is the
    # background dark or light": relative luminance is heavily gamma-skewed,
    # so the black/white break-even point sits near luminance 0.18, not 0.5 --
    # a flat 0.5 split left a wide band of lightish backgrounds paired with
    # white text and a contrast ratio nowhere near 4.5.
    white_ratio = _contrast_ratio(background, (255, 255, 255))
    black_ratio = _contrast_ratio(background, (0, 0, 0))
    text = (255, 255, 255) if white_ratio >= black_ratio else (0, 0, 0)
    return background, text


def _load_font(size: int) -> ImageFont.FreeTypeFont:
    try:
        return ImageFont.truetype(_FONT_PATH, size)
    except OSError:
        return ImageFont.load_default()


def _wrap_text(draw: ImageDraw.ImageDraw, text: str, font, max_width: int) -> list[str]:
    lines: list[str] = []
    current = ''
    for word in text.split():
        candidate = f'{current} {word}'.strip()
        if draw.textbbox((0, 0), candidate, font=font)[2] <= max_width:
            current = candidate
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines or [text]


def _fit_title(draw: ImageDraw.ImageDraw, title: str, max_width: int, max_height: int):
    """The largest font size (72pt down to 28pt) whose wrapped title still
    fits inside the given box, falling back to the smallest size (letting the
    title run past the bottom margin) rather than failing outright."""
    font, lines, line_height = None, None, None
    for size in range(72, 27, -4):
        font = _load_font(size)
        lines = _wrap_text(draw, title, font, max_width)
        line_height = draw.textbbox((0, 0), 'Ag', font=font)[3] + 8
        if line_height * len(lines) <= max_height:
            return font, lines, line_height
    return font, lines, line_height
