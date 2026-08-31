# -*- coding: utf-8 -*-
"""FastAPI front end for the conversion server.

The contract the Android app codes against:

    POST   /api/jobs                    upload an .epub  -> 202 {job_id, status}
                                         (optional title/author form fields feed
                                         a background category/genre lookup --
                                         see SORT_GROUP_LIBRARY.md)
    GET    /api/jobs/{job_id}           poll             -> {status, progress, eta,
                                         category, genres, ...}
    GET    /api/jobs/{job_id}/audiobook the .m4b         (Range-resumable)
    GET    /api/jobs/{job_id}/sync      the timing .json
    GET    /api/jobs/{job_id}/epub      the original upload (Range-resumable)
    GET    /api/jobs/{job_id}/log       audiblez' output for this job
    DELETE /api/jobs/{job_id}           cancel and/or reclaim the disk (owner or admin only)
    GET    /api/jobs                    every job you own, plus every public job,
                                         newest first -- doubles as the library
                                         listing; see README.md
    GET    /api/voices                  voices for one engine (default kokoro), for a picker
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

    GET    /download/app                 unauthenticated: serves the APK, for an
                                          invitee who has no token yet

Upload returns as soon as the file is on disk; the conversion happens in a
Celery worker.  This process never imports torch or kokoro.
"""

import logging
import os
from datetime import datetime, timezone
from pathlib import Path

from fastapi import BackgroundTasks, Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, PlainTextResponse
from pydantic import BaseModel

from .audiblez_meta import (DEFAULT_VOICE_BY_ENGINE, ENGINES, MAX_SPEED, MIN_SPEED,
                            known_voices)
from .book_metadata import LookupUnavailable, lookup as lookup_book_metadata
from .book_metadata_store import BookMetadataStore
from .celery_app import enqueue, revoke
from .config import get_settings
from .mailer import invite_configured, send_invite
from .metadata_health import MetadataHealth
from .store import (DONE, TERMINAL_STATUSES, JobNotFound, JobStore, UploadTooLarge,
                    looks_like_epub)
from .users import UserNotFound, UserStore

app = FastAPI(
    title='read-d-book conversion server',
    description='Converts .epub to .m4b + read-along timing metadata.',
    version='1.0.0',
)

invite_log = logging.getLogger('reedd.mail')
book_metadata_log = logging.getLogger('reedd.book_metadata')


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
    """Voices for one engine. Defaults to the server's default engine (kokoro
    unless REEDD_DEFAULT_ENGINE says otherwise) if `engine` is not given, which
    keeps this endpoint's existing contract for a client that has not been
    updated to know engines exist at all.
    """
    engine = engine or get_settings().default_engine
    if engine not in ENGINES:
        raise HTTPException(status_code=400, detail=f'unknown engine: {engine}')
    default_voice = get_settings().default_voice if engine == 'kokoro' else DEFAULT_VOICE_BY_ENGINE.get(engine)
    return {'voices': known_voices(engine), 'default': default_voice, 'engine': engine}


@app.get('/api/engines')
def engines():
    """Every engine and its voices, for a two-level (engine, then voice) picker."""
    settings = get_settings()
    return {
        'engines': [
            {
                'id': e,
                'voices': known_voices(e),
                'default_voice': settings.default_voice if e == 'kokoro' else DEFAULT_VOICE_BY_ENGINE.get(e),
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
    web process must not import torch/kokoro/pocket_tts at module level (see
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
    tmp = path.with_name(f'.{path.name}.{os.getpid()}.tmp')
    # format='WAV' explicitly: soundfile otherwise infers the format from the
    # filename's extension, and the temp file's real extension is '.tmp', not
    # '.wav' -- it raised TypeError here on the very first real request.
    soundfile.write(tmp, audio, engine_sample_rate(engine), format='WAV')
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


@app.post('/api/jobs', status_code=202)
def create_job(background_tasks: BackgroundTasks,
               file: UploadFile = File(...),
               voice: str = Form(default=None),
               speed: float = Form(default=None),
               engine: str = Form(default=None),
               title: str = Form(default=None),
               author: str = Form(default=None),
               user: dict = Depends(require_user)):
    """Accept an .epub and queue it. Returns immediately with the job's id.

    Deliberately synchronous (`def`, not `async def`) so Starlette runs it in a
    worker thread: the upload is written with blocking I/O in 1 MB chunks, and
    a 200 MB book must not stall the event loop.

    `title`/`author` are optional, sent by the app from metadata it already
    extracted at import time -- used only to kick off a best-effort
    category/genre lookup in the background (see SORT_GROUP_LIBRARY.md),
    not stored or validated beyond that.
    """
    settings = get_settings()
    engine = engine or settings.default_engine
    if engine not in ENGINES:
        raise HTTPException(status_code=400, detail=f'unknown engine: {engine}')
    if voice is None:
        voice = settings.default_voice if engine == 'kokoro' else DEFAULT_VOICE_BY_ENGINE.get(engine)
    speed = settings.default_speed if speed is None else speed

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
                           title=title, author=author)
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
