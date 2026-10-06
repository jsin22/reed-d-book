# -*- coding: utf-8 -*-
"""Answering a reader's question about the book they are reading, using only
the text up to where they are -- never anything later.

The reader's position arrives as an anchor (a short window of the page text
around the furthest point they have reached, plus where in it that point is)
and is placed on a sentence of the server's own chapter text by the same
matcher live reading uses, which already tolerates the app's and the
server's slightly different text extraction. Everything after that sentence
is cut before the model sees anything.

What the model gets, kept to tens of thousands of tokens rather than a whole
novel so the free tier's per-minute limits hold: the book guide's recaps of
earlier chapters and the characters as of the previous chapter
(book_guide.py), the previous chapter's text, and the current chapter up to
the cut. Without a guide (still building), the most recent chapters' raw text
stands in for the recaps.
"""

from functools import lru_cache
from pathlib import Path, PurePosixPath

from . import book_ai, book_guide

SYSTEM_PROMPT = """You answer a reader's questions about the book they are \
reading. You are given recaps of earlier chapters, the characters known so \
far, and the book's text up to the exact point the reader has reached. Answer \
ONLY from this material. The reader has not read beyond this point: never \
reveal, guess or hint at anything that happens later, and never use outside \
knowledge of this book, its author or its adaptations, even if you recognize \
it. If the material does not answer the question yet, say the book has not \
revealed that yet. Keep answers short (2-5 sentences) and plain, and refer to \
characters by the names the book uses."""

#: Budgets, in characters (~4 per token).
MAX_CURRENT_CHARS = 60_000
MAX_PREVIOUS_CHARS = 30_000
MAX_FALLBACK_CHARS = 120_000
MAX_HISTORY_TURNS = 6
MAX_TURN_CHARS = 2_000
MAX_QUESTION_CHARS = 1_000


class UnknownPosition(Exception):
    """The position does not name a chapter of this book."""


@lru_cache(maxsize=8)
def _chapters(epub_path: str, mtime: float) -> list[dict]:
    """Chapter text and its sentences, cached per epub file: a reader asking
    several questions in a row should not re-parse the book each time."""
    from audiblez.quote_split import split_into_spans

    chapters = book_guide.book_chapters(Path(epub_path))
    for chapter in chapters:
        chapter['sentences'] = [s for _kind, s in split_into_spans(chapter['text']) if s.strip()]
    return chapters


def text_up_to(chapter: dict, anchor_text: str | None, anchor_offset: int | None,
               progression: float | None) -> str:
    """The chapter's text through the sentence the reader has reached.

    The anchor is the exact way to place it; [progression] (0-1 through the
    chapter) is the fallback when the anchor cannot be matched, rounded *down*
    -- a sentence short is a missed detail, a sentence long is a spoiler.
    Neither: nothing of this chapter, for the same reason."""
    from .live_reading import _resolve_anchor

    sentences = chapter['sentences']
    index = _resolve_anchor(sentences, anchor_text, anchor_offset) if anchor_text else None
    if index is None and progression is not None:
        index = int(max(0.0, min(1.0, progression)) * len(sentences)) - 1
    if index is None or index < 0:
        return ''
    return ' '.join(s.strip() for s in sentences[:index + 1])


def _title(guide_chapter: dict) -> str:
    return guide_chapter.get('title') or 'Chapter %d' % (guide_chapter['index'] + 1)


def build_prompt(job_id: str, epub_path: Path, question: str, resource_href: str,
                 anchor_text: str | None, anchor_offset: int | None, progression: float | None,
                 history: list[dict]) -> str:
    chapters = _chapters(str(epub_path), epub_path.stat().st_mtime)
    name = PurePosixPath(resource_href).name
    current = next((i for i, c in enumerate(chapters) if c['source'] == name), None)
    if current is None:
        raise UnknownPosition(f'no chapter matches {resource_href!r}')

    guide = book_guide.read_guide(job_id)
    guide_chapters = (guide or {}).get('chapters') or []
    guide_ready = len(guide_chapters) == len(chapters) and \
        all(c.get('status') == book_guide.DONE for c in guide_chapters[:current])

    parts = []
    previous = next((i for i in range(current - 1, -1, -1)
                     if len(chapters[i]['text']) >= book_guide.MIN_CHAPTER_CHARS), None)
    if guide_ready:
        recaps = [f"- {_title(c)}: {c['summary']}" for c in guide_chapters[:current] if c.get('summary')]
        if recaps:
            parts.append('RECAP OF EARLIER CHAPTERS:\n' + '\n'.join(recaps))
        known = book_guide.known_characters(guide_chapters, current - 1) if current > 0 else []
        if known:
            parts.append('CHARACTERS SO FAR:\n' + '\n'.join(
                f"- {c['name']}" + (f" (also: {', '.join(c['aliases'])})" if c['aliases'] else '')
                + f": {c['description']}" for c in known))
        if previous is not None:
            parts.append(f"PREVIOUS CHAPTER ({chapters[previous]['title']}):\n"
                         + chapters[previous]['text'][-MAX_PREVIOUS_CHARS:])
    else:
        # No guide yet: as much recent raw text as the budget allows instead.
        earlier, budget = [], MAX_FALLBACK_CHARS
        for i in range(current - 1, -1, -1):
            text = chapters[i]['text']
            if len(text) < book_guide.MIN_CHAPTER_CHARS:
                continue
            if budget <= 0:
                break
            earlier.insert(0, f"({chapters[i]['title']})\n" + text[-budget:])
            budget -= len(text)
        if earlier:
            parts.append('EARLIER TEXT:\n' + '\n\n'.join(earlier))

    here = text_up_to(chapters[current], anchor_text, anchor_offset, progression)
    parts.append(f"CURRENT CHAPTER ({chapters[current]['title']}), UP TO WHERE THE READER IS:\n"
                 + (here[-MAX_CURRENT_CHARS:] if here else '(the reader is at the start of this chapter)'))

    turns = [t for t in history[-MAX_HISTORY_TURNS:] if t.get('q') and t.get('a')]
    if turns:
        parts.append('CONVERSATION SO FAR:\n' + '\n'.join(
            f"Reader: {t['q'][:MAX_TURN_CHARS]}\nYou: {t['a'][:MAX_TURN_CHARS]}" for t in turns))
    parts.append('QUESTION: ' + question[:MAX_QUESTION_CHARS])
    return '\n\n'.join(parts)


def ask(job_id: str, epub_path: Path, question: str, resource_href: str,
        anchor_text: str | None = None, anchor_offset: int | None = None,
        progression: float | None = None, history: list[dict] | None = None,
        generate=None) -> str:
    prompt = build_prompt(job_id, epub_path, question, resource_href, anchor_text,
                          anchor_offset, progression, history or [])
    # Looked up per call, not bound as a default: the backend is configuration.
    return (generate or book_ai.generate)(SYSTEM_PROMPT, prompt).strip()
