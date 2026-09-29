# -*- coding: utf-8 -*-
"""FastAPI front end for the conversion server.

The contract the Android app codes against:

    POST   /api/jobs                    upload an .epub  -> 202 {job_id, status}
                                         (optional title/author form fields feed
                                         a background category/genre lookup --
                                         see SORT_GROUP_LIBRARY.md; optional `mode`
                                         -- offline/live/live_offline, see
                                         UPLOAD_MODES -- offline unless given)
    GET    /api/jobs/{job_id}           poll             -> {status, progress, eta,
                                         category, genres, ...}
    GET    /api/jobs/{job_id}/audiobook the .m4b         (Range-resumable)
    GET    /api/jobs/{job_id}/sync      the timing .json
    GET    /api/jobs/{job_id}/epub      the original upload (Range-resumable)
    GET    /api/jobs/{job_id}/cover     cover art -- from the epub, fetched
                                         from Open Library, or a generated
                                         placeholder, on first request if it
                                         had none; see app.cover_lookup and
                                         app.cover_generator
    GET    /api/jobs/{job_id}/log       audiblez' output for this job
    DELETE /api/jobs/{job_id}           cancel and/or reclaim the disk (owner or admin only)
    GET    /api/jobs                    every job you own, plus every public job,
                                         newest first -- doubles as the library
                                         listing; see README.md
    POST   /api/books/{id}/live/start   synthesize a chapter live, sentence by
                                         sentence, CPU-only, instead of converting
                                         the whole book first -> {session_id}; see
                                         app.live_reading. 503 if the (small, fixed)
                                         concurrency cap is already full.
    POST   /api/books/{id}/live/{session_id}/advance
                                         tell it where the reader actually is now
                                         -> the same shape as .../status
    GET    /api/books/{id}/live/{session_id}/status
                                         chunks synthesized so far, for polling
    GET    /api/books/{id}/live/{session_id}/chunk/{n}
                                         one synthesized sentence's audio (WAV)
    POST   /api/books/{id}/live/{session_id}/stop
                                         release the session's engine slot early
    GET    /api/voices                  voices for one engine (default pocket_tts), for a picker
    GET    /api/engines                 every engine and its voices, for a two-level picker
    GET    /api/voices/{voice}/sample   a short fixed-text clip of one voice, generated
                                         once and cached; see SAMPLE_TEXT below
    GET    /api/me                      the caller's own {user_id, email, is_admin}
    GET    /api/health

Admin-only (see app.users.UserStore):

    GET    /api/admin/jobs               every job, unfiltered, with owner_email joined in
    POST   /api/admin/jobs/{id}/public   {"public": bool} -- flip a job's visibility
    GET    /api/admin/users              every invited user
    POST   /api/admin/users              {"email": str} -- invite a new user, email them a token
    DELETE /api/admin/users/{user_id}    revoke a user's access (not your own account)
    GET    /api/admin/apk                the live (/download/app) and pending (apk_build_dir) APKs
    POST   /api/admin/push-apk           copy the newest build over the live one -- "Push Update"

    GET    /download/app                 unauthenticated: serves the APK, for an
                                          invitee who has no token yet -- see push_apk above for
                                          why a rebuild does not reach this on its own

Local diagnostics (unauthenticated, meant for Tailscale only -- see its own
section further down for why):

    GET    /admin/dashboard              a page to open directly in a browser: job
                                          storage, plus Flower and Netdata embedded,
                                          and a Live TTS tab (see below)
    GET    /admin/dashboard/storage      the storage table's own data, as JSON
    POST   /admin/live-tts/start         starts the live-TTS experiment (see
                                          app.live_tts_experiment) -> {session_id}
    GET    /admin/live-tts/status/{id}   chunks synthesized so far, for polling
    GET    /admin/live-tts/chunk/{id}/{n} one synthesized sentence's audio (WAV)

Upload returns as soon as the file is on disk; the conversion happens in a
Celery worker. This process never imports torch or pocket_tts during normal
operation -- the one deliberate exception is the Live TTS experiment above,
which does so lazily and only when someone actually uses that dashboard tab.
"""

import logging
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from fastapi import BackgroundTasks, Depends, FastAPI, File, Form, Header, HTTPException, Request, Response, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, PlainTextResponse
from pydantic import BaseModel

from .audiblez_meta import (DEFAULT_VOICE_BY_ENGINE, ENGINES, MAX_SPEED, MIN_SPEED,
                            known_voices)
from .book_metadata import LookupUnavailable, lookup as lookup_book_metadata
from .book_metadata_store import BookMetadataStore
from .celery_app import enqueue, revoke
from .config import get_settings
from . import live_reading, live_tts_experiment
from .cover_generator import generate_placeholder_cover
from .cover_lookup import fetch_cover
from .mailer import invite_configured, send_invite
from .metadata_health import MetadataHealth
from .store import (DONE, LIVE_ONLY, TERMINAL_STATUSES, JobNotFound, JobStore, UploadTooLarge,
                    looks_like_epub)
from .users import UserNotFound, UserStore

app = FastAPI(
    title='read-d-book conversion server',
    description='Converts .epub to .m4b + read-along timing metadata.',
    version='1.0.0',
)


@app.on_event('shutdown')
def _stop_live_reading_sessions():
    """A live-reading worker killed mid-synthesis (a blocking native torch
    call) when the process exits aborts the whole interpreter, not a
    catchable Python exception -- confirmed live while testing app.
    live_reading. This makes `systemctl restart reedd-uvicorn` (or any
    graceful shutdown) safe while someone is reading live, by stopping and
    joining every session's worker first. See live_reading.shutdown's own
    doc.
    """
    live_reading.shutdown()


class _MaxUploadSizeMiddleware:
    """Rejects an oversized POST /api/jobs by its Content-Length header,
    before Starlette's multipart parser ever touches the body.

    `UploadFile`'s own parser must fully spool the file to disk/RAM before
    create_job's handler runs at all (confirmed against the installed
    starlette.formparsers source -- its built-in size limit only covers
    non-file form fields), so JobStore.save_upload's max_upload_bytes check
    was only ever catching an oversized file *after* the whole thing had
    already been received -- on a disk- and thermal-constrained handheld,
    that means an oversized request could fill the OS temp partition before
    the app-level guard ever got a say. Content-Length is client-supplied
    and can be absent or wrong (chunked transfer-encoding, a lying header),
    so this is a backstop for the common case, not a hard guarantee --
    save_upload's own streaming check is what's actually trustworthy, and
    stays in place unchanged.
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope['type'] == 'http' and scope['method'] == 'POST' and scope['path'] == '/api/jobs':
            max_bytes = get_settings().max_upload_bytes
            content_length = next((v for k, v in scope['headers'] if k == b'content-length'), None)
            if content_length is not None and int(content_length) > max_bytes:
                response = PlainTextResponse(f'epub exceeds {max_bytes} bytes', status_code=413)
                await response(scope, receive, send)
                return
        await self.app(scope, receive, send)


app.add_middleware(_MaxUploadSizeMiddleware)

invite_log = logging.getLogger('reedd.mail')
book_metadata_log = logging.getLogger('reedd.book_metadata')
live_reading_log = logging.getLogger('reedd.live_reading')
# Explicit handler + level: nothing configures the root logger in this
# process (uvicorn's own default config only sets up its own 'uvicorn*'
# loggers), so an ordinary `logging.getLogger(...).info(...)` call here
# would silently vanish -- Python's logging falls back to a WARNING-level
# "last resort" handler when no handler is configured anywhere in a
# logger's chain, which drops INFO records before they reach it. Confirmed
# empirically: existing `.info()` calls elsewhere in this file (e.g.
# book_metadata_log) never actually appear in `journalctl`. Scoped to just
# this one logger rather than reconfiguring the root, to avoid changing
# any other module's existing (if quietly broken) logging behavior.
live_reading_log.setLevel(logging.INFO)
if not live_reading_log.handlers:
    _live_reading_handler = logging.StreamHandler()
    _live_reading_handler.setFormatter(logging.Formatter('%(asctime)s %(name)s %(levelname)s %(message)s'))
    live_reading_log.addHandler(_live_reading_handler)


def store() -> JobStore:
    return JobStore(get_settings().jobs_dir)


def users() -> UserStore:
    return UserStore(get_settings().data_dir)


def book_metadata() -> BookMetadataStore:
    return BookMetadataStore(get_settings().data_dir)


def metadata_health() -> MetadataHealth:
    return MetadataHealth(get_settings().data_dir)


def require_user(authorization: str = Header(default='')) -> dict:
    """Every route but /api/health, /api/voices, /api/engines and
    /download/app needs a valid per-user token -- there is no LAN-trust,
    auth-optional mode any more (see users.py and README.md, "Sharing with
    others"): if you can call this, you were invited.
    """
    token = authorization.removeprefix('Bearer ').strip()
    user = users().find_by_token(token) if token else None
    if user is None:
        raise HTTPException(status_code=401, detail='invalid or missing API token')
    return user


def require_admin(user: dict = Depends(require_user)) -> dict:
    if not user.get('is_admin'):
        raise HTTPException(status_code=403, detail='admin only')
    return user


def _visible(job: dict, user: dict) -> bool:
    """Whether `user` may see this job: they own it, it's public, or they're
    admin. The admin branch also covers every job created before `owner`
    existed (owner is None on those) -- see store.JobStore.create -- so nothing
    from before this feature shipped is orphaned, it's just admin-only until
    reclaimed or made public.
    """
    return bool(user.get('is_admin')) or job.get('owner') == user['user_id'] or bool(job.get('public'))


def _owns_or_admin(job: dict, user: dict) -> bool:
    """Narrower than _visible: seeing a public job someone else owns does not
    mean you may delete it."""
    return bool(user.get('is_admin')) or job.get('owner') == user['user_id']


def _read_or_404(job_id: str) -> dict:
    try:
        return store().read(job_id)
    except JobNotFound:
        raise HTTPException(status_code=404, detail=f'no such job: {job_id}')


def get_job(job_id: str, user: dict) -> dict:
    manifest = _read_or_404(job_id)
    if not _visible(manifest, user):
        # Same 404 as a truly unknown id: a caller must not be able to tell
        # "not yours" apart from "doesn't exist" by status code alone.
        raise HTTPException(status_code=404, detail=f'no such job: {job_id}')
    return manifest


@app.get('/api/health')
def health():
    settings = get_settings()
    return {'status': 'ok', 'data_dir': str(settings.data_dir), 'broker': settings.broker_url}


@app.get('/api/voices')
def voices(engine: str | None = None):
    """Voices for one engine. Defaults to the server's default engine
    (pocket_tts unless REEDD_DEFAULT_ENGINE says otherwise) if `engine` is
    not given, which keeps this endpoint's existing contract for a client
    that has not been updated to know engines exist at all.
    """
    engine = engine or get_settings().default_engine
    if engine not in ENGINES:
        raise HTTPException(status_code=400, detail=f'unknown engine: {engine}')
    return {'voices': known_voices(engine), 'default': DEFAULT_VOICE_BY_ENGINE.get(engine), 'engine': engine}


@app.get('/api/engines')
def engines():
    """Every engine and its voices, for a two-level (engine, then voice) picker."""
    settings = get_settings()
    return {
        'engines': [
            {
                'id': e,
                'voices': known_voices(e),
                'default_voice': DEFAULT_VOICE_BY_ENGINE.get(e),
            }
            for e in ENGINES
        ],
        'default': settings.default_engine,
    }


#: What every voice-preview clip says, chosen for having a full range of
#: ordinary phonemes without leaning on any one emotion -- a fair, neutral
#: sample of what a voice actually sounds like reading prose.
SAMPLE_TEXT = ('The city slowly came to life as the morning train arrived on time, '
               'bringing commuters ready to start another productive week.')


def _sample_path(settings, engine: str, voice: str) -> Path:
    # `engine`/`voice` are only ever reached here after validation against
    # ENGINES/known_voices() below -- both fixed, code-defined whitelists --
    # so building a path from them directly is safe; neither is ever
    # attacker-controlled free text the way a job's uploaded filename is.
    return settings.voice_samples_dir / engine / f'{voice}.wav'


def _synthesize_sample(engine: str, voice: str, path: Path) -> None:
    """Generate one voice's preview clip and cache it to `path`.

    Deferred imports, same discipline as everywhere else in this file: the
    web process must not import torch/pocket_tts at module level (see
    this module's own docstring), only from inside a request that actually
    needs them -- which, for this route, is only the very first request for
    a voice nobody has previewed yet.
    """
    import numpy as np
    import soundfile
    from audiblez.engines import engine_sample_rate, load_engine

    tts_engine = load_engine(engine, voice)
    segments = tts_engine.synthesize(SAMPLE_TEXT, voice, speed=1.0)
    audio = np.concatenate(segments) if len(segments) > 1 else segments[0]

    path.parent.mkdir(parents=True, exist_ok=True)
    raw_tmp = path.with_name(f'.{path.name}.{os.getpid()}.raw.tmp')
    tmp = path.with_name(f'.{path.name}.{os.getpid()}.tmp')
    try:
        # format='WAV' explicitly: soundfile otherwise infers the format from
        # the filename's extension, and the temp file's real extension is
        # '.tmp', not '.wav' -- it raised TypeError here on the very first
        # real request.
        soundfile.write(raw_tmp, audio, engine_sample_rate(engine), format='WAV')
        # Same target and reasoning as the real conversion pipeline's
        # create_m4b (audiblez/core.py): raw voices vary by up to ~10 LU in
        # loudness, and a preview that doesn't reflect how loud a voice will
        # actually sound in a finished book defeats the point of previewing
        # it before committing a whole conversion to it.
        # -f wav explicitly, the same reason format='WAV' is explicit just
        # above: the temp file's real extension is '.tmp', which ffmpeg
        # cannot guess an output format from any better than soundfile could.
        subprocess.run(
            ['ffmpeg', '-y', '-i', str(raw_tmp), '-af', 'loudnorm=I=-18:TP=-2:LRA=11', '-f', 'wav', str(tmp)],
            check=True, capture_output=True,
        )
    finally:
        raw_tmp.unlink(missing_ok=True)
    os.replace(tmp, path)


@app.get('/api/voices/{voice}/sample')
def voice_sample(voice: str, engine: str | None = None, user: dict = Depends(require_user)):
    """A short, fixed-text clip of one voice (see SAMPLE_TEXT), for browsing
    what each voice sounds like before picking one to convert with.

    Generated once per voice and cached on disk forever after -- browsing
    every voice must not mean re-synthesizing the same sentence on every
    tap. Deliberately synchronous (`def`, not `async def`): the first
    request for an as-yet-unsampled voice does real, CPU-bound TTS work
    (a few seconds), which has to run on a worker thread, not the event
    loop, the same reasoning as create_job's upload handling above.

    Requires a token (unlike /api/voices, /api/engines): this does real
    work server-side, and the server is reachable from the open internet
    now (see README.md, "Sharing with others") -- an unauthenticated
    version of this route would be a way for anyone to burn this
    handheld's CPU for free.
    """
    settings = get_settings()
    engine = engine or settings.default_engine
    if engine not in ENGINES:
        raise HTTPException(status_code=400, detail=f'unknown engine: {engine}')
    if voice not in known_voices(engine):
        raise HTTPException(status_code=400, detail=f'unknown voice for engine {engine}: {voice}')

    path = _sample_path(settings, engine, voice)
    if not path.is_file():
        _synthesize_sample(engine, voice, path)
    return FileResponse(path, media_type='audio/wav', filename=path.name)


def _resolve_book_metadata(job_id: str, title: str | None, author: str | None) -> None:
    """Category/genre lookup, run via BackgroundTasks after create_job's 202
    is already sent -- a network-bound lookup has no more business blocking
    an upload response than TTS synthesis has running in this process at
    all. See SORT_GROUP_LIBRARY.md.
    """
    if not title:
        return
    cache = book_metadata()
    cached = cache.get(title, author)
    if cached is None:
        try:
            result = lookup_book_metadata(title, author)
        except LookupUnavailable as e:
            # Every source failed at the request level -- not the same as a
            # genuine "nothing found", so this must not be cached: caching
            # it would mean this book can never be looked up again. Leave
            # it unresolved; a future upload of the same book tries fresh.
            book_metadata_log.info('lookup unavailable: %s', e)
            return
        cache.put(title, author, result)
        cached = cache.get(title, author)
    try:
        store().update(job_id, category=cached['category'], genres=cached['genres'])
    except JobNotFound:
        pass  # deleted or cancelled before the lookup finished


#: What POST /api/jobs' `mode` form field accepts -- see store.JobStore.
#: create's own doc and CPU_LIVE_READING_PLAN for what each one means.
UPLOAD_MODES = ('offline', 'live', 'live_offline')


@app.post('/api/jobs', status_code=202)
def create_job(background_tasks: BackgroundTasks,
               file: UploadFile = File(...),
               voice: str = Form(default=None),
               speed: float = Form(default=None),
               engine: str = Form(default=None),
               title: str = Form(default=None),
               author: str = Form(default=None),
               mode: str = Form(default='offline'),
               user: dict = Depends(require_user)):
    """Accept an .epub and queue it. Returns immediately with the job's id.

    Deliberately synchronous (`def`, not `async def`) so Starlette runs it in a
    worker thread: the upload is written with blocking I/O in 1 MB chunks, and
    a 200 MB book must not stall the event loop.

    `title`/`author` are optional, sent by the app from metadata it already
    extracted at import time -- used only to kick off a best-effort
    category/genre lookup in the background (see SORT_GROUP_LIBRARY.md),
    not stored or validated beyond that.

    `mode` -- 'offline' (default), 'live', or 'live_offline' -- only
    'live' actually changes what happens here: it skips the Celery enqueue
    entirely and leaves the job at `LIVE_ONLY` rather than `QUEUED`, since
    that book is never meant to produce a downloadable audiobook on its
    own. See UPLOAD_MODES and store.JobStore.create's own doc.
    """
    settings = get_settings()
    engine = engine or settings.default_engine
    if engine not in ENGINES:
        raise HTTPException(status_code=400, detail=f'unknown engine: {engine}')
    if voice is None:
        voice = DEFAULT_VOICE_BY_ENGINE.get(engine)
    speed = settings.default_speed if speed is None else speed
    if mode not in UPLOAD_MODES:
        raise HTTPException(status_code=400, detail=f'unknown mode: {mode}')

    valid = known_voices(engine)
    if valid and voice not in valid:
        raise HTTPException(status_code=400, detail=f'unknown voice for engine {engine}: {voice}')
    if not MIN_SPEED <= speed <= MAX_SPEED:
        raise HTTPException(status_code=400,
                            detail=f'speed must be between {MIN_SPEED} and {MAX_SPEED}')
    if not (file.filename or '').lower().endswith('.epub'):
        raise HTTPException(status_code=400, detail='expected a .epub file')

    jobs = store()
    manifest = jobs.create(file.filename, voice, speed, engine, owner=user['user_id'],
                           title=title, author=author, mode=mode)
    job_id = manifest['job_id']
    background_tasks.add_task(_resolve_book_metadata, job_id, title, author)
    try:
        jobs.save_upload(job_id, file.file, settings.max_upload_bytes)
    except UploadTooLarge:
        jobs.delete(job_id)
        raise HTTPException(status_code=413,
                            detail=f'epub exceeds {settings.max_upload_bytes} bytes')
    if not looks_like_epub(jobs.job_dir(job_id) / manifest['filename']):
        jobs.delete(job_id)
        raise HTTPException(status_code=400, detail='file is not a valid epub (not a zip archive)')

    if mode == 'live':
        # No Celery task at all -- this book is only ever read live
        # (app.live_reading), which works against the epub on disk
        # regardless of job status.
        return jobs.update(job_id, status=LIVE_ONLY)

    try:
        celery_task_id = enqueue(job_id)
    except Exception as e:
        # Redis down, most likely. Fail loudly now rather than leaving a job
        # sitting at "queued" that nothing will ever pick up.
        jobs.delete(job_id)
        raise HTTPException(status_code=503, detail=f'could not queue the job: {e}')
    return jobs.update(job_id, celery_task_id=celery_task_id)


@app.get('/api/jobs')
def list_jobs(limit: int = 50, user: dict = Depends(require_user)):
    limit = max(1, min(limit, 500))
    # Fetch generously, then filter by visibility, then cap -- filtering
    # after store().list(limit=N) would silently return fewer than N visible
    # jobs even when more existed further back in the unfiltered list.
    visible = [j for j in store().list(limit=10_000) if _visible(j, user)]
    return {'jobs': visible[:limit]}


@app.get('/api/jobs/{job_id}')
def job_status(job_id: str, user: dict = Depends(require_user)):
    return get_job(job_id, user)


@app.delete('/api/jobs/{job_id}')
def delete_job(job_id: str, user: dict = Depends(require_user)):
    """Cancel the job if it is still running, then delete everything it wrote.

    Ownership-gated, not just visibility-gated: being able to see a public
    job someone else uploaded does not mean you may delete it.
    """
    manifest = get_job(job_id, user)
    if not _owns_or_admin(manifest, user):
        raise HTTPException(status_code=403, detail='not your job')
    if manifest['status'] not in TERMINAL_STATUSES:
        revoke(manifest.get('celery_task_id'))
    store().delete(job_id)
    return {'job_id': job_id, 'deleted': True}


def _completed_file(job_id: str, kind: str, user: dict) -> Path:
    manifest = get_job(job_id, user)
    if manifest['status'] != DONE:
        detail = manifest.get('error') or f'job is {manifest["status"]}'
        # 409, not 404: the id is valid, the result just isn't there yet.
        raise HTTPException(status_code=409, detail=detail)
    entry = manifest.get(kind) or {}
    path = store().output_dir(job_id) / entry.get('file', '')
    if not entry.get('file') or not path.is_file():
        raise HTTPException(status_code=410, detail=f'{kind} is no longer on disk')
    return path


@app.get('/api/jobs/{job_id}/audiobook')
def download_audiobook(job_id: str, user: dict = Depends(require_user)):
    """The .m4b. FileResponse honours Range, so an interrupted download resumes."""
    path = _completed_file(job_id, 'audiobook', user)
    return FileResponse(path, media_type='audio/mp4', filename=path.name)


@app.get('/api/jobs/{job_id}/sync')
def download_sync(job_id: str, user: dict = Depends(require_user)):
    """The text-to-timestamp mapping. Format documented in audiblez/SYNC.md."""
    path = _completed_file(job_id, 'sync', user)
    return FileResponse(path, media_type='application/json', filename=path.name)


@app.get('/api/jobs/{job_id}/epub')
def download_epub(job_id: str, user: dict = Depends(require_user)):
    """The originally uploaded .epub.

    Not gated on the job being done -- the upload is written before conversion
    starts and never changes after. This is what lets a device that never
    uploaded this book itself (a different device, a reinstall, wiped app
    storage) adopt a job the server already finished: it can fetch the epub,
    the audiobook and the sync file from a `GET /api/jobs` listing alone,
    without re-uploading or waiting through TTS again. See `GET /api/jobs`
    below and README.md, "Accumulating a library".
    """
    get_job(job_id, user)  # 404s on an unknown or invisible id
    path = store().epub_path(job_id)
    if not path.is_file():
        raise HTTPException(status_code=410, detail='epub is no longer on disk')
    return FileResponse(path, media_type='application/epub+zip', filename=path.name)


def _sniff_image_media_type(path: Path) -> str:
    """`cover` is written with no extension (see app.tasks.convert_epub), so
    the content type has to come from the bytes themselves, not the name."""
    with open(path, 'rb') as f:
        header = f.read(8)
    return 'image/png' if header.startswith(b'\x89PNG\r\n\x1a\n') else 'image/jpeg'


@app.get('/api/jobs/{job_id}/cover')
def download_cover(job_id: str, user: dict = Depends(require_user)):
    """The cover image, if one exists -- extracted from the epub itself by
    audiblez, fetched from Open Library as a fallback once conversion
    finished with none (app.cover_lookup), or a generated placeholder if
    even Open Library has nothing (app.cover_generator). The last of those
    always succeeds, so every done job serves *some* cover.

    A job that finished *before* these fallbacks existed gets one lazily,
    right here, the first time anything actually asks for it -- "backfill
    the covers of everything already converted" this way, on demand as each
    book is next downloaded, rather than a separate one-time pass over the
    whole job store that would spend the lookup on books nobody is
    revisiting.
    """
    manifest = get_job(job_id, user)
    if manifest['status'] != DONE:
        detail = manifest.get('error') or f'job is {manifest["status"]}'
        raise HTTPException(status_code=409, detail=detail)
    output_dir = store().output_dir(job_id)
    entry = manifest.get('cover') or {}
    cover_path = output_dir / entry.get('file', 'cover')
    if not entry.get('file') or not cover_path.is_file():
        fetched = fetch_cover(manifest.get('title'), manifest.get('author'))
        if not fetched:
            fetched = generate_placeholder_cover(manifest.get('title') or manifest['filename'])
        cover_path = output_dir / 'cover'
        cover_path.write_bytes(fetched)
        try:
            store().update(job_id, cover={'file': cover_path.name, 'bytes': cover_path.stat().st_size})
        except JobNotFound:
            pass  # deleted between the check above and now; still serve this one response
    return FileResponse(cover_path, media_type=_sniff_image_media_type(cover_path), filename='cover.jpg')


# -- live reading -------------------------------------------------------------
#
# Phase 1 of the live-reading plan (see PURRFECT_HOPPING_UNICORN, saved
# 2026-09-07): synthesize a chapter sentence by sentence, on demand, CPU-only,
# instead of converting the whole book first. See app.live_reading's own
# module doc for the session/pool design. Upload-mode wiring (an actual
# "Live" book with no conversion job) and the Android player are later
# phases -- these routes work against any book whose epub is on disk today,
# converted or not.


class LiveReadingStartBody(BaseModel):
    #: The epub-internal resource path/filename of the chapter to read,
    #: e.g. "OEBPS/xhtml/chapter1.xhtml" -- resolved by bare filename, not
    #: numeric position (see live_reading._extract_sentences's own doc).
    resource_href: str
    from_sentence_index: int = 0
    #: 0..1, "how far into this resource" -- overrides from_sentence_index
    #: when given and anchor_text finds nothing (see live_reading.start's
    #: own doc). Android only knows a proportion, since its own text
    #: extraction does not exactly match this server's.
    from_fraction: float | None = None
    #: A window of text (Android's own epub extraction) centered on the
    #: actual tap point -- tried before from_fraction, an exact match
    #: immune to the two extractions counting sentences differently.
    anchor_text: str | None = None
    #: The tap's own raw offset within anchor_text -- not generally its
    #: midpoint, since the window is clamped at either edge of the
    #: resource's own text (see live_reading._resolve_anchor's own doc).
    anchor_offset: int | None = None
    voice: str


class LiveReadingAdvanceBody(BaseModel):
    now_at_sentence_index: int


@app.post('/api/books/{book_id}/live/start')
def live_reading_start(book_id: str, body: LiveReadingStartBody, user: dict = Depends(require_user)):
    get_job(book_id, user)  # 404s on an unknown or invisible id
    epub_path = store().epub_path(book_id)
    if not epub_path.is_file():
        raise HTTPException(status_code=410, detail='epub is no longer on disk')
    # Added to trace two real, reported bugs with no visible server-side
    # error: live/offline mode switching "doesn't seem to work," and
    # picking a new live voice not actually changing it. This is the one
    # place that can confirm whether the client ever sent the new voice at
    # all, and what the pool actually did with the request.
    live_reading_log.info(
        'live/start book_id=%s voice=%s resource_href=%s from_sentence_index=%s from_fraction=%s '
        'anchor_text=%s anchor_offset=%s',
        book_id, body.voice, body.resource_href, body.from_sentence_index, body.from_fraction,
        'yes' if body.anchor_text else None, body.anchor_offset,
    )
    try:
        session_id, resolved_index = live_reading.start(
            book_id, epub_path, body.resource_href, body.from_sentence_index, body.voice,
            from_fraction=body.from_fraction, anchor_text=body.anchor_text, anchor_offset=body.anchor_offset,
        )
    except live_reading.Busy:
        live_reading_log.warning('live/start book_id=%s voice=%s -- pool busy', book_id, body.voice)
        raise HTTPException(status_code=503, detail='reading live is busy right now -- try again shortly')
    except live_reading.UnknownChapter as e:
        live_reading_log.warning('live/start book_id=%s voice=%s -- unknown chapter: %s', book_id, body.voice, e)
        raise HTTPException(status_code=400, detail=str(e))
    live_reading_log.info(
        'live/start book_id=%s voice=%s -- session=%s resolved_from_sentence_index=%s',
        book_id, body.voice, session_id, resolved_index,
    )
    return {'session_id': session_id, 'from_sentence_index': resolved_index}


@app.post('/api/books/{book_id}/live/{session_id}/advance')
def live_reading_advance(book_id: str, session_id: str, body: LiveReadingAdvanceBody, user: dict = Depends(require_user)):
    get_job(book_id, user)
    result = live_reading.advance(session_id, body.now_at_sentence_index)
    if result is None:
        raise HTTPException(status_code=404, detail='unknown or expired live session')
    return result


@app.get('/api/books/{book_id}/live/{session_id}/status')
def live_reading_status(book_id: str, session_id: str, user: dict = Depends(require_user)):
    get_job(book_id, user)
    result = live_reading.status(session_id)
    if result is None:
        raise HTTPException(status_code=404, detail='unknown or expired live session')
    return result


@app.get('/api/books/{book_id}/live/{session_id}/chunk/{index}')
def live_reading_chunk(book_id: str, session_id: str, index: int, user: dict = Depends(require_user)):
    get_job(book_id, user)
    audio = live_reading.chunk_audio(session_id, index)
    if audio is None:
        raise HTTPException(status_code=404, detail='chunk not found')
    return Response(content=audio, media_type='audio/wav')


@app.post('/api/books/{book_id}/live/{session_id}/stop')
def live_reading_stop(book_id: str, session_id: str, user: dict = Depends(require_user)):
    get_job(book_id, user)
    live_reading.stop(session_id)
    return {'stopped': True}


# -- app diagnostics --------------------------------------------------------
#
# The app cannot display its own stack trace once the process has died, and the
# Android emulator does not run on the Pocket 4, so `adb logcat` is the only
# other way to see one.  These two endpoints let the phone leave a crash report
# here instead: it is written to disk *and* logged, so it shows up in the
# uvicorn console as it arrives.

crash_log = logging.getLogger('reedd.crash')

MAX_CRASH_BYTES = 256 * 1024
MAX_CRASH_FILES = 200


@app.post('/api/diagnostics/crash', status_code=202, dependencies=[Depends(require_user)])
async def report_crash(request: Request):
    """Accept a crash report as plain text from the app.

    Deliberately lenient: this is the endpoint of last resort for a client that
    has just died, so it validates almost nothing and never fails in a way that
    would lose the report.
    """
    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail='empty crash report')
    text = body[:MAX_CRASH_BYTES].decode('utf-8', errors='replace')

    settings = get_settings()
    settings.crashes_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%f')
    path = settings.crashes_dir / f'crash-{stamp}.txt'
    path.write_text(text, encoding='utf-8')

    # One line per crash in the console, plus the trace, so it is visible in the
    # terminal running uvicorn without going looking for the file.
    first_line = next((ln for ln in text.splitlines() if ln.strip()), '(no detail)')
    crash_log.error('app crash reported (%s): %s\n%s', path.name, first_line, text)

    _prune_crashes(settings.crashes_dir)
    return {'stored': path.name, 'bytes': len(text)}


@app.get('/api/diagnostics/crashes', response_class=PlainTextResponse,
         dependencies=[Depends(require_user)])
def list_crashes(limit: int = 5):
    """The most recent crash reports, newest first, as one plain-text blob."""
    settings = get_settings()
    if not settings.crashes_dir.is_dir():
        return 'no crash reports'
    files = sorted(settings.crashes_dir.glob('crash-*.txt'), reverse=True)[:max(1, min(limit, 50))]
    if not files:
        return 'no crash reports'
    return '\n\n'.join(
        f'===== {f.name} =====\n{f.read_text(encoding="utf-8", errors="replace")}' for f in files
    )


def _prune_crashes(directory: Path) -> None:
    """Keep the newest MAX_CRASH_FILES; a crash loop must not fill the disk."""
    files = sorted(directory.glob('crash-*.txt'), reverse=True)
    for stale in files[MAX_CRASH_FILES:]:
        stale.unlink(missing_ok=True)


feedback_log = logging.getLogger('reedd.feedback')

MAX_FEEDBACK_BYTES = 256 * 1024
MAX_FEEDBACK_FILES = 200
FEEDBACK_TYPES = {'bug', 'feature', 'other'}


@app.post('/api/feedback', status_code=202, dependencies=[Depends(require_user)])
async def submit_feedback(request: Request, feedback_type: str = 'other'):
    """Accept a bug report or feature request as plain text from the app --
    the same shape as `report_crash` above, deliberately: this is the same
    kind of low-volume, admin-reads-it-directly data, just submitted on
    purpose rather than left behind by a crash. The app builds the whole
    formatted body itself (the reader's own message, which book if any,
    the `Breadcrumbs` trail) and posts it verbatim, same as `CrashReporter`
    already does for crashes.

    `feedback_type` names the file (see `submit_feedback`'s own filename
    below) so an admin can `ls`/grep by type without opening anything --
    an unrecognized value falls back to "other" rather than rejecting the
    submission over it, same "never lose the report" stance `report_crash`
    already takes with a body that fails to decode.
    """
    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail='empty feedback')
    text = body[:MAX_FEEDBACK_BYTES].decode('utf-8', errors='replace')
    kind = feedback_type if feedback_type in FEEDBACK_TYPES else 'other'

    settings = get_settings()
    settings.feedback_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%f')
    path = settings.feedback_dir / f'feedback-{kind}-{stamp}.txt'
    path.write_text(text, encoding='utf-8')

    first_line = next((ln for ln in text.splitlines() if ln.strip()), '(no detail)')
    feedback_log.error('feedback reported (%s): %s', path.name, first_line)

    _prune_feedback(settings.feedback_dir)
    return {'stored': path.name, 'bytes': len(text)}


@app.get('/api/admin/feedback', response_class=PlainTextResponse,
         dependencies=[Depends(require_admin)])
def list_feedback(limit: int = 20):
    """The most recent feedback submissions, newest first, as one plain-text
    blob -- admin-only, unlike crash listing: this is meant for triage, not
    general diagnostics."""
    settings = get_settings()
    if not settings.feedback_dir.is_dir():
        return 'no feedback yet'
    files = sorted(settings.feedback_dir.glob('feedback-*.txt'), reverse=True)[:max(1, min(limit, 100))]
    if not files:
        return 'no feedback yet'
    return '\n\n'.join(
        f'===== {f.name} =====\n{f.read_text(encoding="utf-8", errors="replace")}' for f in files
    )


def _prune_feedback(directory: Path) -> None:
    """Keep the newest MAX_FEEDBACK_FILES; a submission loop must not fill the disk."""
    files = sorted(directory.glob('feedback-*.txt'), reverse=True)
    for stale in files[MAX_FEEDBACK_FILES:]:
        stale.unlink(missing_ok=True)


@app.get('/api/jobs/{job_id}/log', response_class=PlainTextResponse)
def job_log(job_id: str, user: dict = Depends(require_user)):
    get_job(job_id, user)  # 404s on an unknown or invisible id
    path = store().log_path(job_id)
    if not path.is_file():
        raise HTTPException(status_code=404, detail='no log yet; the job has not started')
    return path.read_text(encoding='utf-8', errors='replace')


# -- current user & admin ----------------------------------------------------


@app.get('/api/me')
def me(user: dict = Depends(require_user)):
    return {'user_id': user['user_id'], 'email': user['email'], 'is_admin': user['is_admin']}


class PublicBody(BaseModel):
    public: bool


class InviteBody(BaseModel):
    email: str


@app.get('/api/admin/jobs', dependencies=[Depends(require_admin)])
def admin_list_jobs(limit: int = 50):
    """Every job, unfiltered, with the owner's email joined in for the admin screen."""
    by_id = {u['user_id']: u['email'] for u in users().list()}
    jobs = store().list(limit=max(1, min(limit, 500)))
    return {'jobs': [dict(j, owner_email=by_id.get(j.get('owner'))) for j in jobs]}


@app.post('/api/admin/jobs/{job_id}/public', dependencies=[Depends(require_admin)])
def set_job_public(job_id: str, body: PublicBody):
    _read_or_404(job_id)
    return store().update(job_id, public=body.public)


@app.get('/api/admin/users', dependencies=[Depends(require_admin)])
def admin_list_users():
    return {'users': [{'user_id': u['user_id'], 'email': u['email'],
                        'is_admin': u['is_admin'], 'created_at': u['created_at']}
                       for u in users().list()]}  # token_hash never leaves the server


@app.get('/api/admin/metadata-health', dependencies=[Depends(require_admin)])
def admin_metadata_health():
    """Whether the category/genre lookup (Gemini, see LLM_GENRE_ENRICHMENT.md)
    is currently working -- unlike the sources it replaced, there is no
    second one to quietly fall back to if this breaks, so the admin screen
    surfaces it directly rather than letting "books never get tagged"
    happen with no visible reason why.
    """
    return metadata_health().status()


@app.post('/api/admin/users', status_code=201)
def invite_user(body: InviteBody, admin: dict = Depends(require_admin)):
    if users().find_by_email(body.email):
        raise HTTPException(status_code=409, detail='already invited')
    user, token = users().create(body.email, invited_by=admin['user_id'])

    settings = get_settings()
    email_sent = False
    if invite_configured(settings):
        try:
            send_invite(settings, body.email, token)
            email_sent = True
        except Exception as e:
            # Email is a delivery convenience, not the source of truth -- the
            # account and its token below are real either way, so a bad app
            # password must not block onboarding.
            invite_log.error('invite email to %s failed: %s', body.email, e)

    return {'user': {'user_id': user['user_id'], 'email': user['email'],
                     'is_admin': user['is_admin'], 'created_at': user['created_at']},
            'token': token, 'email_sent': email_sent}


@app.delete('/api/admin/users/{user_id}')
def delete_user(user_id: str, admin: dict = Depends(require_admin)):
    """Revoke a user's access. Their token stops working immediately; jobs
    they own are left alone -- see UserStore.delete's docstring for why.
    """
    if user_id == admin['user_id']:
        # Not a security boundary (an admin could just invite a second admin
        # and delete this one from there) -- purely to stop a slip of the
        # thumb locking someone out of their own admin session.
        raise HTTPException(status_code=400, detail='cannot delete your own account')
    try:
        users().delete(user_id)
    except UserNotFound:
        raise HTTPException(status_code=404, detail='no such user')
    return {'user_id': user_id, 'deleted': True}


@app.get('/download/app')
def download_app():
    """Unauthenticated on purpose: an invitee has no token until after they
    install the app and paste one into Settings."""
    path = get_settings().apk_path
    if not path or not Path(path).is_file():
        raise HTTPException(status_code=404, detail='app download not configured')
    return FileResponse(path, media_type='application/vnd.android.package-archive',
                        filename=Path(path).name)


def _apk_info(path_str: str) -> dict | None:
    path = Path(path_str) if path_str else None
    if path is None or not path.is_file():
        return None
    stat = path.stat()
    return {
        'filename': path.name,
        'bytes': stat.st_size,
        'built_at': datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc).isoformat(),
    }


def _newest_built_apk(build_dir: Path) -> Path | None:
    candidates = list(build_dir.glob('*.apk')) if build_dir.is_dir() else []
    return max(candidates, key=lambda p: p.stat().st_mtime, default=None)


@app.get('/api/admin/apk', dependencies=[Depends(require_admin)])
def admin_apk_status():
    """What invitees would download right now (`live`), versus the newest
    build sitting in `apk_build_dir` waiting to be pushed there (`pending`)
    -- see `push_apk` below for why these two are allowed to differ.
    """
    settings = get_settings()
    return {
        'live': _apk_info(settings.apk_path),
        'pending': _apk_info(str(newest)) if (newest := _newest_built_apk(settings.apk_build_dir)) else None,
    }


@app.post('/api/admin/push-apk', dependencies=[Depends(require_admin)])
def push_apk():
    """Copies the newest build in `apk_build_dir` over `apk_path` -- the
    Admin screen's "Push Update" button.

    A rebuild does not reach `GET /download/app` on its own: every other
    person using this app was invited to it, and them force-updating to
    whatever this machine happens to be mid-testing (or a build that
    doesn't even run) is a worse failure mode than the small extra step of
    pushing deliberately once it is actually ready.
    """
    settings = get_settings()
    if not settings.apk_path:
        raise HTTPException(status_code=400, detail='REEDD_APK_PATH is not configured')
    source = _newest_built_apk(settings.apk_build_dir)
    if source is None:
        raise HTTPException(status_code=404, detail=f'no .apk found in {settings.apk_build_dir}')
    Path(settings.apk_path).parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, settings.apk_path)
    return {'pushed': _apk_info(settings.apk_path)}


# -- local diagnostics dashboard ---------------------------------------------
#
# Not the Android app's own admin API: this is a plain page meant to be typed
# into a browser directly (phone, Chromebook) once on the Tailscale network,
# so it deliberately carries none of the app's own Bearer-token auth -- a
# browser navigating here has no way to attach one. Tailscale's own network
# boundary is the only access control, same trust model already accepted for
# Flower and Netdata themselves, both left at their own unauthenticated
# defaults for the same reason.

FLOWER_PORT = 5555
NETDATA_PORT = 19999


def _file_bytes(path: Path) -> int | None:
    return path.stat().st_size if path.is_file() else None


def _job_storage_info(job: dict) -> dict:
    """One row for the dashboard's storage table -- real, on-disk sizes, not
    just whatever a job's manifest happens to cache. The epub's own size is
    never one of the fields job.json tracks (unlike the audiobook/sync/cover,
    which _describe() writes there on success), so it's stat'd fresh here.
    """
    job_id = job['job_id']
    job_dir = store().job_dir(job_id)
    epub_bytes = _file_bytes(job_dir / job['filename']) if job.get('filename') else None
    audiobook = job.get('audiobook') or {}
    sync = job.get('sync') or {}
    cover = job.get('cover') or {}
    sizes = (epub_bytes, audiobook.get('bytes'), sync.get('bytes'), cover.get('bytes'))
    return {
        'job_id': job_id,
        'title': job.get('title') or job['filename'],
        'author': job.get('author'),
        'status': job['status'],
        'epub_bytes': epub_bytes,
        'audiobook_bytes': audiobook.get('bytes'),
        'sync_bytes': sync.get('bytes'),
        'cover_bytes': cover.get('bytes'),
        'total_bytes': sum(b for b in sizes if b),
    }


@app.get('/admin/dashboard/storage')
def dashboard_storage():
    jobs = store().list(limit=10_000)
    rows = [_job_storage_info(j) for j in jobs]
    return {'jobs': rows, 'total_bytes': sum(r['total_bytes'] for r in rows)}


@app.get('/admin/dashboard', response_class=HTMLResponse)
def dashboard_page():
    return _DASHBOARD_HTML


class LiveTtsStartBody(BaseModel):
    text: str
    voice: str
    device: str = 'auto'  # 'auto' (GPU if visible) or 'cpu' (forced)


@app.post('/admin/live-tts/start')
def live_tts_start(body: LiveTtsStartBody):
    """Kicks off the live-TTS experiment (see app.live_tts_experiment) and
    returns a session id immediately; the dashboard polls
    /admin/live-tts/status/{id} for chunks as they're synthesized.

    Unauthenticated like the rest of this dashboard section -- triggered
    from a plain browser page with no way to attach a Bearer token; the
    Tailscale network boundary is the access control (see this section's
    own header comment).
    """
    if not body.text.strip():
        raise HTTPException(status_code=400, detail='text is empty')
    if body.device not in ('auto', 'cpu'):
        raise HTTPException(status_code=400, detail="device must be 'auto' or 'cpu'")
    return {'session_id': live_tts_experiment.start(body.text, body.voice, body.device)}


@app.get('/admin/live-tts/status/{session_id}')
def live_tts_status(session_id: str):
    result = live_tts_experiment.status(session_id)
    if result is None:
        raise HTTPException(status_code=404, detail='unknown session')
    return result


@app.get('/admin/live-tts/chunk/{session_id}/{index}')
def live_tts_chunk(session_id: str, index: int):
    audio = live_tts_experiment.chunk_audio(session_id, index)
    if audio is None:
        raise HTTPException(status_code=404, detail='chunk not found')
    return Response(content=audio, media_type='audio/wav')


_DASHBOARD_HTML = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>reed-d-book diagnostics</title>
<style>
  :root {{
    --bg: #14161a; --surface: #1c1f25; --border: #2b2f37;
    --ink: #e7e9ec; --ink-soft: #9aa1ab; --accent: #5b9df5;
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; background: var(--bg); color: var(--ink);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
  }}
  header {{
    padding: 14px 16px; border-bottom: 1px solid var(--border);
    display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  }}
  header h1 {{ font-size: 1.05rem; margin: 0; font-weight: 600; }}
  nav {{ display: flex; gap: 6px; margin-left: auto; }}
  nav button {{
    background: none; border: 1px solid var(--border); color: var(--ink-soft);
    padding: 6px 14px; border-radius: 999px; font-size: 0.85rem; cursor: pointer;
  }}
  nav button.active {{ background: var(--accent); border-color: var(--accent); color: #08111f; font-weight: 600; }}
  main {{ height: calc(100vh - 57px); }}
  section {{ display: none; height: 100%; }}
  section.active {{ display: block; }}
  iframe {{ width: 100%; height: 100%; border: 0; }}
  #storage {{ padding: 16px; overflow-y: auto; }}
  table {{ width: 100%; border-collapse: collapse; font-size: 0.9rem; }}
  th, td {{ text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--border); }}
  th {{ color: var(--ink-soft); font-weight: 600; font-size: 0.78rem; text-transform: uppercase; letter-spacing: 0.04em; }}
  td.num {{ text-align: right; font-variant-numeric: tabular-nums; }}
  th.num {{ text-align: right; }}
  .status {{ font-size: 0.78rem; padding: 2px 8px; border-radius: 999px; background: var(--surface); border: 1px solid var(--border); }}
  .status.done {{ color: #6fce8f; }}
  .status.running {{ color: var(--accent); }}
  .status.error {{ color: #f27272; }}
  .total-row td {{ font-weight: 600; border-top: 2px solid var(--border); border-bottom: none; }}
  .loading, .empty {{ color: var(--ink-soft); padding: 24px 0; }}
  #livetts {{ padding: 16px; overflow-y: auto; }}
  #livetts textarea {{
    width: 100%; background: var(--surface); color: var(--ink); border: 1px solid var(--border);
    border-radius: 8px; padding: 10px; font: inherit; resize: vertical;
  }}
  .livetts-row {{ display: flex; align-items: center; gap: 10px; margin-top: 10px; }}
  .livetts-row select, .livetts-row button {{
    background: var(--surface); color: var(--ink); border: 1px solid var(--border);
    border-radius: 6px; padding: 6px 12px; font: inherit;
  }}
  .livetts-row button {{ background: var(--accent); color: #08111f; font-weight: 600; border-color: var(--accent); cursor: pointer; }}
  .livetts-row button:disabled {{ opacity: 0.5; cursor: default; }}
  #livetts-state {{ color: var(--ink-soft); font-size: 0.85rem; }}
  #livetts table {{ margin-top: 16px; }}
</style>
</head>
<body>
<header>
  <h1>reed-d-book diagnostics</h1>
  <nav>
    <button data-tab="storage" class="active">Storage</button>
    <button data-tab="queue">Queue</button>
    <button data-tab="system">System</button>
    <button data-tab="livetts">Live TTS</button>
  </nav>
</header>
<main>
  <section id="storage" class="active">
    <div id="storage-content" class="loading">Loading…</div>
  </section>
  <section id="queue"><iframe id="flower-frame"></iframe></section>
  <section id="system"><iframe id="netdata-frame"></iframe></section>
  <section id="livetts">
    <textarea id="livetts-text" rows="6">The old house at the end of the lane had been empty for years, or so everyone in town believed. Sarah had heard the stories, of course; everyone had. But standing at the rusted gate on a gray October afternoon, she felt none of the dread she'd expected. Instead, there was only a quiet curiosity, the kind that pulls a person forward one step at a time.</textarea>
    <div class="livetts-row">
      <select id="livetts-voice"><option>loading voices…</option></select>
      <select id="livetts-device">
        <option value="auto">GPU (if available)</option>
        <option value="cpu">CPU (forced)</option>
      </select>
      <button id="livetts-start">Start</button>
      <span id="livetts-state"></span>
    </div>
    <audio id="livetts-audio"></audio>
    <table>
      <thead><tr>
        <th class="num">#</th><th class="num">Chars</th><th class="num">Duration</th>
        <th class="num">Ready at</th><th class="num">Lead</th>
      </tr></thead>
      <tbody id="livetts-rows"></tbody>
    </table>
  </section>
</main>
<script>
  const host = location.hostname;
  document.getElementById('flower-frame').src = `http://${{host}}:{FLOWER_PORT}/`;
  document.getElementById('netdata-frame').src = `http://${{host}}:{NETDATA_PORT}/`;

  for (const btn of document.querySelectorAll('nav button')) {{
    btn.addEventListener('click', () => {{
      for (const b of document.querySelectorAll('nav button')) b.classList.remove('active');
      for (const s of document.querySelectorAll('main section')) s.classList.remove('active');
      btn.classList.add('active');
      document.getElementById(btn.dataset.tab).classList.add('active');
    }});
  }}

  function fmtBytes(n) {{
    if (n === null || n === undefined) return '—';
    const units = ['B', 'KB', 'MB', 'GB'];
    let i = 0;
    while (n >= 1024 && i < units.length - 1) {{ n /= 1024; i++; }}
    return `${{n.toFixed(i === 0 ? 0 : 1)}} ${{units[i]}}`;
  }}

  fetch('/admin/dashboard/storage')
    .then(r => r.json())
    .then(data => {{
      const el = document.getElementById('storage-content');
      if (!data.jobs.length) {{
        el.innerHTML = '<div class="empty">No jobs yet.</div>';
        return;
      }}
      const rows = data.jobs.map(j => `
        <tr>
          <td>${{j.title}}${{j.author ? ` <span style="color:var(--ink-soft)">— ${{j.author}}</span>` : ''}}</td>
          <td><span class="status ${{j.status}}">${{j.status}}</span></td>
          <td class="num">${{fmtBytes(j.epub_bytes)}}</td>
          <td class="num">${{fmtBytes(j.audiobook_bytes)}}</td>
          <td class="num">${{fmtBytes(j.sync_bytes)}}</td>
          <td class="num">${{fmtBytes(j.total_bytes)}}</td>
        </tr>`).join('');
      el.innerHTML = `
        <table>
          <thead><tr>
            <th>Book</th><th>Status</th><th class="num">Epub</th>
            <th class="num">Audiobook</th><th class="num">Sync</th><th class="num">Total</th>
          </tr></thead>
          <tbody>${{rows}}
            <tr class="total-row">
              <td colspan="5">${{data.jobs.length}} jobs</td>
              <td class="num">${{fmtBytes(data.total_bytes)}}</td>
            </tr>
          </tbody>
        </table>`;
    }})
    .catch(() => {{
      document.getElementById('storage-content').innerHTML =
        '<div class="empty">Could not load job storage data.</div>';
    }});

  // -- Live TTS experiment -----------------------------------------------
  // Whether Pocket TTS can stay ahead of playback synthesizing sentence by
  // sentence, instead of converting a whole book upfront: Start kicks off
  // /admin/live-tts/start, then polls /admin/live-tts/status for chunks as
  // they're synthesized and queues each one to play in order as soon as it
  // exists -- "Lead" is how far synthesis was ahead (positive) or behind
  // (negative) of where continuous playback, started at t=0, would have
  // already reached by the time that chunk was ready.
  (function () {{
    const audio = document.getElementById('livetts-audio');
    const startBtn = document.getElementById('livetts-start');
    const stateEl = document.getElementById('livetts-state');
    const rowsEl = document.getElementById('livetts-rows');
    let sessionId = null, pollTimer = null, seenChunks = 0, cumulativeDuration = 0;
    let audioQueue = [], isPlaying = false;

    fetch('/api/voices').then(r => r.json()).then(data => {{
      const sel = document.getElementById('livetts-voice');
      sel.innerHTML = data.voices.map(v =>
        `<option value="${{v}}" ${{v === data.default ? 'selected' : ''}}>${{v}}</option>`).join('');
    }}).catch(() => {{}});

    function playNext() {{
      if (isPlaying || audioQueue.length === 0) return;
      isPlaying = true;
      audio.src = `/admin/live-tts/chunk/${{sessionId}}/${{audioQueue.shift()}}`;
      audio.play().catch(() => {{ isPlaying = false; }});
    }}
    audio.addEventListener('ended', () => {{ isPlaying = false; playNext(); }});

    function poll() {{
      fetch(`/admin/live-tts/status/${{sessionId}}`).then(r => r.json()).then(data => {{
        for (let i = seenChunks; i < data.chunks.length; i++) {{
          const c = data.chunks[i];
          const lead = cumulativeDuration - c.ready_at_s;
          const row = document.createElement('tr');
          row.innerHTML = `<td class="num">${{c.index + 1}}</td><td class="num">${{c.chars}}</td>` +
            `<td class="num">${{c.duration_s.toFixed(2)}}s</td><td class="num">${{c.ready_at_s.toFixed(2)}}s</td>` +
            `<td class="num" style="color:${{lead >= 0 ? '#6fce8f' : '#f27272'}}">` +
            `${{lead >= 0 ? '+' : ''}}${{lead.toFixed(2)}}s</td>`;
          rowsEl.appendChild(row);
          cumulativeDuration += c.duration_s;
          audioQueue.push(c.index);
        }}
        seenChunks = data.chunks.length;
        playNext();
        const label = data.device === 'cpu' ? 'CPU' : 'GPU (auto)';
        if (data.status !== 'running') {{
          stateEl.textContent = data.status === 'error' ? `error: ${{data.error}}` : `done (${{label}})`;
          clearInterval(pollTimer);
          startBtn.disabled = false;
        }} else {{
          stateEl.textContent = `synthesizing on ${{label}}…`;
        }}
      }});
    }}

    startBtn.addEventListener('click', () => {{
      const text = document.getElementById('livetts-text').value;
      const voice = document.getElementById('livetts-voice').value;
      const device = document.getElementById('livetts-device').value;
      if (!text.trim()) return;
      startBtn.disabled = true;
      stateEl.textContent = 'starting…';
      rowsEl.innerHTML = '';
      seenChunks = 0;
      cumulativeDuration = 0;
      audioQueue = [];
      isPlaying = false;
      audio.pause();
      clearInterval(pollTimer);
      fetch('/admin/live-tts/start', {{
        method: 'POST',
        headers: {{'Content-Type': 'application/json'}},
        body: JSON.stringify({{text, voice, device}}),
      }}).then(r => r.json()).then(data => {{
        sessionId = data.session_id;
        stateEl.textContent = 'synthesizing…';
        pollTimer = setInterval(poll, 400);
      }});
    }});
  }})();
</script>
</body>
</html>
"""
