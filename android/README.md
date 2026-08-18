# Android app (project_plan Phases 3 and 4)

The reader. Imports an `.epub`, sends it to the [conversion server](../server/README.md)
over wifi, waits out a conversion that takes minutes to hours, downloads the
`.m4b` and the timing `.json`, plays the audiobook, and highlights each sentence
as it is spoken.

```
pick .epub ──▶ copy to app storage ──▶ POST /api/jobs ──▶ job_id in Room
                                                              │
                     ┌────────────────────────────────────────┤
                     │ foreground: poll every 4s while a screen is open
                     │ background: WorkManager, every 15 min, app closed
                     └────────────────────────────────────────┤
                                                              ▼
                                              status == "done" ──▶ download
                                                                  .m4b (resumable)
                                                                  .json ──▶ sync_chunks
```

## Building

Needs a JDK (not just a JRE) and the Android SDK. On this machine both are
outside the system packages:

```sh
export JAVA_HOME=~/.jdks/jdk-21.0.12+8      # Fedora ships only java-*-headless
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

`local.properties` holds the SDK path and is gitignored; Android Studio writes
its own and uses its bundled JBR, so neither variable is needed there.

| | |
|---|---|
| compileSdk | **37** — forced by androidx: `core-ktx` 1.19 and `lifecycle` 2.11 refuse anything older |
| targetSdk | **36** — deliberately behind; it is what opts in to new runtime behaviour, and 37 offers this app nothing |
| minSdk | **26** — native `java.time`, adaptive icons, notification channels |
| AGP / Gradle | **9.3.1 / 9.7** — AGP 9 compiles Kotlin itself, so there is no `kotlin-android` plugin |
| Kotlin / KSP | **2.4.10 / 2.3.11** — KSP's versioning decoupled from Kotlin's at 2.3.0 |

## How it hangs together

**The database is the only thing the UI reads.** Every screen observes Room. The
foreground poll, the background worker, the upload and the download all *write*
there and nothing else. That is what makes the library look identical whether a
live poll is running, the app was just reopened, or a download finished while it
was closed — there is no in-memory state to lose and no second code path to
drift.

**One watcher, three callers.** [`ConversionWatcher`](app/src/main/java/dev/reedd/domain/ConversionWatcher.kt)
polls a job and writes down the answer. The ViewModel calls it every 4 seconds
while a screen is open, [`PollWorker`](app/src/main/java/dev/reedd/work/PollWorker.kt)
calls it every 15 minutes while the app is closed, and app start calls it once to
reconcile. 15 minutes is the platform floor for periodic work — coarse for a
progress bar, fine for noticing a finished book, which is why the foreground path
exists at all.

**The poller cancels itself.** Once nothing is pending, `PollWorker` cancels its
own periodic registration rather than waking the device every quarter hour for a
library where everything is already converted. Uploading re-registers it.

**A 404 is a state, not an error.** If the server has forgotten a job — its data
directory was wiped while the app was closed — the row is marked `jobMissing` and
polling stops. Without that the book sits at "queued" forever waiting on a job
nobody will ever run. The same flag is set deliberately after a successful
download, when the app deletes the job to reclaim server disk, so the UI
distinguishes the two by asking whether the files are actually present
(`needsReupload` vs `isPlayable`).

**Downloads resume.** An `.m4b` is hundreds of megabytes over wifi from a
handheld. Bytes are appended to a `.part` file with `Range: bytes=N-` and only
renamed into place once the size matches the job manifest, so a truncated file
can never be mistaken for a finished one. Verified against the real server: a
206 carries `Content-Range: bytes 100000-582521/582522`, and a stitched-together
resume is byte-identical to a single download.

**HTTP errors are classified once.** An OkHttp interceptor turns every non-2xx
into an `ApiException` carrying FastAPI's `detail`, and it subclasses
`IOException` so callers have one failure path instead of two. The status codes
are load-bearing: 404 means the job is gone, 409 means it isn't finished, 410
means its files were cleaned up.

**Narrow SQL updates, not whole-row writes.** The poller, the reader and the
download worker all write the same row concurrently. `UPDATE books SET
jobStatus = ...` touches only its own columns; a read-modify-write of the whole
entity would let one of them clobber another's value with a stale copy.

**Milliseconds, not seconds.** The sync file stores float seconds; a player
reports integer milliseconds. The conversion happens once, at download time, into
an indexed `(bookId, startMs)` column — not on every playback tick. Rounding each
boundary independently preserves audiblez' guarantee that
`chunks[n].end == chunks[n+1].start`, so Phase 4 cannot see a gap between two
sentences.

**Cleartext HTTP is permitted at the base config.** The server is plain uvicorn
on a LAN address the user types in, so there is no domain list to enumerate. The
alternative — terminating TLS on a handheld with a self-signed certificate and
shipping a custom trust anchor — is strictly worse for a link that never leaves
the user's own network.

## How read-along works (Phase 4)

Highlighting a sentence needs an answer to two questions: *which* sentence is
being spoken, and *where on the page* it is. They are solved in completely
different places.

**Which sentence: a binary search over an in-memory index.** The player is polled
every 100 ms while playing (400 ms while paused, since a paused player still moves
when scrubbed). Each tick reads an in-memory field and binary-searches a
[`ChunkIndex`](app/src/main/java/dev/reedd/domain/ChunkIndex.kt) loaded once when
the book opens — a novel's mapping is a few megabytes, and a database query ten
times a second would be indefensible. A binary search rather than walking forward
from the last sentence, because a scrub can move the position anywhere in either
direction. A position exactly on a boundary belongs to the sentence that *starts*
there, since the sync file guarantees `chunks[n].end == chunks[n+1].start`.

**Where on the page: a text-quote anchor, computed once.** This is the part with
no obvious answer, so it is worth stating what Readium actually does. Its
JavaScript resolves a locator like this:

```js
if (text && text.highlight) {
  scope = locations.cssSelector ? document.querySelector(...) : document.body
  return new TextQuoteAnchor(scope, text.highlight, {prefix: text.before, suffix: text.after}).toRange()
}
```

So a sentence plus its surrounding context resolves to a DOM range — no CSS
selectors or character offsets needed. The
[aligner](app/src/main/java/dev/reedd/data/align/ChunkAligner.kt) therefore runs
once per book, at download time, and stores exactly that: an href, the sentence,
and ~40 characters either side.

Matching cannot be literal, because `SYNC.md` documents three ways audiblez'
sentence text differs from the epub's: a `.` is appended to anything not ending in
one, whitespace differs from however the author indented their XHTML, and only
`title/p/h1-h4/li` is extracted so the page contains text the audio skips. So
comparison happens on a normalised projection — whitespace collapsed, quotes and
dashes folded, case folded — that keeps an index back to the original, and the
**epub's own substring** is what gets stored. Storing audiblez' version would hand
Readium a string that is not on the page.

Sentences are matched with a **cursor advancing through the chapter**, not searched
for individually: they are in reading order, so one linear pass is enough, and a
per-sentence search would be quadratic on a novel and would happily match the
wrong occurrence of "He nodded."

**Nothing is required to align.** Chunk 0 is audiblez' injected
`"<title> – <author>."` and appears in no epub, by design. Any other unmatched
sentence keeps its timings and simply does not highlight — the audio still plays —
and the match rate is recorded per book and shown on the detail screen, so a badly
aligned book says so rather than just behaving oddly.

**Playback is a service, not a screen.** [`PlaybackService`](app/src/main/java/dev/reedd/playback/PlaybackService.kt)
is a Media3 `MediaSessionService` holding the one ExoPlayer, which is what makes
background playback work and what supplies the lockscreen and notification
controls and the headset buttons for free. Leaving the reader does not stop the
audio, and [`PlayerConnection`](app/src/main/java/dev/reedd/playback/PlayerConnection.kt)
-- the app's one `MediaController` handle on it -- does not go away either: it is
a single instance shared by every screen (`AppContainer.playerConnection`), not
rebuilt per reader. It used to be rebuilt per reader, which was a real bug: a
fresh instance's notion of "what is playing" always started blank, so its guard
against reloading the book already open never fired, and the outgoing book's
`playWhenReady` flag (a player-level flag, not a property of the media item)
carried over and auto-started whatever opened next. One connection is what makes
"which book is playing" a single fact the whole app -- including the library's
now-playing bar -- can agree on, and `prepare()` now checks the controller's
actual current item rather than a copy of its own.

**Following is a state machine, because auto-advance fights the reader.** Dragging
the page stops the audio dragging it back, with an explicit way to jump back to it;
an intentional seek (tapping a sentence, scrubbing) resumes following, because that
is a request to be taken there. The rules live in
[`FollowController`](app/src/main/java/dev/reedd/domain/FollowController.kt) with no
Android dependency, so they are actually tested.

**Tapping a sentence plays it.** A second group of transparent but *activable*
decorations covers the sentences in the resource on screen; Readium reports which
one was activated, and the sentence index is encoded in its id. So the tap resolves
to an exact sentence rather than being guessed from coordinates or reverse-matched
from text.

**The timing offset is adjustable and defaults to 0.** `SYNC.md` warns the `.m4b`
carries ~40–90 ms of AAC priming the timestamps do not describe, and advises
correcting in the player rather than the file. The reader has an "earlier/later"
nudge in 25 ms steps. It is left at 0 because ExoPlayer honours ffmpeg's gapless
metadata, so it may well be unnecessary — worth measuring before compensating. The
offset shifts the *lookup* only; it is subtracted back out when seeking, or tapping
a sentence would drift by it every time.

## Screens

| | |
|---|---|
| **Library** | every book with a derived status badge: on device, uploading, queued, converting *n*% + ETA, downloading, ready, failed, lost; a book's badge becomes "Playing"/"Paused" while it is the one loaded in the player, and a mini-player bar at the bottom (play/pause, tap to reopen) is shown whenever anything is |
| **Import** | SAF picker, then voice (from `GET /api/voices`) and speed, validated against the server's own 0.5–2.0 range |
| **Book detail** | progress, the server's error text, the audiblez log via `GET /api/jobs/{id}/log`, and cancel / resend / resume-download / delete |
| **Reader** | Readium's `EpubNavigatorFragment` hosted in Compose; paginated or continuous scroll, text size, theme, table of contents, position saved to Room. For a converted book: a transport bar with play/pause, previous/next **sentence**, speed, a follow-the-audio toggle and the highlight-timing nudge |
| **Settings** | server address and token with a `Test connection` that hits `/api/health` (the one endpoint that never needs the token, so a bad address is distinguishable from a bad token), plus storage use |

## Storage

```
filesDir/
├── books/<bookId>/book.epub        the imported copy
├── books/<bookId>/cover.jpg        extracted at import
└── audiobooks/<bookId>/
    ├── <name>.m4b                  downloaded
    ├── <name>.json                 downloaded, then parsed into sync_chunks
    └── <name>.m4b.part             in flight; renamed when verified
```

The epub is copied out of the picker rather than read through its `content://`
URI: a SAF grant does not reliably outlive the picker, and a background worker
may need the file hours later. Filenames come from the job manifest, because
audiblez derives every output path from the sanitised upload name and the sync
file's `audio_file` field refers to the `.m4b` by that name.

## Tests

```sh
./gradlew testDebugUnitTest      # 146 tests, no device, no network, no server
```

Robolectric and MockWebServer throughout. The interesting ones:

- **`ChunkAlignerTest`** — run against the real `sample-short.epub` and the real
  sync file audiblez produced for it. Asserts that every sentence except the
  injected title line is located, that a heading whose chunk has an invented period
  still matches, that a repeated sentence resolves to *successive* occurrences
  rather than the first one twice, and — the property the whole approach rests on —
  that every stored highlight is a literal substring of the page text.
- **`ChunkIndexTest`** — boundaries, backwards seeks, before the first sentence,
  past the last, and that the timing offset shifts the lookup but not a seek.
- **`FollowControllerTest`** — that the same sentence never navigates twice, that
  dragging disengages, and that resuming moves the page even to the sentence it
  last targeted.
- **`MigrationTest`** — builds a version 1 database from Room's *own* exported
  `1.json` so the test cannot drift from the real old schema, then opens it with
  Room, which validates the migration produced exactly version 2.

- **`ResumableDownloaderTest`** — resume with a `Range` header; a server that
  *ignores* `Range` and answers 200 (restart, do not corrupt); a stale `.part`
  longer than the resource (416, then start over); a transfer cut off mid-body
  (keep the `.part`, finish it on the next attempt); a size that disagrees with
  the manifest.
- **`ConversionWatcherTest`** — a 404 stopping the polling; a finished job
  enqueueing its download; one book's failure not abandoning the others; a
  conversion that completed while the app was closed; a row whose files vanished
  from disk being repaired instead of trusted.
- **`SyncFileParserTest`** — parsed against the real file this repo's audiblez
  fork produced for `sample-short.epub`, asserting contiguity survives the
  millisecond conversion.
- **`BookDaoTest`** — that a poll cannot clobber the reading position or the
  download counters.

The JSON fixtures in `app/src/test/resources/fixtures/` are **generated by
`server/app/store.py` itself**, not hand-written, so a change to the manifest
shape fails a test here rather than silently reading null on device.

## Known gaps

- **Not yet run on a device or emulator.** The emulator will not start on this
  machine: the SDK's bundled `qemu-system-x86_64-headless` segfaults about 20
  seconds into boot, identically under `-gpu host`, `swiftshader_indirect`,
  `guest` and `off`, and with a pristine default AVD — so it is the host QEMU
  against this kernel, not the configuration. Everything here compiles, passes
  lint with no errors, and its logic is unit-tested, but the parts only a real
  Android runtime can exercise are unverified. **This matters more for Phase 4 than
  it did for Phase 3**, because read-along is mostly runtime behaviour:
  - that Readium's JavaScript resolves these text-quote anchors to the ranges the
    aligner intends (the alignment itself is tested; the *rendering* of it is not);
  - that a transparent activable decoration is still tappable, which is what
    tap-a-sentence-to-play depends on;
  - whether `go()` per sentence is smooth or visibly jumpy, in either scroll or
    paginated mode;
  - whether the AAC priming offset is actually needed;
  - background playback, the media session, and `WorkManager` under real Doze.
- **The reader's fragment factory is installed on the activity's
  FragmentManager.** It works because `AndroidFragment` instantiates through that
  factory, but it is a shared mutable global; a second fragment-hosting screen
  would need this reworked.
- **A worker killed mid-job still leaves the server's manifest at `running`
  forever** — a server-side gap noted in its own README. The app shows it as
  converting indefinitely; cancel and resend.
- **Schema version 2, with a real migration and still no destructive fallback**, so
  a future bump crashes naming the missing migration rather than silently wiping a
  library of converted audiobooks. `app/schemas/` is checked in to diff against.
- **The tap layer is applied per resource, not per page.** A chapter's worth of
  decorations in one batch is fine; a very long single-resource book would be
  wasteful. Narrowing it to the visible range needs on-device measurement first.
- **Release builds are unminified.** R8 rules for Readium's reflective resource
  loading are their own piece of work and nothing here ships through Play.
