# -*- coding: utf-8 -*-
"""Word lookups against the full Wiktionary dictionary (E-8).

The app ships a frequency-cut dictionary (the most common words) for instant,
offline lookups, and asks here only when a word is not in it -- rare words a
reader actually wants defined ("catholicity", "quotidian"). Same SQLite schema
as the app's copy (`tools/build_dictionary_wiktionary.py`), so a definition
reads the same whichever one answered.

Inflection handling stays in the app (`Lemmatizer.kt`): it sends the forms it
would try itself, and this adds the full dictionary's own irregular-form entry
right after the word, the same order the app uses ("went" -> "go").
"""

import sqlite3
import threading
from pathlib import Path

#: Matches the app's own `Dictionary.MAX_SENSES_SHOWN`.
MAX_SENSES = 8
#: Generous for a word plus its inflection candidates; a cap, not a contract.
MAX_CANDIDATES = 24

#: Shown after a word's ordinary meanings, as are abbreviation/initialism/
#: acronym senses: the full dictionary has 150k+ proper-noun senses, and without
#: this "go" (via "went") led with "Abbreviation of Gorontalo: a province of
#: Indonesia" and "Initialism of graphene oxide" ahead of "to move".
DEMOTED_PARTS_OF_SPEECH = ('proper noun', 'symbol', 'character', 'prefix', 'suffix', 'infix', 'punctuation')

_local = threading.local()


class DictionaryUnavailable(Exception):
    """The full dictionary has not been built on this server."""


def _connection(path: Path) -> sqlite3.Connection:
    """One read-only connection per thread (FastAPI runs sync endpoints on a
    thread pool, and a sqlite3 connection must stay on its own thread)."""
    conn = getattr(_local, 'conn', None)
    if conn is None or getattr(_local, 'path', None) != path:
        if not path.is_file():
            raise DictionaryUnavailable(str(path))
        conn = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
        _local.conn, _local.path = conn, path
    return conn


def lookup(path: Path, word: str, candidates: list[str]) -> dict | None:
    """The first of `word`, its irregular base, then `candidates` that the
    dictionary has senses for -- or None if none of them is in it."""
    conn = _connection(path)
    word = word.strip().lower()
    if not word:
        return None
    ordered = [word]
    row = conn.execute('SELECT base FROM forms WHERE form = ?', (word,)).fetchone()
    if row:
        ordered.append(row[0])
    ordered += [c.strip().lower() for c in candidates[:MAX_CANDIDATES]]

    seen = set()
    for candidate in ordered:
        if not candidate or candidate in seen:
            continue
        seen.add(candidate)
        senses = conn.execute(
            """
            SELECT senses.id, parts_of_speech.name, senses.gloss, senses.ipa
            FROM senses JOIN parts_of_speech ON parts_of_speech.id = senses.pos
            WHERE senses.word = ?
            ORDER BY
                parts_of_speech.name IN (%s)
                    OR senses.gloss LIKE 'Abbreviation of%%'
                    OR senses.gloss LIKE 'Initialism of%%'
                    OR senses.gloss LIKE 'Acronym of%%',
                senses.rank, senses.id
            LIMIT ?
            """ % ', '.join('?' * len(DEMOTED_PARTS_OF_SPEECH)),
            (candidate, *DEMOTED_PARTS_OF_SPEECH, MAX_SENSES),
        ).fetchall()
        if senses:
            return {
                'queried': word,
                'word': candidate,
                'senses': [
                    {
                        'part_of_speech': pos,
                        'definition': gloss,
                        'pronunciation': ipa,
                        'synonyms': [
                            r[0] for r in conn.execute(
                                'SELECT synonym FROM synonyms WHERE sense_id = ?', (sense_id,))
                        ],
                    }
                    for sense_id, pos, gloss, ipa in senses
                ],
            }
    return None
