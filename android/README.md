# Android app (project_plan Phase 3)

The reader. Imports an `.epub`, sends it to the [conversion server](../server/README.md)
over wifi, waits out a conversion that takes minutes to hours, downloads the
`.m4b` and the timing `.json`, and displays the book.

Phase 3 is the plumbing and the UI. Playback and text highlighting are Phase 4;
the pieces they need are already in place (the timings are parsed into the
database at download time, and the reader is rendered by a navigator that
supports highlight decorations).

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

## Screens

| | |
|---|---|
| **Library** | every book with a derived status badge: on device, uploading, queued, converting *n*% + ETA, downloading, ready, failed, lost |
| **Import** | SAF picker, then voice (from `GET /api/voices`) and speed, validated against the server's own 0.5–2.0 range |
| **Book detail** | progress, the server's error text, the audiblez log via `GET /api/jobs/{id}/log`, and cancel / resend / resume-download / delete |
| **Reader** | Readium's `EpubNavigatorFragment` hosted in Compose; paginated or continuous scroll, text size, theme, table of contents, position saved to Room |
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
./gradlew testDebugUnitTest      # 100 tests, no device, no network, no server
```

Robolectric and MockWebServer throughout. The interesting ones:

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
  Android runtime can exercise are unverified: Readium rendering inside Compose,
  the foreground-service notifications, and `WorkManager` under real Doze.
- **The reader's fragment factory is installed on the activity's
  FragmentManager.** It works because `AndroidFragment` instantiates through that
  factory, but it is a shared mutable global; a second fragment-hosting screen
  would need this reworked.
- **A worker killed mid-job still leaves the server's manifest at `running`
  forever** — a server-side gap noted in its own README. The app shows it as
  converting indefinitely; cancel and resend.
- **No migrations.** Schema version 1 with no destructive fallback, so a future
  bump will crash rather than silently wipe a library. `app/schemas/` is checked
  in to diff against.
- **Release builds are unminified.** R8 rules for Readium's reflective resource
  loading are their own piece of work and nothing here ships through Play.
