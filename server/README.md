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
| `POST /api/jobs` | multipart: `file` (the .epub), optional `engine`, `voice`, `speed`, `title`, `author`. `202` with the job. |
| `GET /api/jobs/{id}` | poll: `status`, `progress` (0-100), `eta`, `chapters_done`, `error`, `category`, `genres`. |
| `GET /api/jobs/{id}/audiobook` | the `.m4b`. Supports `Range`, so an interrupted download resumes. |
| `GET /api/jobs/{id}/sync` | the timing `.json`. |
| `GET /api/jobs/{id}/epub` | the original upload. Also `Range`-resumable. |
| `GET /api/jobs/{id}/log` | audiblez' output for this job, ffmpeg included. |
| `DELETE /api/jobs/{id}` | cancels if running, then deletes the job's files. |
| `GET /api/jobs` | every job, newest first. |
| `GET /api/voices` | voices for one engine (`?engine=`, default the server's default engine). |
| `GET /api/engines` | every engine and its own voices/default, for a two-level picker. |
| `GET /api/voices/{voice}/sample` | a short fixed-text clip of one voice (`?engine=`), generated once and cached. |
| `GET /api/me` | the caller's own `{user_id, email, is_admin}`. |
| `GET /api/health` | liveness; never requires a token, so the app can find the server. |

Admin-only (see "Sharing with others" below):

| | |
|---|---|
| `GET /api/admin/jobs` | every job, unfiltered, with `owner_email` joined in. |
| `POST /api/admin/jobs/{id}/public` | `{"public": bool}` — flips a job's visibility. |
| `GET /api/admin/users` | every invited user. |
| `POST /api/admin/users` | `{"email": str}` — invites a user and emails them a token. |
| `DELETE /api/admin/users/{user_id}` | revokes a user's access; refuses to delete your own account. |
| `GET /api/admin/metadata-health` | `{ok, last_error, last_error_at, last_success_at}` for the category/genre lookup — see below. |
| `GET /download/app` | unauthenticated: serves the APK, for an invitee who has no token yet. |

`status` is one of `queued`, `running`, `done`, `error`. Interactive docs are at
`/docs`. Every route above except `/api/health`, `/api/voices`, `/api/engines`
and `/download/app` needs `Authorization: Bearer <token>` — there is no
auth-optional mode any more, see "Sharing with others".

```console
$ auth="Authorization: Bearer $TOKEN"
$ curl -H "$auth" -F file=@book.epub -F voice=af_heart http://pocket4.local:8000/api/jobs
{"job_id":"bc2989f1-...","status":"queued", ...}

$ curl -H "$auth" http://pocket4.local:8000/api/jobs/bc2989f1-...
{"status":"running","progress":32,"eta":"00d 00h 00m 11s","chapters_done":0, ...}

$ curl -H "$auth" -OJ http://pocket4.local:8000/api/jobs/bc2989f1-.../audiobook
$ curl -H "$auth" -OJ http://pocket4.local:8000/api/jobs/bc2989f1-.../sync
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

### Staying up across reboots

All three processes (redis, celery, uvicorn) run as systemd **user** services
so a reboot doesn't silently take the app's backend down — `~/.config/systemd/user/reedd-{redis,celery,uvicorn}.service`,
enabled with `systemctl --user enable --now`. This machine has no root, so
redis comes from the `reedd` micromamba env
(`~/micromamba/envs/reedd/bin/redis-server`) rather than a distro package.

User services normally only run while that user has an active login session;
`loginctl enable-linger jsin` (no root needed for enabling your own linger)
makes them start at boot instead. Check with `loginctl show-user jsin -p Linger`
— `no` means a reboot will leave the backend down until someone logs back in.

Useful commands:

```sh
systemctl --user status reedd-redis reedd-celery reedd-uvicorn
systemctl --user restart reedd-uvicorn      # after pulling a server code change
journalctl --user -u reedd-uvicorn -f       # tail logs (replaces the old uvicorn.log/celery.log files)
```

### Sharing with others

The server only accepts requests from invited people — see "Configuration"
below for how access works. To let an invitee's phone reach it without
joining your Tailscale network, expose it publicly with [Tailscale
Funnel](https://tailscale.com/kb/1223/funnel):

```sh
tailscale funnel 8000
```

(Enable Funnel once for this node in the Tailscale admin console if it isn't
already.) This gives an HTTPS URL like `https://pocket4.<tailnet>.ts.net`
that resolves and connects from any network, reusing `tailscale cert` for
TLS — no port-forwarding, no separate account. Set `REEDD_PUBLIC_SERVER_URL`
to that URL; it's only used to build the `/download/app` link in invite
emails, since the Android app has its own copy of this URL baked in at build
time and never needs to be told it.

To invite someone:

1. Bootstrap yourself as the first admin (one-time, from `server/`):
   ```sh
   ../audiblez/.venv/bin/python -m app.users create-admin you@example.com
   ```
   This prints a token once — paste it into your own phone's Settings screen.
2. From the app's Admin screen (visible once you're signed in as an admin),
   enter the person's email and send the invite. If `REEDD_SMTP_USER` /
   `REEDD_SMTP_APP_PASSWORD` are set, they get an email with just their
   token and an APK download link — no server URL to communicate, the app
   already knows it. If SMTP isn't configured, the token is shown inline in
   the Admin screen so you can hand-deliver it instead.
3. They install the app and paste the token into Settings. That's it — no
   server address to enter.

Everyone sees the books they upload themselves, plus any job an admin has
marked public from the Admin screen. Nothing else is visible to them, and
mutating another user's job (deleting it) is refused even for a public one.

## Configuration

Everything has a working default; override with environment variables.

| variable | default | |
|---|---|---|
| `REEDD_DATA_DIR` | `server/data` | where uploads and output live |
| `REEDD_BROKER_URL` | `redis://127.0.0.1:6379/0` | |
| `REEDD_RESULT_BACKEND` | `redis://127.0.0.1:6379/1` | |
| `REEDD_DEFAULT_ENGINE` | `pocket_tts` | the only engine the server offers, see below |
| `REEDD_DEFAULT_SPEED` | `1.0` | |
| `REEDD_MAX_UPLOAD_BYTES` | `209715200` (200 MB) | |
| `REEDD_KEEP_INTERMEDIATE` | `0` | keep the per-chapter `.wav` files |
| `REEDD_CONVERSION_WORKERS` | *(auto)* | chapters to synthesize concurrently on CPU, see below |
| `REEDD_SMTP_HOST` | `smtp.gmail.com` | invite-email relay, see "Sharing with others" |
| `REEDD_SMTP_PORT` | `587` | |
| `REEDD_SMTP_USER` | *(empty: invites aren't emailed)* | a Gmail address |
| `REEDD_SMTP_APP_PASSWORD` | *(empty)* | an [app password](https://support.google.com/mail/answer/185833), not the account password |
| `REEDD_SMTP_FROM` | `REEDD_SMTP_USER` | |
| `REEDD_PUBLIC_SERVER_URL` | *(empty)* | the externally-reachable URL, for the `/download/app` link in invite emails |
| `REEDD_APK_PATH` | *(empty: `/download/app` returns 404)* | path to the APK it serves |
| `REEDD_GEMINI_API_KEY` | *(empty: every lookup fails, see "Watching for trouble")* | from [Google AI Studio](https://aistudio.google.com/apikey) |
| `REEDD_GEMINI_MODEL` | `gemini-3.1-flash-lite` | category/genre lookup, see below |

Access itself is invite-only, not an env var — see "Sharing with others"
above for `create-admin` and the admin-only invite endpoints.

## How it hangs together

**Job status lives in `data/jobs/<id>/job.json`, not in Celery's result
backend.** Celery results expire (24h by default) and vanish if Redis is
flushed, and an expired id reports as `PENDING`, which is indistinguishable
from an id that never existed — no good when the app may poll a job hours
later, after being closed and reopened (Phase 3). Celery stays the queue; it
just isn't asked to remember anything. The worker rewrites the manifest
atomically as it goes, so a poll never reads a half-written file.

**The web process never imports torch or pocket_tts.** It dispatches
by task name with `send_task`, which keeps uvicorn's startup instant and its
memory small, and lets the whole API be tested without the TTS stack
installed. It does need to know the engine's *voice names* to validate a
request, which is why those live in their own plain-data module
(`audiblez.pocket_tts_voices`) that imports nothing heavier
than the stdlib -- `server/app/audiblez_meta.py` reads that directly, never
`audiblez.engines` itself, so a future engine's module accidentally importing
torch at its own top level cannot silently break that separation.

**One TTS engine today; the interface still supports more.**
`audiblez.engines.TTSEngine` is a small interface (`sample_rate`,
`synthesize(text, voice, speed)`) that `PocketTTSEngine` implements;
`core.py`'s chapter loop (sequential or the parallel pool) calls it without
knowing which backend is underneath. `POST /api/jobs`'s `engine` field still
exists and is still validated (default `REEDD_DEFAULT_ENGINE`/`pocket_tts`,
currently the only value `ENGINES` accepts) rather than being collapsed away,
so a second engine remains a new `TTSEngine` subclass and a new `ENGINES`
entry, not a rewrite of the request contract.

It wasn't always one engine. Two others were evaluated and, for a while,
offered alongside Kokoro (the original engine this project started with)
after Kokoro's narration was reported as handling context and non-speech
sounds (interjections like "mmmhmmm") poorly -- worth keeping the history,
since it explains why Pocket TTS is the one that's left:

- [Chatterbox](https://github.com/resemble-ai/chatterbox) (standard/Turbo/Nano,
  all rejected on either quality or speed) measured 2-25x *slower* than
  Kokoro's already-established CPU-parallel baseline on this hardware.
- [Pocket TTS](https://github.com/kyutai-labs/pocket-tts) (~100M params) --
  built for CPU inference specifically, not a GPU model that happens to also
  run on CPU -- measured faster than Kokoro single-process. `speed` has no
  effect on its output (the backend has no speed control) -- accepted for
  API/manifest consistency, silently ignored. The one that stuck: quality won
  out, and the Android app's own picker was locked to it alone
  (`ImportSheet.kt`) well before Kokoro and Supertonic were removed from the
  server entirely -- see git history on `audiblez/engines.py` for both.
- [Supertonic 3](https://github.com/supertone-inc/supertonic) (~99M params,
  ONNX Runtime rather than PyTorch) also measured faster than Kokoro
  single-process, and unlike Pocket TTS its `speed` parameter was genuinely
  honored. Removed alongside Kokoro -- nothing in this project's real usage
  ever selected either.

All three shared the same chapter-parallelism path (`REEDD_CONVERSION_WORKERS`,
see below) and the same `.wav`/sync-cache shape, so nothing about chapter
caching, resuming, or the sync timeline's *structure* needed to change between
them. Their sample rates were not all the same, though -- Kokoro and Pocket
TTS both happen to use 24000Hz, Supertonic used 44100Hz -- and that was a
genuine, easy-to-miss trap: the sync timeline's own rate has to be resolved
from whichever engine a job actually selected
(`audiblez.engines.engine_sample_rate`), not assumed, or every timestamp in
that book's sync file comes out wrong by the ratio between the two rates.
Caught by hand while wiring Supertonic in, not by inspection -- still worth
knowing if a second engine ever gets added again.

**Progress comes from audiblez' existing `post_event` hook** (`CORE_PROGRESS`
carries a percentage and an ETA, `CORE_CHAPTER_FINISHED` a chapter count). It
fires once per sentence, so the manifest is only rewritten when the whole-number
percentage actually moves.

Each job directory holds the upload, an `out/` folder, and `worker.log`. The
per-chapter `.wav` files are deleted once the `.m4b` exists — they are roughly
20x its size, and this is a handheld's disk. That forfeits audiblez'
resume-from-`.wav` behaviour, which is the right trade for a one-shot job; set
`REEDD_KEEP_INTERMEDIATE=1` while debugging.

**Chapters synthesize in parallel on CPU.** A book's chapters are independent
work, and `audiblez.core` (`resolve_worker_count`, `_run_chapters_parallel`)
fans them out across several worker processes instead of the one-chapter-at-a-
time loop audiblez ships with upstream. GPU acceleration (ROCm, for the Pocket
4's integrated Radeon 890M) was also evaluated and, once actually working,
measured no faster than a single CPU process on this hardware -- MIOpen's
kernel support for this specific, very new GPU architecture just isn't mature
enough yet. CPU parallelism is the one that actually pays off here. Each
worker writes its chapter's `.wav` and sync cache straight to disk and only a
tiny summary crosses back to the main process, so results splice into the
final timeline through the exact same cache-reading code path a resumed run
already uses -- parallel and sequential runs produce identical output.
Automatically skipped (falls back to one process) whenever
`torch.cuda.is_available()` is true, since a single accelerator does not
benefit from being asked for by several processes at once.

The default (`REEDD_CONVERSION_WORKERS` unset) is roughly half the logical
CPUs, capped at 6 — deliberately short of "as many as there are cores": each
worker holds its own copy of the Kokoro model and spaCy in memory (~1.8GB RSS
measured on the reference Pocket 4), and this runs on a handheld with a lot
less RAM and thermal headroom than a desktop. This is a starting point, not a
measured optimum for your machine — raise or lower it once you've watched
`free -h` and temperatures during a real conversion. This is orthogonal to
Celery's own `--concurrency=1` above, which limits how many *jobs* run at
once, not how many chapters one job uses at a time; keep `--concurrency=1`
regardless, since two conversions each trying to fan out across the same CPUs
would only fight each other.

**Accumulating a library.** A finished job is a complete, standalone record of
one conversion — the epub that went in, the audiobook and sync file that came
out — and nothing here deletes it on its own. `GET /api/jobs` is therefore also
the library listing: on launch, the app diffs it against the jobs it already
has a local row for and adopts anything new, fetching the epub alongside the
audiobook and sync file it would fetch anyway. A book converted once stays
converted for every device that ever points at this server, without
re-uploading or waiting through TTS a second time. Nothing about a conversion
itself changes to make this work — audiblez, the queue, and the job.json
lifecycle are exactly as they were; this is purely a question of what the app
does with a job once it exists.

**Category/genre lookup, for sorting and filtering the library.** The app
sends `title`/`author` on upload (metadata it already extracted at import
time); the server asks Gemini (`gemini-3.1-flash-lite` by default) for
Fiction/Non-fiction plus a confidence-scored list of genre tags from a
fixed vocabulary (`app/llm_metadata.py`) — only tags scored 7+ (of 10) are
kept. **Requires `REEDD_GEMINI_API_KEY`** — unlike everything else this
server talks to, this is a real credential, not a keyless public API; an
unconfigured key makes every lookup fail (see "Watching for trouble"
below) rather than silently doing nothing. Nothing is queried if the app
didn't send a title. The lookup is a network-bound call, not CPU-bound
TTS work, so it runs via FastAPI's `BackgroundTasks` right after the
upload response is sent — it does not touch Celery's worker or its single
`--concurrency` slot, and often finishes well before the conversion
itself does. Every result (including "nothing found") is cached forever
in `data/book_metadata.json`, keyed by a normalized title+author pair, so
the same book is never looked up twice even across different users'
uploads. See `app/book_metadata.py`, `app/llm_metadata.py`, and
`LLM_GENRE_ENRICHMENT.md` for the full design and the live experimentation
(across several models and approaches) behind it — including why Open
Library and Google Books, an earlier version of this feature, were
removed rather than kept as a fallback.

**Watching for trouble.** Gemini is the *only* source now — there is no
second one to quietly fall back to if it breaks. `GET /api/admin/
metadata-health` (surfaced as a warning banner on the app's Admin screen)
reports whether the most recent real lookup attempt succeeded; a bad or
revoked API key, an exhausted quota, or a deprecated model name (Google
has fully removed model versions with only a `404` pointing at their
replacement, more than once during this feature's own development) all
show up there rather than as a silent "books never get tagged."

A job converted before this feature existed has no title/author at all, so
it never gets a category/genre on its own. `python -m app.backfill_metadata`
(`--dry-run` to preview first) is a one-off fix for that backlog: it reads
title/author straight out of each such job's own stored epub (`app/
epub_meta.py`) and runs the same lookup. Safe to re-run — a job that
already has a category/genres is skipped unless `--recheck` is passed,
which re-resolves everything (bypassing the cache too) — useful after a
prompt/vocabulary change like the Open-Library-to-Gemini switch itself.

## Known gaps

- **A worker killed mid-job leaves the manifest at `running` forever.** Celery's
  `acks_late` would re-run it instead, but a job that crashes deterministically
  would then loop, and a conversion is expensive. `DELETE` the job and re-upload.
- **Deleting/cancelling a job mid-conversion does not stop its chapter workers.**
  `DELETE /api/jobs/{id}` revokes the Celery task with `terminate=True`, which
  signals the worker process actually running it — but once that task has
  handed chapters off to its own `billiard.pool.Pool` (parallel CPU synthesis,
  see "Chapters synthesize in parallel" above), the signal has been observed
  not to reach, or not to stop, those child processes. They keep running to
  completion — full CPU each — with nothing left tracking them, since the job
  they belong to is already gone from disk. Confirmed by hand once; not yet
  root-caused (unclear whether the signal isn't reaching the pool workers at
  all, or is being caught/ignored) or fixed. If a cancelled conversion seems to
  leave the machine still under load, `ps -ef | grep celery` and kill any
  leftover children by hand; restarting the Celery worker also clears them.
- **No automatic disk reclamation.** Finished jobs accumulate on disk
  indefinitely by design now (see "Accumulating a library" above) — the app's
  "free server disk after downloading" setting defaults to off. Nothing sweeps
  old jobs on its own; `DELETE /api/jobs/{id}` is a manual, deliberate action, on
  a server disk that is not being watched for size.

## Tests

```sh
cd server && ../audiblez/.venv/bin/python -m unittest discover
```

179 tests, no Redis, no network, no TTS stack: the queue is stubbed and the
worker runs against a fake audiblez. To also convert `sample-short.epub` for
real (needs torch/kokoro/spacy/ffmpeg, ~15s):

```sh
REEDD_INTEGRATION=1 ../audiblez/.venv/bin/python -m unittest tests.test_audiblez_integration
```
