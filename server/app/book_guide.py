# -*- coding: utf-8 -*-
"""The book guide: a recap of each chapter and who its characters are, built
once per book so a reader can ask "who is this?" or "what happened?" --
online, and offline from the copy downloaded with the audiobook.

Spoiler-safe by construction: the guide is built one chapter at a time, in
order, and each call sees only *that* chapter plus the character list as of
the end of the previous one. Nothing in chapter N's entry can come from a
later chapter. The phone then shows only completed chapters (see the app's
guide screens), and the ask endpoint only sends the guide up to the reader's
chapter.

Written to `<job dir>/guide.json` after every chapter, so an interrupted
build (a restart, a model error) resumes where it stopped instead of paying
for the whole book again. Progress is mirrored on the job manifest as
`guide: {status, chapters_done, total, error}` for the app to show.
"""

import json
import logging
import os
import threading
import time
from pathlib import Path, PurePosixPath

from . import book_ai
from .config import get_settings
from .store import JobNotFound, JobStore

log = logging.getLogger('reedd.book_guide')

GUIDE_FILENAME = 'guide.json'
GUIDE_VERSION = 1

PENDING, RUNNING, DONE, ERROR = 'pending', 'running', 'done', 'error'

#: Below this, a "chapter" is a title page, a dedication, a contents list --
#: nothing to summarize, so no model call is spent on it.
MIN_CHAPTER_CHARS = 400
#: A single chapter longer than this is cut (rare; keeps one call bounded).
MAX_CHAPTER_CHARS = 200_000
#: Gap between model calls while building: the free tier rate-limits bursts,
#: and a guide is never on a path anyone is waiting on.
MIN_CALL_INTERVAL_S = 4.0

SYSTEM_PROMPT = """You help a reader keep track of the book they are reading. \
You are given ONE chapter of the book, plus what is already known about its \
characters from earlier chapters. Use ONLY the text you are given. Never use \
outside knowledge of this book, its author, its adaptations or what happens \
later in it, even if you recognize the book -- the reader has not read further \
and must not be spoiled. Never hint at future events. Reply with one JSON object."""

_PROMPT = """Characters known so far, from earlier chapters:
{known}

CHAPTER {number} TEXT:
<<<
{text}
>>>

Return a JSON object: {{"summary": "3-6 sentences on what happens in this \
chapter", "characters": [{{"name": "the name the text uses most", "aliases": \
["other names, nicknames or titles the text uses for them"], "description": \
"who they are and what is known about them by the end of this chapter, 1-3 \
sentences, including relationships to other characters"}}]}}.
Include only characters who appear in, or are meaningfully discussed in, this \
chapter. For a character already known, give the updated description as of the \
end of this chapter, keeping earlier facts that still hold. For non-fiction, \
treat the real people discussed as the characters. If this chapter is front \
matter, a table of contents, a license or similar, return an empty summary \
and no characters."""


def guide_path(job_id: str) -> Path:
    return JobStore(get_settings().jobs_dir).job_dir(job_id) / GUIDE_FILENAME


def read_guide(job_id: str) -> dict | None:
    path = guide_path(job_id)
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding='utf-8'))


def book_chapters(epub_path: Path) -> list[dict]:
    """Every document chapter, in spine order: `{source, title, text}`, via the
    same extraction audiblez converts with (see live_reading._extract_sentences
    for why that, keyed by resource filename, is what lines up with the app)."""
    from ebooklib import epub

    from audiblez.core import find_document_chapters_and_extract_texts

    book = epub.read_epub(str(epub_path))
    chapters = []
    for chapter in find_document_chapters_and_extract_texts(book):
        text = (chapter.extracted_text or '').strip()
        first_line = next((ln.strip() for ln in text.splitlines() if ln.strip()), '')
        chapters.append({
            'source': PurePosixPath(chapter.get_name()).name,
            'title': first_line[:80],
            'text': text,
        })
    return chapters


def known_characters(chapters: list[dict], upto: int) -> list[dict]:
    """The character list as of the end of chapter index [upto] (inclusive):
    each chapter's entries merged in order, the latest description winning and
    aliases accumulating. The app does the same merge for the offline guide.

    Matched on name *or* alias, not exact name: the model names people the way
    the chapter does, and a book does not name them consistently -- in Jekyll
    and Hyde the final chapter's "Henry Jekyll"/"Edward Hyde" are the earlier
    "Dr. Jekyll"/"Mr. Hyde", each carrying the other form as an alias. The name
    the book first used stays the display name; later names become aliases."""
    merged: list[dict] = []
    for entry in chapters[:upto + 1]:
        for c in entry.get('characters') or []:
            labels = {c['name'].lower(), *(a.lower() for a in c.get('aliases') or [])}
            existing = next((m for m in merged
                             if labels & {m['name'].lower(), *(a.lower() for a in m['aliases'])}), None)
            if existing is None:
                merged.append({'name': c['name'], 'aliases': list(c.get('aliases') or []),
                               'description': c.get('description', '')})
                continue
            existing['description'] = c.get('description') or existing['description']
            for alias in [c['name'], *(c.get('aliases') or [])]:
                if alias.lower() != existing['name'].lower() and \
                        alias.lower() not in (a.lower() for a in existing['aliases']):
                    existing['aliases'].append(alias)
    return merged


def _format_known(characters: list[dict]) -> str:
    if not characters:
        return '(none yet)'
    lines = []
    for c in characters:
        aliases = f" (also: {', '.join(c['aliases'])})" if c['aliases'] else ''
        lines.append(f"- {c['name']}{aliases}: {c['description']}")
    return '\n'.join(lines)


def _parse_chapter_reply(text: str) -> dict:
    """The model's JSON, validated by hand rather than trusted: wrong types
    are dropped, lengths are capped, so a bad reply cannot break the app's
    rendering of the guide."""
    try:
        parsed = json.loads(text)
    except (json.JSONDecodeError, TypeError) as e:
        raise book_ai.BookAIError('the guide reply was not JSON') from e
    if not isinstance(parsed, dict):
        raise book_ai.BookAIError('the guide reply was not a JSON object')
    summary = parsed.get('summary')
    characters = []
    for c in parsed.get('characters') or []:
        if not isinstance(c, dict) or not isinstance(c.get('name'), str) or not c['name'].strip():
            continue
        aliases = [a.strip()[:80] for a in c.get('aliases') or [] if isinstance(a, str) and a.strip()]
        description = c.get('description') if isinstance(c.get('description'), str) else ''
        characters.append({'name': c['name'].strip()[:80], 'aliases': aliases[:10],
                           'description': description.strip()[:600]})
    return {'summary': summary.strip()[:2000] if isinstance(summary, str) else '',
            'characters': characters[:60]}


def _save(path: Path, guide: dict) -> None:
    tmp = path.with_name(f'.{path.name}.{os.getpid()}.tmp')
    tmp.write_text(json.dumps(guide, ensure_ascii=False, indent=1), encoding='utf-8')
    os.replace(tmp, path)


def build(job_id: str, generate=book_ai.generate) -> dict:
    """Build (or finish building) the guide for one job, chapter by chapter.
    [generate] is the model call -- a seam for tests."""
    store = JobStore(get_settings().jobs_dir)
    manifest = store.read(job_id)
    epub_path = store.job_dir(job_id) / manifest['filename']
    path = guide_path(job_id)

    chapters = book_chapters(epub_path)
    guide = read_guide(job_id)
    sources = [c['source'] for c in chapters]
    if guide is None or guide.get('version') != GUIDE_VERSION or \
            [c['source'] for c in guide.get('chapters', [])] != sources:
        guide = {
            'version': GUIDE_VERSION,
            'model': f'{get_settings().book_ai_backend}:{get_settings().book_ai_model}',
            'chapters': [{'index': i, 'source': c['source'], 'title': c['title'], 'status': PENDING}
                         for i, c in enumerate(chapters)],
        }
        _save(path, guide)

    total = len(chapters)
    done = sum(1 for c in guide['chapters'] if c['status'] == DONE)
    store.update(job_id, guide={'status': RUNNING, 'chapters_done': done, 'total': total, 'error': None})
    last_call = 0.0
    try:
        for i, chapter in enumerate(chapters):
            entry = guide['chapters'][i]
            if entry['status'] == DONE:
                continue
            if len(chapter['text']) < MIN_CHAPTER_CHARS:
                entry.update(status=DONE, summary='', characters=[])
            else:
                wait = MIN_CALL_INTERVAL_S - (time.monotonic() - last_call)
                if wait > 0:
                    time.sleep(wait)
                known = known_characters(guide['chapters'], i - 1) if i > 0 else []
                prompt = _PROMPT.format(known=_format_known(known), number=i + 1,
                                        text=chapter['text'][:MAX_CHAPTER_CHARS])
                last_call = time.monotonic()
                reply = generate(SYSTEM_PROMPT, prompt, json_output=True)
                entry.update(status=DONE, **_parse_chapter_reply(reply))
            _save(path, guide)
            done += 1
            store.update(job_id, guide={'status': RUNNING, 'chapters_done': done, 'total': total, 'error': None})
    except (book_ai.BookAIError, OSError) as e:
        log.warning('guide for %s stopped at chapter %d/%d: %s', job_id, done, total, e)
        store.update(job_id, guide={'status': ERROR, 'chapters_done': done, 'total': total, 'error': str(e)})
        raise
    store.update(job_id, guide={'status': DONE, 'chapters_done': total, 'total': total, 'error': None})
    return guide


_running: set[str] = set()
_running_lock = threading.Lock()


def start_in_background(job_id: str) -> bool:
    """Build a job's guide on a daemon thread -- not the Celery worker, which
    runs one job at a time and would queue this behind hours of TTS. Returns
    False if one is already building for this job."""
    with _running_lock:
        if job_id in _running:
            return False
        _running.add(job_id)

    def run():
        try:
            build(job_id)
        except JobNotFound:
            pass  # deleted while building
        except Exception:  # noqa: BLE001 -- recorded on the manifest by build(); never kill the thread noisily
            log.exception('guide build failed for %s', job_id)
        finally:
            with _running_lock:
                _running.discard(job_id)

    threading.Thread(target=run, name=f'guide-{job_id[:8]}', daemon=True).start()
    return True


def resume_unfinished() -> int:
    """Restart guide builds a server restart interrupted (status pending/running)."""
    store = JobStore(get_settings().jobs_dir)
    resumed = 0
    for manifest in store.list(limit=10_000):
        if (manifest.get('guide') or {}).get('status') in (PENDING, RUNNING):
            resumed += start_in_background(manifest['job_id'])
    return resumed
