#!/usr/bin/env python3
"""Build the app's offline dictionary from the kaikki.org English Wiktionary
extraction (https://kaikki.org/dictionary/English/ -- one JSON object per line,
per word/part-of-speech/etymology).

Replaces the earlier WordNet-based dictionary.db: WordNet is a lexical-semantic
database of content words, not a general dictionary, so it deliberately excludes
function words ("the", "of", "and", pronouns, ...) and includes odd technical
abbreviation entries (e.g. a noun sense for the chemical symbol "he") with nothing
to rank them below in a lookup. Wiktionary is a real general dictionary and covers
both properly.

Streams the input .jsonl.gz directly (never decompresses the whole file to disk --
it's ~2GB+ uncompressed). Two passes over the data, in memory:
  1. Group every real (non-inflected -- see "form-of" below) sense by (word, pos),
     demoting obsolete/archaic/rare/dialectal/dated/historical senses below
     ordinary ones rather than dropping them, then cap at MAX_SENSES.
  2. Write the capped result to SQLite.

Several size-reduction passes on top of the original cut of this script (each
verified against the actual resulting numbers, not applied blind):
  - FREQ_LIMIT caps the frequency allowlist (see below) to a fixed number of the
    most common words, not the whole list.
  - MAX_SENSES/MAX_SYNONYMS lowered from an earlier, more generous cut.
  - Inflected forms ("formats", "went", "children") no longer get their own full
    headword entry -- Wiktionary tags these senses `form-of` and names the base
    word directly, which is both a cleaner signal than guessing from suffix
    patterns and removes a real source of bulk: a large fraction of Wiktionary's
    English headwords are just an inflection of another one, each otherwise
    costing a full row. Still resolved correctly through the `forms` table and
    Lemmatizer's own suffix rules, same as WordNet-derived irregular forms
    always were -- see Lemmatizer.kt.
  - Part of speech stored as a small integer against a `parts_of_speech` lookup
    table, not repeated as text on every one of several hundred thousand rows.

Adds one thing the original cut never had: a pronunciation (IPA) per word,
picked from Wiktionary's own `sounds` list -- General American preferred, then
Received Pronunciation, then whatever's first with an `ipa` key. Audio file
references in the same list are never touched.
"""
import gzip
import json
import re
import sqlite3
import sys
from collections import defaultdict
from pathlib import Path

IN = Path(sys.argv[1])
OUT = Path(sys.argv[2])
FREQ_LIST = Path(sys.argv[3]) if len(sys.argv) > 3 else None

FREQ_LIMIT = 80_000
MAX_SENSES = 3
MAX_SYNONYMS = 5

# Tags that push a sense to the back of the list rather than dropping it --
# still shown if it's all a word has, never preferred over an ordinary sense.
DEMOTE_TAGS = {"obsolete", "archaic", "rare", "dialectal", "dated", "historical"}

# A headword worth indexing: letters (any script Wiktionary's word field already
# normalizes to, in practice near-always Latin for lang_code "en"), apostrophes,
# hyphens. Multi-word entries ("kick the bucket") are dropped as headwords --
# tapping a single word on a page can never match one -- but kept as synonym text.
WORD_RE = re.compile(r"^[a-z][a-z'\-]*$")

POS_LABELS = {
    "noun": "noun", "verb": "verb", "adj": "adjective", "adv": "adverb",
    "pron": "pronoun", "prep": "preposition", "conj": "conjunction",
    "det": "determiner", "article": "article", "intj": "interjection",
    "num": "numeral", "particle": "particle", "prefix": "prefix",
    "suffix": "suffix", "infix": "infix", "interfix": "interfix",
    "contraction": "contraction", "abbrev": "abbreviation", "symbol": "symbol",
    "character": "character", "punct": "punctuation", "proverb": "proverb",
    "phrase": "phrase", "name": "proper noun", "affix": "affix",
    "circumfix": "circumfix", "postp": "postposition", "prep_phrase": "prepositional phrase",
}


def pos_label(pos: str) -> str:
    return POS_LABELS.get(pos, pos)


def clean_word(raw: str) -> str | None:
    w = raw.strip().lower()
    return w if WORD_RE.match(w) else None


def leaf_gloss(glosses: list[str]) -> str:
    """A sense's `glosses` list is a breadcrumb through nested sub-senses, not
    separate definitions -- for a word with many close sub-senses (the
    articles "the"/"a", or a common verb like "run"), every sibling sense
    repeats the same broad parent gloss(es) verbatim before its own specific
    final clause, so joining the whole list (the original, simpler cut of
    this script) produced a wall of near-duplicate text per sense. The last
    element is the actual leaf-specific definition; earlier elements are
    shared framing, kept here only as a fallback for the (single-element)
    common case and if the leaf turns out to be empty.
    """
    if not glosses:
        return ""
    leaf = glosses[-1].lstrip(". ").strip()
    if not leaf:
        return glosses[0]
    if leaf[0].islower():
        leaf = leaf[0].upper() + leaf[1:]
    return leaf


def pick_ipa(sounds: list[dict]) -> str | None:
    by_tag = {}
    first = None
    for s in sounds:
        ipa = s.get("ipa")
        if not ipa:
            continue
        if first is None:
            first = ipa
        for tag in s.get("tags") or []:
            by_tag.setdefault(tag, ipa)
    return by_tag.get("General-American") or by_tag.get("Received-Pronunciation") or first


def load_freq_allowlist(path: Path, limit: int) -> set[str]:
    words = set()
    with path.open(encoding="utf-8", errors="ignore") as f:
        for line in f:
            if len(words) >= limit:
                break
            w = line.split("\t", 1)[0].strip().lower()
            if w:
                words.add(w)
    return words


def main():
    allowlist = load_freq_allowlist(FREQ_LIST, FREQ_LIMIT) if FREQ_LIST else None
    if allowlist is not None:
        print(f"frequency allowlist: {len(allowlist):,} words (top {FREQ_LIMIT:,})", file=sys.stderr)

    # (word, pos) -> list of {gloss, demoted, synonyms}
    grouped: dict[tuple[str, str], list[dict]] = defaultdict(list)
    # (word, pos) -> ipa string
    pronunciations: dict[tuple[str, str], str] = {}
    forms: dict[str, str] = {}
    form_of_only = 0

    total_lines = 0
    with gzip.open(IN, "rt", encoding="utf-8", errors="ignore") as f:
        for line in f:
            total_lines += 1
            if total_lines % 200_000 == 0:
                print(f"...{total_lines:,} lines read, {len(grouped):,} (word,pos) groups so far", file=sys.stderr)
            line = line.strip()
            if not line:
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue
            if entry.get("lang_code") != "en":
                continue
            pos = entry.get("pos")
            word = clean_word(entry.get("word", ""))
            if not word or not pos:
                continue
            if allowlist is not None and word not in allowlist:
                continue

            entry_synonyms = [s.get("word", "") for s in entry.get("synonyms") or [] if s.get("word")]
            had_real_sense = False

            for sense in entry.get("senses") or []:
                tags = set(sense.get("tags") or [])

                # An inflected form of another headword ("formats" -> "Plural
                # of format"): route straight to the forms table instead of a
                # full entry of its own -- see this script's own docstring.
                if "form-of" in tags:
                    for target in sense.get("form_of") or []:
                        base = clean_word(target.get("word", ""))
                        if base and base != word:
                            forms.setdefault(word, base)
                    continue

                glosses = [g.strip() for g in (sense.get("glosses") or sense.get("raw_glosses") or []) if g and g.strip()]
                gloss = leaf_gloss(glosses)
                if not gloss:
                    continue
                had_real_sense = True
                demoted = bool(tags & DEMOTE_TAGS)
                syns = [s.get("word", "") for s in sense.get("synonyms") or [] if s.get("word")]
                if not syns:
                    syns = entry_synonyms
                grouped[(word, pos)].append({"gloss": gloss, "demoted": demoted, "synonyms": syns})

            if not had_real_sense:
                form_of_only += 1

            if had_real_sense:
                ipa = pick_ipa(entry.get("sounds") or [])
                if ipa:
                    pronunciations.setdefault((word, pos), ipa)

            # Regular inflections named the other way (a headword's own
            # `forms` list of its plural/past/etc, rather than the inflected
            # word's own entry naming its base) -- a second, independent
            # source for the same forms table, since not every inflection
            # necessarily has its own Wiktionary entry to tag `form-of`.
            for form in entry.get("forms") or []:
                tags = set(form.get("tags") or [])
                if "alternative" in tags or "obsolete" in tags:
                    continue
                if not tags & {"plural", "present", "past", "participle", "comparative",
                                "superlative", "singular", "third-person"}:
                    continue
                base_form = clean_word(form.get("form", ""))
                if base_form and base_form != word:
                    forms.setdefault(base_form, word)

    print(f"read {total_lines:,} lines, {len(grouped):,} (word,pos) groups, "
          f"{form_of_only:,} inflection-only entries routed to forms, {len(forms):,} forms", file=sys.stderr)

    OUT.unlink(missing_ok=True)
    # Assigned from whatever part-of-speech labels actually turned up in this
    # run, not a fixed table -- POS_LABELS is a display-name mapping, not a
    # closed set (kaikki's own pos codes have grown over time, e.g.
    # "prep_phrase"; pos_label() already falls back to the raw code for
    # anything not in that mapping, so IDs need to cover that too, or a code
    # this script has never seen before would crash the build outright rather
    # than just showing a slightly less pretty label). Sorted, not insertion
    # order, so this is at least deterministic for a given input file.
    pos_names = sorted({pos_label(pos) for _, pos in grouped})
    pos_ids = {name: i + 1 for i, name in enumerate(pos_names)}

    db = sqlite3.connect(OUT)
    db.executescript("""
        PRAGMA page_size = 4096;
        PRAGMA journal_mode = OFF;
        CREATE TABLE parts_of_speech (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE senses (id INTEGER PRIMARY KEY, word TEXT NOT NULL, pos INTEGER NOT NULL,
                              rank INTEGER NOT NULL, gloss TEXT NOT NULL, ipa TEXT);
        CREATE TABLE synonyms (sense_id INTEGER NOT NULL, synonym TEXT NOT NULL);
        CREATE TABLE forms (form TEXT PRIMARY KEY, base TEXT NOT NULL);
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)
    db.executemany("INSERT INTO parts_of_speech VALUES (?, ?)", [(i, name) for name, i in pos_ids.items()])

    sense_rows = []
    synonym_rows = []
    sense_id = 0
    for (word, pos), senses in grouped.items():
        # Wiktionary splits some words across multiple etymology entries even
        # when a (word, pos) group's senses end up identical (e.g. "the" as
        # an article shows up on four separate lines) -- dedupe by gloss
        # text first, merging synonyms from every duplicate rather than
        # keeping only the first one's.
        by_gloss: dict[str, dict] = {}
        order: list[str] = []
        for sense in senses:
            key = sense["gloss"].strip().lower()
            if key not in by_gloss:
                by_gloss[key] = {"gloss": sense["gloss"], "demoted": sense["demoted"], "synonyms": list(sense["synonyms"])}
                order.append(key)
            else:
                existing = by_gloss[key]
                existing["demoted"] = existing["demoted"] and sense["demoted"]
                for syn in sense["synonyms"]:
                    if syn not in existing["synonyms"]:
                        existing["synonyms"].append(syn)
        deduped = [by_gloss[key] for key in order]

        # A word that is *also* a recorded inflection of some other word (its
        # own entry in `forms`) but whose only surviving, non-form-of senses
        # here are obscure -- entirely tagged obsolete/archaic/rare/etc., or
        # this whole group is just a "proper noun: a surname" homograph --
        # is worse than useless to keep as its own headword: it would shadow
        # the actually-wanted base-word lookup (Lemmatizer tries the literal
        # form first) with something a reader almost never means. Confirmed
        # live: "children" survived filtering with only "A surname." left,
        # "went" with only an obsolete noun sense ("a course, way, path") and
        # a surname/river name, "feet" with only an obsolete "fact; feat" --
        # each one hiding "plural of child"/"simple past of go"/"plural of
        # foot" behind a sense nobody taps the word for. Skipping the whole
        # group here means Lemmatizer's candidate list falls through past
        # the literal form to `forms`' recorded base instead, same as a pure
        # inflection-only word already does.
        if word in forms and (
            pos_label(pos) == "proper noun" or all(s["demoted"] for s in deduped)
        ):
            continue

        # Stable sort: ordinary senses first (Wiktionary's own editorial order
        # preserved within each group), demoted ones after.
        ordered = sorted(enumerate(deduped), key=lambda pair: (pair[1]["demoted"], pair[0]))
        pos_id = pos_ids[pos_label(pos)]
        ipa = pronunciations.get((word, pos))
        for rank, (_, sense) in enumerate(ordered[:MAX_SENSES]):
            sense_id += 1
            sense_rows.append((sense_id, word, pos_id, rank, sense["gloss"], ipa))
            seen = set()
            kept = 0
            for syn in sense["synonyms"]:
                syn_clean = syn.strip()
                key = syn_clean.lower()
                if not syn_clean or key == word or key in seen:
                    continue
                seen.add(key)
                synonym_rows.append((sense_id, syn_clean))
                kept += 1
                if kept >= MAX_SYNONYMS:
                    break

    db.executemany("INSERT INTO senses VALUES (?, ?, ?, ?, ?, ?)", sense_rows)
    db.executemany("INSERT INTO synonyms VALUES (?, ?)", synonym_rows)
    db.executemany("INSERT OR IGNORE INTO forms VALUES (?, ?)", forms.items())
    db.executemany("INSERT INTO meta VALUES (?, ?)", [
        ("source", "Wiktionary (en.wiktionary.org), via kaikki.org"),
        ("licence", "see dictionary-LICENSE.txt"),
        ("max_senses", str(MAX_SENSES)),
    ])

    db.execute("CREATE INDEX idx_senses_word ON senses(word, rank)")
    db.execute("CREATE INDEX idx_synonyms_sense ON synonyms(sense_id)")
    db.commit()
    db.execute("VACUUM")
    db.close()

    print(f"senses    {len(sense_rows):>9}")
    print(f"synonyms  {len(synonym_rows):>9}")
    print(f"words     {len(set(w for w, _ in grouped)):>9}")
    print(f"forms     {len(forms):>9}")
    print(f"pronunciations {len(pronunciations):>4}")
    print(f"size      {OUT.stat().st_size / 1024 / 1024:.1f} MB")


main()
