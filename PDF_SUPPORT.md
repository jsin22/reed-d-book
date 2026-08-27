# PDF support

Not started. Scoped 2026-08-27 as a future feature -- the app currently
rejects anything that isn't a `.epub` at every layer (picker, importer,
upload validation, and the conversion engine itself), and PDF text is
structurally different enough from EPUB that this needs a real design
before touching code.

## Context

A user asked whether the app works with PDFs. It doesn't, anywhere in the
pipeline:

- **App**: the file picker only offers `application/epub+zip`, and
  `EpubImporter` throws `"X is not an .epub"` for anything else.
- **Server**: `POST /api/jobs` rejects a filename not ending in `.epub`
  (400), then separately verifies the uploaded bytes are a real zip archive.
- **audiblez**: parses the book with `ebooklib.epub.read_epub()`, which only
  understands EPUB's zip/XHTML structure -- it has no PDF code path at all.

## Why PDF isn't a small addition

EPUB and PDF are fundamentally different kinds of document, and this
project's pipeline -- both the server's synthesis side and the app's
reading side -- is built entirely around EPUB's assumptions:

- **Reflowable vs. fixed layout.** EPUB content is semantic HTML/CSS that
  Readium reflows to fit the screen; the app's whole reading experience
  (page count by text size, tap-to-play, chunk highlighting -- see BUG-17's
  history in BUGS.md) depends on that. Most PDFs are fixed-layout: text has
  absolute page coordinates, not semantic structure. Readium does have a
  PDF navigator, but it is a genuinely separate code path from the EPUB
  navigator this app is built on, not a drop-in swap.
- **No reliable chapter structure.** EPUB has a spine and table of contents
  audiblez already reads directly. Most PDFs have neither -- some carry an
  outline/bookmark tree, many don't, and even when one exists it doesn't
  always line up with a book's real chapter breaks.
- **Extraction quality varies enormously.** A single-column novel-style PDF
  extracts to clean, orderly text reasonably well. A textbook or magazine
  layout (multi-column, running headers/footers, footnotes, page numbers
  interleaved mid-flow) does not -- naive per-page text extraction
  interleaves all of that into the body text. A scanned, image-only PDF has
  no text layer at all and needs OCR, a much heavier and slower step.

## Recommended approach: convert PDF to a synthetic EPUB, once, server-side

Rather than teaching the server's TTS pipeline and the app's reading UI to
both understand PDF natively (two large, separate integrations), do the
conversion once, early, and let literally everything downstream -- audiblez,
the job/poll/download flow, the Android reader -- keep working exactly as it
does today, because by the time it reaches any of that code it's just
another `.epub`.

1. **New preprocessing step, before the existing `convert_epub` task** (or
   as its own first phase): given a `.pdf` upload, extract text page by
   page, group it into chapters, and write a real EPUB using `ebooklib`
   (already a dependency, just used for writing instead of only reading).
2. **Chapter detection, best effort first, falling back gracefully:**
   - Prefer the PDF's own outline/bookmarks when present (most reliable).
   - Fall back to a font-size/style heuristic (large or bold lines as
     probable headings) when there's no outline.
   - Worst case, treat the whole PDF as one chapter -- works, but page
     count and per-chapter progress both degrade, and it becomes one very
     long synthesis job with no useful progress boundaries.
3. **Text extraction library**: evaluate PyMuPDF (`fitz`) and
   `pdfplumber`/`pdfminer.six` against real sample PDFs before picking one
   -- not installed today, so this is a new dependency either way. PyMuPDF
   is the likely starting point (fast, good reading-order extraction) but
   needs testing against the specific kinds of PDFs this app will actually
   see, not just novels.
4. **Reject what can't be handled honestly rather than guessing.** A
   scanned/image-only PDF (no extractable text layer) should fail with a
   clear "this PDF has no extractable text" error, not silently produce
   near-empty audio or hang. OCR (e.g. Tesseract) is explicitly out of
   scope for a first pass -- it's a much heavier, slower addition on top of
   an already CPU-constrained handheld server.
5. **The server always ends up with a real `.epub` on disk**, whether that
   was the original upload or the synthetically-generated one -- so
   `GET /api/jobs/{id}/epub` and everything that depends on it (a second
   device adopting the job, the app's own EPUB-based reader) keeps working
   unchanged.

## What has to be honest with the user

The reading experience after a PDF conversion will not look like the
original PDF. It's re-flowed, extracted text rendered like any other EPUB
-- no original page images, no original layout, no page-for-page match to
the source document. Worth saying so explicitly in the UI (e.g. on the
import sheet, when a `.pdf` is picked) rather than letting someone expect a
faithful reproduction of a fixed-layout PDF.

## Open questions to settle before building

- **Extraction library choice** -- needs real testing against representative
  PDFs (a novel, a scanned book, a multi-column textbook) before committing,
  not a decision made from documentation alone.
- **Chapter-heuristic quality** on PDFs with no outline -- how bad does the
  font-size fallback get on an unusual layout, and is "one giant chapter"
  an acceptable worst case to ship, or does it need to block PDFs without a
  usable heuristic instead?
- **Where extraction runs**: inside the existing Celery worker (same
  process that already avoids importing heavy TTS libraries at module
  level, matching that discipline) or a separate step/queue, given it's a
  different kind of work (CPU-bound text/layout parsing, not TTS) with its
  own failure modes.
- **Size/time limits.** A large scanned textbook could be slow to extract
  from and might need its own upload-size or page-count ceiling, separate
  from `REEDD_MAX_UPLOAD_BYTES`'s existing epub-sized assumption.
- **Should the synthetic EPUB be downloadable/inspectable** on its own (for
  debugging bad extractions), the way `worker.log` already exists for
  debugging a failed conversion?

## Suggested sequencing, whenever this gets picked up

1. Spike: try PyMuPDF and pdfplumber against 3-5 real PDFs spanning the
   quality range (clean novel, scanned book, multi-column textbook) and
   look at the raw extracted text before writing any pipeline code.
2. Server: the PDF-to-synthetic-EPUB step in isolation, testable with the
   same `unittest` conventions as the rest of `server/tests/`, no app
   changes yet.
3. Server: wire it into `POST /api/jobs` (accept `.pdf`, dispatch to the
   new step before the existing conversion task) and validation
   (`looks_like_pdf`, a `%PDF-` magic-bytes check mirroring
   `looks_like_epub`).
4. App: the file picker's mime-type filter, `EpubImporter`'s extension
   check, and the import sheet's messaging about the re-flowed reading
   experience.
5. Decide on and handle the scanned/no-text-layer rejection path end to end
   (clear server error -> clear app-side message, not a silent failure).
