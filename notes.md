# How the backend works

Notes on the Phase 1 conversion server in [`server/`](server/README.md).

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
