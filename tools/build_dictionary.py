#!/usr/bin/env python3
"""Build the app's offline dictionary from the WordNet 3.0 database files.

Schema is normalised on purpose: a synset's definition is shared by every word in
it, so storing the gloss per word would duplicate most of the text several times
over. Multi-word entries ("united states of america") are dropped -- tapping a
single word on a page can never match one, and they are a large share of the rows.
"""
import re
import sqlite3
import sys
from pathlib import Path

DICT = Path(sys.argv[1])
OUT = Path(sys.argv[2])
MAX_SENSES = 3

POS_NAME = {'n': 'noun', 'v': 'verb', 'a': 'adjective', 's': 'adjective', 'r': 'adverb'}
POS_FILES = {'noun': 'n', 'verb': 'v', 'adj': 'a', 'adv': 'r'}


def definition_of(gloss: str) -> str:
    """Drop the quoted examples, keep the definition."""
    parts = [p.strip() for p in gloss.split(';')]
    kept = [p for p in parts if not p.startswith('"')]
    return '; '.join(p for p in kept if p).strip()


def load_synsets():
    """(pos_file, offset) -> (pos_name, definition)."""
    synsets = {}
    for name, _ in POS_FILES.items():
        path = DICT / f'data.{name}'
        for line in path.read_text(encoding='latin-1').splitlines():
            if line.startswith('  '):
                continue  # licence header
            left, _, gloss = line.partition('|')
            if not gloss:
                continue
            fields = left.split()
            offset, ss_type = fields[0], fields[2]
            definition = definition_of(gloss)
            if definition:
                synsets[(name, offset)] = (POS_NAME.get(ss_type, ss_type), definition)
    return synsets


def load_senses():
    """word -> [(pos_file, offset)] in WordNet's sense order (most common first)."""
    senses = {}
    for name in POS_FILES:
        path = DICT / f'index.{name}'
        for line in path.read_text(encoding='latin-1').splitlines():
            if line.startswith('  '):
                continue
            fields = line.split()
            lemma = fields[0]
            if '_' in lemma:
                continue  # multi-word; a single tapped word cannot match it
            synset_cnt = int(fields[2])
            p_cnt = int(fields[3])
            offsets = fields[4 + p_cnt + 2:][:synset_cnt]
            senses.setdefault(lemma.lower(), []).extend((name, o) for o in offsets)
    return senses


def load_forms():
    """Irregular inflections: 'went' -> 'go', 'children' -> 'child'."""
    forms = {}
    for name in POS_FILES:
        path = DICT / f'{name}.exc'
        if not path.is_file():
            continue
        for line in path.read_text(encoding='latin-1').splitlines():
            parts = line.split()
            if len(parts) >= 2 and '_' not in parts[0]:
                forms.setdefault(parts[0].lower(), parts[1].lower())
    return forms


def main():
    synsets = load_synsets()
    senses = load_senses()
    forms = load_forms()

    OUT.unlink(missing_ok=True)
    db = sqlite3.connect(OUT)
    db.executescript("""
        PRAGMA journal_mode = OFF;
        CREATE TABLE synsets (id INTEGER PRIMARY KEY, pos TEXT NOT NULL, gloss TEXT NOT NULL);
        CREATE TABLE senses (word TEXT NOT NULL, rank INTEGER NOT NULL, synset INTEGER NOT NULL);
        CREATE TABLE forms (form TEXT PRIMARY KEY, base TEXT NOT NULL);
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)

    synset_ids = {}
    rows = []
    for key, (pos, gloss) in synsets.items():
        synset_ids[key] = len(synset_ids) + 1
        rows.append((synset_ids[key], pos, gloss))
    db.executemany('INSERT INTO synsets VALUES (?, ?, ?)', rows)

    sense_rows = []
    for word, keys in senses.items():
        rank = 0
        seen = set()
        for key in keys:
            sid = synset_ids.get(key)
            if sid is None or sid in seen:
                continue
            seen.add(sid)
            sense_rows.append((word, rank, sid))
            rank += 1
            if rank >= MAX_SENSES:
                break
    db.executemany('INSERT INTO senses VALUES (?, ?, ?)', sense_rows)

    db.executemany('INSERT OR IGNORE INTO forms VALUES (?, ?)', forms.items())
    db.executemany('INSERT INTO meta VALUES (?, ?)', [
        ('source', 'WordNet 3.0, Princeton University'),
        ('licence', 'see dictionary-LICENSE.txt'),
        ('max_senses', str(MAX_SENSES)),
    ])

    # Synsets reached only by multi-word lemmas, or by senses past the cap, are
    # dead weight: about a third of them, and they are pure gloss text.
    db.execute('DELETE FROM synsets WHERE id NOT IN (SELECT DISTINCT synset FROM senses)')

    db.execute('CREATE INDEX idx_senses_word ON senses(word, rank)')
    db.commit()
    db.execute('VACUUM')
    db.close()

    print(f'synsets   {len(rows):>7} (before pruning)')
    print(f'senses    {len(sense_rows):>7}')
    print(f'words     {len(senses):>7}')
    print(f'forms     {len(forms):>7}')
    print(f'size      {OUT.stat().st_size / 1024 / 1024:.1f} MB')


main()
