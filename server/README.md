# Conversion server (project_plan Phase 1)

The backend that runs on the GPD Pocket 4. The Android app uploads an `.epub`
over wifi and gets back an `.m4b` plus the read-along timing `.json` described
in [`audiblez/SYNC.md`](../audiblez/SYNC.md).

Conversion takes minutes to hours, so the upload endpoint returns a job id
immediately and the app polls it. FastAPI serves the HTTP side, Celery/Redis is
the queue, and a worker runs audiblez.

```
Android app ──POST /api/jobs──▶ FastAPI ──▶ Redis ──▶ Celery worker ──▶ audiblez
            ◀──── job_id ─────           queue                          │
            ──GET  /api/jobs/{id}──▶ job.json on disk ◀──progress───────┘
            ──GET  .../audiblez, .../sync──▶ the finished files
```

## Endpoints

| | |
|---|---|
| `POST /api/jobs` | multipart: `file` (the .epub), optional `voice`, `speed`. `202` with the job. |
| `GET /api/jobs/{id}` | poll: `status`, `progress` (0-100), `eta`, `chapters_done`, `error`. |
| `GET /api/jobs/{id}/audiobook` | the `.m4b`. Supports `Range`, so an interrupted download resumes. |
| `GET /api/jobs/{id}/sync` | the timing `.json`. |
| `GET /api/jobs/{id}/log` | audiblez' output for this job, ffmpeg included. |
| `DELETE /api/jobs/{id}` | cancels if running, then deletes the job's files. |
| `GET /api/jobs` | every job, newest first. |
| `GET /api/voices` | the Kokoro voices audiblez accepts. |
| `GET /api/health` | liveness; never requires the token, so the app can find the server. |

`status` is one of `queued`, `running`, `done`, `error`. Interactive docs are at
`/docs`.

```console
$ curl -F file=@book.epub -F voice=af_heart http://pocket4.local:8000/api/jobs
{"job_id":"bc2989f1-...","status":"queued", ...}

$ curl http://pocket4.local:8000/api/jobs/bc2989f1-...
{"status":"running","progress":32,"eta":"00d 00h 00m 11s","chapters_done":0, ...}

$ curl -OJ http://pocket4.local:8000/api/jobs/bc2989f1-.../audiobook
$ curl -OJ http://pocket4.local:8000/api/jobs/bc2989f1-.../sync
```

## Setup

Python and git, then Redis:

```sh
sudo dnf install valkey && sudo systemctl enable --now valkey   # Fedora
sudo apt install redis-server                                   # Debian/Ubuntu
podman run -d -p 6379:6379 docker.io/library/redis:7-alpine     # anywhere, incl. Windows via WSL
```

Fedora has no `redis` package — it was dropped over the RSAL/SSPL relicensing
and replaced by **valkey**, the Linux Foundation's fork of Redis 7.2. It listens
on 6379 and speaks the same protocol, so nothing here changes; keep using the
`redis://` URLs.

Install the server into the **same** virtualenv as audiblez — the worker
imports `audiblez.core` directly, and audiblez pins python `>=3.10,<3.13`:

```sh
python3.10 -m venv audiblez/.venv
audiblez/.venv/bin/pip install torch --index-url https://download.pytorch.org/whl/cpu
audiblez/.venv/bin/pip install -e audiblez -r server/requirements.txt
audiblez/.venv/bin/python -m spacy download xx_ent_wiki_sm
```

Run the two processes from the `server/` directory:

```sh
../audiblez/.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
../audiblez/.venv/bin/celery -A app.tasks worker --loglevel=info --concurrency=1
```

`--host 0.0.0.0` is what makes it reachable from the phone; open the port in the
Pocket 4's firewall (`sudo firewall-cmd --add-port=8000/tcp`) and point the app
at the machine's LAN address. Keep `--concurrency=1`: TTS already saturates the
machine, and a second job would only make both slower.

## Configuration

Everything has a working default; override with environment variables.

| variable | default | |
|---|---|---|
| `REEDD_DATA_DIR` | `server/data` | where uploads and output live |
| `REEDD_BROKER_URL` | `redis://127.0.0.1:6379/0` | |
| `REEDD_RESULT_BACKEND` | `redis://127.0.0.1:6379/1` | |
| `REEDD_DEFAULT_VOICE` | `af_heart` | used when the app doesn't ask for one |
| `REEDD_DEFAULT_SPEED` | `1.0` | |
| `REEDD_MAX_UPLOAD_BYTES` | `209715200` (200 MB) | |
| `REEDD_KEEP_INTERMEDIATE` | `0` | keep the per-chapter `.wav` files |
| `REEDD_API_TOKEN` | *(empty: no auth)* | if set, requests need `Authorization: Bearer <token>` |

## How it hangs together

**Job status lives in `data/jobs/<id>/job.json`, not in Celery's result
backend.** Celery results expire (24h by default) and vanish if Redis is
flushed, and an expired id reports as `PENDING`, which is indistinguishable
from an id that never existed — no good when the app may poll a job hours
later, after being closed and reopened (Phase 3). Celery stays the queue; it
just isn't asked to remember anything. The worker rewrites the manifest
atomically as it goes, so a poll never reads a half-written file.

**The web process never imports torch or kokoro.** It dispatches by task name
with `send_task`, which keeps uvicorn's startup instant and its memory small,
and lets the whole API be tested without the TTS stack installed.

**Progress comes from audiblez' existing `post_event` hook** (`CORE_PROGRESS`
carries a percentage and an ETA, `CORE_CHAPTER_FINISHED` a chapter count). It
fires once per sentence, so the manifest is only rewritten when the whole-number
percentage actually moves.

Each job directory holds the upload, an `out/` folder, and `worker.log`. The
per-chapter `.wav` files are deleted once the `.m4b` exists — they are roughly
20x its size, and this is a handheld's disk. That forfeits audiblez'
resume-from-`.wav` behaviour, which is the right trade for a one-shot job; set
`REEDD_KEEP_INTERMEDIATE=1` while debugging.

## Known gaps

- **A worker killed mid-job leaves the manifest at `running` forever.** Celery's
  `acks_late` would re-run it instead, but a job that crashes deterministically
  would then loop, and a conversion is expensive. `DELETE` the job and re-upload.
- **No cleanup policy.** Finished jobs sit on disk until deleted; nothing sweeps
  them. The app is expected to `DELETE` a job once it has both files.

## Tests

```sh
cd server && ../audiblez/.venv/bin/python -m unittest discover
```

55 tests, no Redis, no network, no TTS stack: the queue is stubbed and the
worker runs against a fake audiblez. To also convert `sample-short.epub` for
real (needs torch/kokoro/spacy/ffmpeg, ~15s):

```sh
REEDD_INTEGRATION=1 ../audiblez/.venv/bin/python -m unittest tests.test_audiblez_integration
```
