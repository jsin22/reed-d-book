# read-d-book — v1.0 features

What the app actually does, as of this milestone. This is the *what*; the
*how* and *why* behind each piece live in [`android/README.md`](android/README.md)
and [`server/README.md`](server/README.md) (design rationale, tradeoffs,
"how it hangs together"), and in the feature-specific docs linked below.
`en.md` and `BUGS.md` are the working history — decisions and defects as they
happened — not meant to be read start to end. This doc is the snapshot: a
new contributor, or the user six months from now, should be able to read
this alone and know what the app can do today.

read-d-book turns an EPUB into a narrated, page-synced audiobook: pick a
book on your phone, a small local server converts it to an `.m4b` with a
neural TTS voice, and the app plays it back with the current sentence
highlighted on the page as it's read — tap ahead, drag-select a passage,
look up a word, or leave yourself a note, all without a network connection
once the book is on the device.

## Library

- **Import** an `.epub` through the system file picker; the app copies it
  into its own storage (not read live from the picker's `content://` URI,
  which doesn't reliably survive past the picker closing) and extracts its
  cover.
- **Convert**: pick a voice and speed, upload over wifi to the conversion
  server, and the app polls for progress — in the foreground every 4s while
  a screen is open, and via `WorkManager` every 15 minutes in the
  background, so a book left converting overnight is picked up on the next
  launch even if the app was fully closed.
- Every book shows a derived status badge: on device, uploading, queued,
  converting *n*% with an ETA, downloading, ready, failed, or lost (the
  server no longer has a record of the job). The badge becomes
  Playing/Paused whenever that book is the one loaded in the player, and a
  mini-player bar (play/pause, tap to reopen) shows at the bottom of the
  library whenever anything is playing.
- **Sync from the server**: `GET /api/jobs` is also the library listing —
  any job the server has, including ones converted from a *different*
  device, is adopted into the library on launch. A book converted once
  stays converted for every device pointed at that server; nothing
  re-uploads or waits through TTS twice.
- **Sort**: title, author, recently added, recently opened.
- **Filter**: by category (Fiction/Non-fiction) and by genre — multi-select
  chips populated from whatever genres actually appear in the current
  library, not a fixed master list. Category and genre are looked up
  automatically at conversion time (see below) and are a per-device UI
  preference, not synced to the server. See
  [`SORT_GROUP_LIBRARY.md`](SORT_GROUP_LIBRARY.md).
- Downloads (`.m4b`, the sync file) resume from where they left off if
  interrupted, verified byte-identical to a non-interrupted download.

## Conversion (server)

- FastAPI accepts the upload and returns a job id immediately; a Celery
  worker does the actual synthesis, so the app never holds an HTTP request
  open for the minutes-to-hours a real novel takes.
- **Pocket TTS**, the only engine left behind
  `audiblez.engines.TTSEngine`'s per-job interface. Chosen after live
  evaluation against Kokoro-82M, Supertonic 3, and a fourth, Chatterbox
  (rejected for being 2–25× slower); Kokoro and Supertonic were later
  removed from the pipeline entirely once nothing ever selected them, so
  the interface now has one implementation instead of choosing among
  three.
- **Chapters synthesize in parallel** across several CPU worker processes
  (`REEDD_CONVERSION_WORKERS`, default ~half the logical CPUs, capped at
  6) rather than one at a time — the one form of parallelism that actually
  paid off on the reference hardware (an integrated GPU's ROCm path was
  evaluated and measured no faster).
- Progress (`%`, ETA, chapters done) updates live as the conversion runs,
  polled by the app.
- **Category and genre lookup**: on upload, the server asks Gemini for a
  Fiction/Non-fiction category plus genre tags from a fixed 27-word
  vocabulary (Science Fiction, Mystery, Fantasy, Biography, History,
  Philosophy, Self-Help, and so on — see `server/app/llm_metadata.py`),
  keeping only tags the model is confident about. Runs in the background
  right after upload, cached forever per title+author so the same book is
  never looked up twice across any user. An admin health check
  (`GET /api/admin/metadata-health`) reports whether the most recent
  lookup actually succeeded, since a bad API key or a deprecated model
  name would otherwise just look like books silently never getting
  tagged. See [`LLM_GENRE_ENRICHMENT.md`](LLM_GENRE_ENRICHMENT.md).
- Downloads (`.m4b`, sync `.json`, and the original `.epub`) all support
  HTTP `Range`, so an interrupted transfer resumes instead of restarting.
- A per-job log (audiblez' own output, ffmpeg included) is available from
  the book's detail screen for diagnosing a failed or stuck conversion.

## Reader

- EPUB rendering via Readium's `EpubNavigatorFragment`, hosted inside the
  app's Compose UI.
- Paginated or continuous-scroll reading, adjustable text size, a table of
  contents, and reading position saved automatically per book.
- Appearance controls including a **Paper** theme — a warm e-ink-style
  grey page with near-black text, which also switches off the publisher's
  own CSS for that theme only (otherwise a book's own `background: #fff`
  would punch a white hole through the grey page).
- A full-screen mode that hides both the app's own toolbars and the
  system bars, so the page reaches the physical edge of the screen.
- A muted position indicator (`24 / 312`) at the bottom of the page —
  Readium's stable position slices, not a page count in the print sense,
  since a reflowable EPUB has no fixed page count independent of font size
  and screen.

## Read-along playback

- The currently-spoken sentence is highlighted on the page as the audio
  plays, driven by a sentence-level timing file the server generates
  alongside the audio.
- A transport bar: play/pause, previous/next **sentence** (not a fixed
  time skip), speed control, and a follow toggle.
- **Follow mode**: the page turns itself to keep the spoken sentence in
  view. Dragging the page manually disengages following (with an explicit
  crosshair control to jump back to where the audio is) rather than
  fighting the reader for control; an intentional seek — tapping a
  sentence, scrubbing — re-engages it, since that's a request to be taken
  there.
- **Tap a sentence to play it**: resolves to the exact sentence via
  Readium's own decoration/activation mechanism, not guessed from
  coordinates.
- **Read from here**, from the word-tap menu (see below): plays from the
  start of the sentence containing the tapped word or selection.
- A background playback service (Media3 `MediaSessionService`) keeps
  audio playing when the reader isn't open, with lockscreen and
  notification controls and headset-button support for free.
- A small "earlier/later" timing nudge (25ms steps, default 0) exists for
  correcting AAC container priming offset if a particular conversion's
  highlight drifts from the audio.

## Word interaction: tap, select, define, note

- **Tap a word** to highlight it and open a small menu at the bottom of
  the screen (replacing the transport bar for as long as the menu is
  open, so the menu never has to float near — and potentially cover — the
  word or its highlight).
- **Drag either handle to extend the selection** into a multi-word
  passage — green for the start, red for the end. Dragging a handle past
  the other is a no-op (the selection just doesn't invert), and the
  selection can span multiple lines.
- The menu offers, depending on what's selected: **Read from here**
  (hidden if the passage has no audio mapped to it), **Definition**
  (hidden for a multi-word selection), **Notes**, and **Copy**.

## Notes

- From the word menu's **Notes** option: type a note about the tapped
  word or selected passage; it's saved together with the quoted text and
  its location in the book.
- A **Notes** list, reachable from the reader's toolbar, shows every note
  in reading order. Each entry expands to show the full note text, and
  has a button to jump straight back to that spot in the book — which
  also re-highlights the original passage in yellow (no handles, no
  menu — just showing you what was noted).

## Offline dictionary

- Word and passage definitions work with **no network connection**: a
  filtered extract of Wiktionary ships inside the APK, zstd-compressed
  (~20MB, roughly 90ms to decompress once on first use) rather than
  relying on the APK's own weaker general-purpose compression.
- Covers real general-vocabulary words — including function words like
  "the" that a purely lexical-semantic dictionary (the app's original
  choice, WordNet, was replaced for exactly this reason) doesn't define
  at all.
- Each sense shows its part of speech, a definition, a few synonyms where
  Wiktionary has them, and — where available — an IPA pronunciation next
  to the headword.
- Regular and irregular inflections ("walked," "went," "children")
  resolve back to their base word's entry automatically.

## Sharing / multi-user

- **Invite-only access**: the server has no anonymous or LAN-trust mode.
  The first admin bootstraps themselves from the command line; from then
  on, an admin invites people by email from the app's Admin screen, and
  an invitee just pastes the token they're emailed into Settings — no
  server address to type in, since the app already knows it.
- Reachable from outside the home network via Tailscale Funnel, with no
  port-forwarding or self-signed certificate to install.
- Each user sees books they've uploaded themselves, plus any job an admin
  has explicitly marked public. An admin can revoke a user's access at
  any time.
- The Admin screen also surfaces the metadata-lookup health check
  described above, and a voice-sample browser for auditioning voices
  before converting a book.

## Settings

- Server address and access token, with a **Test connection** button that
  hits the one endpoint that never requires a token — so a bad address is
  distinguishable from a bad token.
- Appearance (text size, theme, margins) and storage usage, including a
  toggle for whether a finished job is deleted from the server once
  downloaded (freeing server disk) or left in place.

## Known limitations

- **A sentence spanning two pages**: the page doesn't turn until the
  sentence finishes and the next one begins, since there's no per-word
  timing to know precisely when the words on screen are done being read —
  only per-sentence. See `BUGS.md` BUG-15.
- **A worker killed mid-conversion** leaves that job showing as
  "converting" forever on the server side; cancel and re-upload. See
  `server/README.md`'s "Known gaps".
- **The first line of a book never highlights** — audiblez injects a
  `"<title> – <author>."` line into chapter 1's audio that appears
  nowhere in the actual EPUB text, so it has nothing to match against. By
  design, not a bug.
- **Conversion is slow on CPU** (roughly 5× realtime before the parallel-
  chapter-synthesis work landed) — a 10-hour audiobook is a some-tens-of-
  minutes-to-an-hour-plus wait even now, not an instant turnaround.
- **Release builds are unminified** — R8 rules for Readium's reflection-
  heavy resource loading haven't been written, and nothing here ships
  through the Play Store.
- Two feature designs are scoped but **not started**: PDF support
  ([`PDF_SUPPORT.md`](PDF_SUPPORT.md)) and chapter-by-chapter progressive
  playback, converting and listening to one chapter while the rest is
  still processing ([`PROGRESSIVE_PLAYBACK.md`](PROGRESSIVE_PLAYBACK.md)).

## Where to look next

- [`android/README.md`](android/README.md) — the app's architecture:
  how the database-is-the-only-source-of-truth pattern works, how
  read-along timing and page-following are implemented, storage layout,
  test suite.
- [`server/README.md`](server/README.md) — the server's architecture:
  the job lifecycle, the three-TTS-engine abstraction, parallel chapter
  synthesis, the sharing/invite system, configuration reference.
- `en.md` — the enhancement log, in the order features actually landed,
  with the reasoning behind each one.
- `BUGS.md` — defects found and fixed (or knowingly not), with root
  causes — useful when something looks similar to a problem solved before.
