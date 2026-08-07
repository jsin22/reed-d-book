# How the backend works

Notes on the Phase 1 conversion server in [`server/`](server/README.md). The
client half is at the end, under [How the app works](#how-the-app-works).

## The problem it solves

Converting a book takes minutes to hours. An HTTP request can't stay open that
long — phones sleep, wifi drops, and the app has to survive being closed. So the
backend never converts *during* a request. It accepts the file, hands back an
id, and does the work elsewhere.

## Three processes

| process | job | needs |
|---|---|---|
| **uvicorn** (`app.main`) | speaks HTTP: takes uploads, answers polls, serves files | fast, always responsive |
| **Redis/valkey** | the queue: a list of job ids waiting to be picked up | just a broker |
| **Celery worker** (`app.tasks`) | runs audiblez, one book at a time | torch, kokoro, ffmpeg |

They share two things: the **Redis URL** (how work is handed over) and the
**data directory** (where files and state live). Nothing else — the API and
worker never talk to each other directly.

## Lifecycle of one book

**1. Upload** — `create_job`, `server/app/main.py:70`

The app POSTs the `.epub`. The endpoint validates voice/speed/extension, mints a
UUID, creates `data/jobs/<uuid>/`, and streams the file to disk in 1 MB chunks
(a 200 MB book must not sit in RAM on a handheld). It writes a `job.json`
manifest with `status: "queued"`, pushes the id onto Redis via `enqueue`, and
returns `202` with the whole manifest.

Everything after that point is bookkeeping — no TTS has happened.

**2. Pickup** — `convert_epub`, `server/app/tasks.py:132`

The worker is blocked on Redis waiting for an id. It gets one, flips the
manifest to `running`, then calls `audiblez.core.main()` in-process — the same
function `audiblez book.epub -o out/` calls. audiblez does the real work: splits
chapters, runs Kokoro sentence by sentence, writes per-chapter `.wav`s,
concatenates them to `.m4b` via ffmpeg, and (thanks to the Phase 2 changes)
emits the sync `.json`.

**3. Progress** — `_Progress`, `server/app/tasks.py:45`

audiblez already accepts a `post_event` callback and fires `CORE_PROGRESS` after
every sentence with a percentage and ETA. The worker passes one in and writes
those numbers into `job.json` — but only when the whole-number percent actually
changes, otherwise a novel would mean tens of thousands of disk writes.
Meanwhile everything audiblez and ffmpeg print goes into that job's
`worker.log`.

**4. Poll** — `job_status`, `server/app/main.py:121`

The app polls `GET /api/jobs/{id}`. That handler does one thing: read `job.json`
off disk and return it. It doesn't ask Redis, doesn't ask Celery, doesn't touch
the worker. Which is why polling stays instant no matter what the worker is
doing.

**5. Finish**

The worker checks the two deliverables actually exist (if ffmpeg is missing,
audiblez returns *normally* having produced no `.m4b` — that's an error, not
success), deletes the per-chapter `.wav`s, and writes `status: "done"` with both
filenames and byte sizes. On a crash it writes `status: "error"` with the
traceback tail instead.

**6. Download** — `server/app/main.py:149`

`/audiobook` and `/sync` serve the files with `FileResponse`, which honours
`Range` — a dropped download resumes instead of restarting 500 MB. They return
`409` if the job isn't done and `410` if the files were cleaned up. Then
`DELETE` reclaims the disk.

## The one design decision that shapes everything

**`job.json` on disk is the source of truth — not Celery.**

The obvious approach is `AsyncResult(task_id).state`. It isn't used here because
Celery results expire after 24h and evaporate if Redis restarts, and an expired
id reports as `PENDING` — identical to an id that never existed. The app would
show "queued, please wait" forever for a book that finished last night. Since
Phase 3 requires resuming after the app is closed, state has to outlive both
Redis and the worker. Celery is used purely as a queue; it isn't asked to
remember anything.

The consequence: the manifest is written by the worker and read by the API
concurrently, so `JobStore.write` (`server/app/store.py`) writes a temp file and
`os.replace`s it — a poll can never catch a half-written file.

The second decision: **uvicorn never imports torch or kokoro.** It dispatches by
task *name* (`send_task('reedd.convert_epub', ...)`) rather than importing the
task function. That's why the API starts instantly, stays small in RAM, and why
the test suite runs the whole HTTP contract without the TTS stack installed.

## On disk

```
server/data/jobs/<uuid>/
├── job.json        status, progress, eta, error, filenames  ← what polling returns
├── Book_One.epub   the upload (name sanitised: audiblez derives every output path from it)
├── worker.log      audiblez + ffmpeg output for this job
└── out/
    ├── Book_One.m4b     ← GET .../audiobook
    └── Book_One.json    ← GET .../sync
```

The job id is a UUID and is validated as one before it's ever joined into a
path — `../../etc` returns 404 rather than reading the filesystem.

## Running it

```sh
podman run -d --name reedd-redis -p 6379:6379 docker.io/library/redis:7-alpine
cd server
../audiblez/.venv/bin/celery -A app.tasks worker --loglevel=info --concurrency=1
../audiblez/.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Endpoints, configuration and known gaps are in [`server/README.md`](server/README.md).

# How the app works

Notes on the Phase 3 Android app in [`android/`](android/README.md).

## It is the other half of the same decision

The backend keeps job state in `job.json` on disk instead of Celery's result
backend so that an id polled hours later still answers. The app is what that was
for. Its half of the bargain is the same idea one level up: **the phone's
database is the source of truth for the UI, and nothing the UI reads lives in
memory.**

Every screen observes Room. Four different things write to it — the upload, the
foreground poll, the background poll, the download workers — and none of them
tell the UI anything directly. Close the app mid-conversion, reopen it an hour
later, and the library renders from rows that were updated by a worker while it
was dead. There is no "restore my state" path because there was never any state
to lose.

## Watching a conversion, twice

The same `ConversionWatcher.pollOnce` runs in three situations:

| when | who calls it | how often |
|---|---|---|
| a screen is open | the ViewModel's loop | every 4 s |
| the app is closed | `PollWorker` (WorkManager) | every 15 min |
| the app just opened | `reconcile()` | once |

Fifteen minutes is the floor Android allows for periodic work. That is useless
for a progress bar somebody is watching and perfectly adequate for noticing that
a book finished — hence both. Because they share one function and one set of
rows, they cannot disagree; the fast one just makes the same writes more often.

When nothing is pending, the periodic worker **cancels itself**. A library where
every book is already converted should not wake the device four times an hour
forever. Uploading something registers it again.

## The three things that go wrong

**The server forgot the job.** Its data directory was cleared while the app was
closed, so a poll comes back 404. That is a state, not an error: the row is
flagged, polling stops, and the book offers a re-upload. Without it the app would
show "queued, please wait" for a job that will never run — the exact failure the
backend's design notes set out to avoid, reintroduced on the client.

**The download broke halfway.** An `.m4b` is hundreds of megabytes over wifi from
a machine in the next room. Bytes go into a `.part` file, the next attempt sends
`Range: bytes=<what we have>-`, and the file is only renamed once its size
matches the manifest. A phone that walks out of range costs seconds, not a
restart. The server already supported this — `FileResponse` honours `Range` — and
it was checked against the live server rather than assumed: a resumed transfer
stitched from two range requests is byte-identical to a single download.

**The files vanished but the database says otherwise.** Someone cleared the app's
storage. On launch, any book claiming to be downloaded is checked against the
filesystem, and a row whose files are gone is demoted and re-queued rather than
believed. Opening a reader onto a missing file is worse than downloading again.

## Timestamps become milliseconds once

The sync file stores float seconds because that is what falls out of dividing
frame counts by 24000. A player reports integer milliseconds. So the conversion
happens exactly once, when the file is parsed into `sync_chunks` at download
time, into a column the database indexes — not on every playback tick in Phase 4.

Rounding each boundary independently is what keeps audiblez' guarantee that one
chunk ends exactly where the next begins, so the highlight in Phase 4 can never
flicker through a one-millisecond gap between two sentences.

Screens, versions, storage layout and known gaps are in
[`android/README.md`](android/README.md).
