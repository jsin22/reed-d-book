# Sorting and filtering the library, plus genre/category lookup

Scoped 2026-08-27 after discussion. **Implemented** -- all 5 build-order
steps below are done; see "What actually happened" at the end of Part 1 and
Part 2 for the handful of things real API output and real compilation
changed from this plan.

**Part 1's lookup mechanism (Open Library -> Google Books) described below
is superseded by `LLM_GENRE_ENRICHMENT.md`**, scoped 2026-08-31: both are
being removed in favor of a single Gemini call. Everything else here --
the sort/filter UI, the Android data model, the caching/backfill
infrastructure, the vocabulary itself -- is unaffected and still current;
only *where* `category`/`genres` come from is changing. Read this section
for the history of why a lookup exists at all, not as the current
implementation.

## Context

The library screen currently has exactly one order: `SELECT * FROM books
ORDER BY addedAt DESC` (`BookDao.observeAll()`) -- most-recently-imported
first, no other option. The ask: let the user sort by a few useful
criteria, and filter the list down by category/genre. Neither exists as
data anywhere today, so this plan also covers looking it up.

Decided through discussion, not open questions any more:

- **Sort-by only, no manual drag-and-drop reordering.** Options: Title
  (A-Z), Author (A-Z), Recently added (`addedAt`, today's only order),
  Recently opened (`lastOpenedAt`, already tracked on every read via
  `BookRepository.updateReadingPosition`, just never used for sorting).
- **Filter, not group-by.** A book can genuinely belong to more than one
  genre (Horror *and* Fantasy), which a single "group by" bucket can't
  represent well. A filter -- "show books tagged Horror" -- handles that
  naturally, and doesn't force every book into exactly one bucket.
- **Two filter facets: Category and Genre.** (A third, Format --
  novel/play/poem/short story -- was considered and dropped: format
  signals are rarer and noisier in the source data than genre-content
  tags, so it would likely end up mostly "Unknown" and wasn't worth a
  whole separate facet.)
  - **Category**: single-valued per book -- Fiction / Non-fiction /
    Unknown. The coarse split, reliably derivable from the source data's
    own top-level heading.
  - **Genre**: multi-valued per book -- Horror, Mystery, Romance, Fantasy,
    Biography, History, Science, etc. Biography lives here, not as its own
    Category, resolving the earlier ambiguity about where it belongs.
    Multiple genre selections combine as OR (books matching *any* selected
    genre); Category and Genre combine with each other as AND.
- **Source**: looked up server-side by title+author against **Open Library
  first, Google Books as a fallback** when Open Library has no match or no
  usable subjects. Not both-always-queried-and-reconciled -- that adds
  real complexity (tie-breaking two disagreeing sources) for a best-effort
  filter label that doesn't need to be authoritative. The fallback already
  gives "whichever source has the data" for free.

## Part 1: Category/genre lookup (server)

### Where title/author come from

The server currently has **no structured title/author anywhere** -- job
manifests only store the sanitized filename (`store.py`'s `safe_filename`).
The Android app already extracts real title/author reliably via Readium at
import time (`EpubImporter`, already in `BookEntity.title`/`.author`). Send
that to the server rather than adding a second, redundant EPUB-metadata
parser server-side:

- `POST /api/jobs` gains two optional form fields, `title` and `author`
  (alongside the existing `voice`/`speed`/`engine`), sent by the app from
  data it already has.
- `JobStore.create()` gains matching optional params, stored on the
  manifest like every other field.

### New module: `server/app/book_metadata.py`

```python
def lookup(title: str, author: str | None) -> dict | None:
    """Best-effort. Returns None if neither source has anything usable --
    callers must treat that as Category=Unknown, Genres=[], not an error."""
```

- **stdlib `urllib.request` only** -- no `requests`/`httpx` dependency,
  matching `mailer.py`'s existing stdlib-only precedent and this project's
  4-package minimalism (`requirements.txt`).
- Open Library: `GET https://openlibrary.org/search.json?title=...&author=...`,
  read `docs[0].subject` (a list of free-text tags).
- Google Books fallback, only when Open Library returns nothing usable:
  `GET https://www.googleapis.com/books/v1/volumes?q=intitle:...+inauthor:...`,
  read `items[0].volumeInfo.categories` (BISAC-style hierarchical strings,
  e.g. `"Fiction / Thrillers / Suspense"`).
- **Category mapping**: a small keyword check against whichever source's
  top-level heading came back -- contains "nonfiction"/"non-fiction", or
  is a recognizably non-fiction BISAC heading (Biography & Autobiography,
  History, Science, ...) -> Non-fiction; contains "fiction" and isn't one
  of those -> Fiction; otherwise Unknown.
- **Genre mapping**: a curated starter keyword list (Horror, Mystery,
  Romance, Fantasy, Science Fiction, Thriller, Biography, History, Science,
  Poetry, Drama, Young Adult, Classic, ...), checked against *every* raw
  tag/category the source returned, keeping every match -- not just the
  first -- since a book can genuinely have several. Starter list refined
  once there's real API output to look at, not fully fixed in this doc.
- Store **both raw source payloads**, not a merged/reconciled value -- so
  if richer metadata (description, ISBN, a better cover) gets pulled in
  later, each field can independently come from whichever source had it.

### New cache store: `server/app/book_metadata_store.py`

Same shape as `UserStore`/`JobStore`: a single JSON file,
`<data_dir>/book_metadata.json`, keyed by a normalized `(title, author)`
pair (lowercased, whitespace-collapsed) so two different users uploading
the same book only ever triggers one external lookup, ever. Atomic write,
same temp-file-plus-`os.replace` pattern as the other stores.

```python
{
    "title|author (normalized key)": {
        "category": "Fiction",       # or "Non-fiction", or null
        "genres": ["Horror", "Fantasy"],   # possibly empty
        "source": "open_library",    # or "google_books", or null
        "raw": {...},                 # whatever that source returned, for debugging
        "looked_up_at": "2026-08-27T..."
    }
}
```

### When the lookup runs

This is a fast, network-bound call, not CPU-bound TTS work -- it does
**not** need Celery's worker process or its single-`--concurrency` slot
(which should stay dedicated to actual conversions). Run it via FastAPI's
`BackgroundTasks` from `create_job`, after the 202 response is already
sent: check the cache first (by normalized title/author), and only hit the
network on a genuine miss. Once resolved, write it onto the job's manifest
with the existing `store.update(job_id, category=..., genres=...)`
pattern -- the same partial-merge mechanism every other manifest field
already uses, so this needs zero changes to `tasks.py`'s conversion task.

Result: category/genre are often available **well before conversion
finishes**, since the lookup doesn't wait in the TTS queue at all.

### API surface

No new endpoint needed -- `category`/`genres` just become new fields on
the job dict, already returned wholesale by the existing `GET /api/jobs`
and `GET /api/jobs/{id}`.

### Existing books: `app/backfill_metadata.py`

Jobs created before this feature has no title/author server-side at all
(that data was never sent), so their category/genres would otherwise stay
Unknown/empty forever. Rather than leave that as a known gap, `python -m
app.backfill_metadata` (`--dry-run` to preview) is a one-off maintenance
script: for every job with no category/genres yet, it reads title/author
straight out of the job's own stored epub via `app/epub_meta.py`
(`ebooklib`'s OPF metadata -- not the sanitised upload filename, which is
underscore-normalised and usually drops the author entirely), then runs the
same `lookup()`/cache path `create_job`'s background task uses, and writes
`title`/`author`/`category`/`genres` onto the manifest with `JobStore.
update()`. Idempotent -- a job that already has a resolved category/genres
is skipped, so it is safe to run again after a future upload batch.
`ebooklib` only, never `audiblez.core`: the web process this script shares
code with must not import audiblez' heavy per-engine modules (torch/kokoro/
etc), same rule as `audiblez_meta.py`.

Run once against the live server on 2026-08-30 and resolved all 8
jobs that existed at the time (2 came back with no usable tags from either
source -- a genuine "nothing found", not a failure).

### What actually happened (vs. this plan)

Real API output surfaced three things worth recording, since they shaped
`book_metadata.py` more than "a small keyword check" above suggests:

- **Open Library's `search.json` omits `subject` unless asked for.** A bare
  `?title=...&author=...` query came back with no `subject` key at all
  (confirmed against *Pride and Prejudice*); fixed by adding
  `fields=subject` to the request.
- **Category can't be "any tag containing a marker wins".** Real subject
  lists mix genre, topic and character tags freely -- *The Shining* and
  *Pride and Prejudice* (both fiction) each carry a literal `"Fiction"` tag
  *alongside* an incidental `"Military history"`/`"History"` one, and a
  naive "does any tag mention history" check misclassified both as
  Non-fiction. `_map_category` is a two-pass check instead: an exact
  `"Fiction"`/`"Nonfiction"` tag wins outright when only one side has one;
  only when neither does does it fall back to counting marker-substring
  hits per side.
- **A source being unreachable is not the same as "nothing found".**
  Google Books' keyless access rate-limits (a real `429` was hit while
  testing). Treating that as "no results" would cache a negative result
  forever with no way to ever retry that book. `lookup()` instead raises
  `LookupUnavailable` only when *every* source failed at the request
  level; `_resolve_book_metadata` in `main.py` catches it and simply
  leaves the book unresolved rather than caching anything, so the next
  upload of the same book tries fresh.

## Part 2: Sort and filter (Android)

### Data model

`BookEntity` gains three nullable/default-empty columns via a Room
migration (`MIGRATION_3_4`, following the exact `engine` column's addition
last time: `ALTER TABLE books ADD COLUMN ... `), written the same way
`engine` already is, via `BookDao.updateJobState`/`applyJobState` picking
up the new manifest fields from the poll response:

- `category: String?`
- `genres: List<String>` -- library scale here is dozens of books, not
  thousands, so this is a small stored list, filtered **in memory** in the
  ViewModel over the already-loaded `books` list. Not a normalized
  many-to-many table -- that's real complexity a personal library of this
  size doesn't need. Stored as JSON (`Converters.genresToString`/
  `stringToGenres`, via kotlinx.serialization), not comma-joined as
  originally planned here -- a genre tag's source is a server-side keyword
  list with no commas in it today, but nothing enforces that stays true,
  and a stray comma silently corrupting a stored list was a cheap thing to
  rule out up front.

### Sort options

| Label | Field | Notes |
|---|---|---|
| Title (A-Z) | `title` | |
| Author (A-Z) | `author` | Null authors sort last, not first |
| Recently added | `addedAt` DESC | Today's only order, made explicit |
| Recently opened | `lastOpenedAt` DESC | Never-opened books sort last |

### Filter options

- **Category**: single-select chips -- All / Fiction / Non-fiction.
- **Genre**: multi-select chips, populated from the distinct genre values
  actually present across the current library (not a fixed master list --
  no point offering "Poetry" as a filter if nothing in the library has
  that tag). Selecting several genres is OR; combined with Category via
  AND.

Applied as a pure function over the loaded `books` list in the ViewModel
(easily unit-testable on its own, no server or database involved in the
filtering logic itself).

### Where the controls live, and persistence

A new icon in the Library `TopAppBar` (next to the existing Refresh and
Settings icons) opens a bottom sheet with two sections: Sort (single-select
list) and Filter (Category chips + Genre chips). The chosen sort+filter is
a **local, per-device UI preference**, not synced to the server -- stored
in the existing DataStore-backed `SettingsStore`, the same mechanism
`ReaderSettings` already uses for font size/theme, so it persists across
app restarts without inventing a new persistence layer.

## Testing

**Server**: pure-function tests for the category/genre keyword mapping
(fixture category/subject strings from both APIs, asserting the resulting
category and genre list), tests for the cache store mirroring
`JobStoreTest`/`UserStoreTest`'s conventions (atomic write, normalized-key
lookup, no duplicate external calls on a cache hit), and orchestration
tests with both external HTTP calls mocked (`unittest.mock.patch` on the
`urllib.request` calls, same spirit as `test_tasks.py` stubbing
`_load_audiblez`) covering: Open Library hit, Open Library miss -> Google
Books hit, both miss -> Unknown/empty, and a cached second lookup making
zero network calls.

**Android**: unit tests for the sort comparators and the filter function
as pure logic, matching the existing style of `ChunkIndexTest.kt` -- no
server, no database, no Compose involved.

## Suggested build order

1. ~~Server: `book_metadata.py` + the cache store + category/genre mapping,
   fully unit tested, no wiring into `main.py` yet.~~ Done --
   `server/app/book_metadata.py`, `book_metadata_store.py`.
2. ~~Server: wire into `create_job` ... confirm live against the real Open
   Library/Google Books APIs~~ Done -- see "What actually happened" above
   for what that live testing found.
3. ~~Android: `title`/`author` sent on upload; `category`/`genres` columns +
   migration; poll response picks them up.~~ Done -- `UploadWorker`,
   `MIGRATION_3_4`, `BookDao.updateJobState`/`BookRepository.applyJobState`.
4. ~~Android: sort comparators + filter function, unit tested.~~ Done --
   `domain/LibrarySort.kt`, `domain/LibraryFilter.kt`, plus
   `LibrarySortTest.kt`/`LibraryFilterTest.kt`.
5. ~~Android: the sort/filter UI control and its DataStore persistence.~~
   Done -- `ui/library/LibrarySortFilterSheet.kt`, opened from a new
   TopAppBar icon on the Library screen; persisted via
   `SettingsStore.LibraryViewSettings`.
