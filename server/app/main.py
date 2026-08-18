# -*- coding: utf-8 -*-
"""FastAPI front end for the conversion server.

The contract the Android app codes against:

    POST   /api/jobs                    upload an .epub  -> 202 {job_id, status}
    GET    /api/jobs/{job_id}           poll             -> {status, progress, eta, ...}
    GET    /api/jobs/{job_id}/audiobook the .m4b         (Range-resumable)
    GET    /api/jobs/{job_id}/sync      the timing .json
    GET    /api/jobs/{job_id}/epub      the original upload (Range-resumable)
    GET    /api/jobs/{job_id}/log       audiblez' output for this job
    DELETE /api/jobs/{job_id}           cancel and/or reclaim the disk
    GET    /api/jobs                    every job, newest first -- doubles as the
                                         library listing; see README.md
    GET    /api/voices                  Kokoro voices, for a picker
    GET    /api/health

Upload returns as soon as the file is on disk; the conversion happens in a
Celery worker.  This process never imports torch or kokoro.
"""

import logging
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, PlainTextResponse

from .audiblez_meta import MAX_SPEED, MIN_SPEED, known_voices
from .celery_app import enqueue, revoke
from .config import get_settings
from .store import (DONE, TERMINAL_STATUSES, JobNotFound, JobStore, UploadTooLarge,
                    looks_like_epub)

app = FastAPI(
    title='reed-d-book conversion server',
    description='Converts .epub to .m4b + read-along timing metadata.',
    version='1.0.0',
)


def store() -> JobStore:
    return JobStore(get_settings().jobs_dir)


def require_token(authorization: str = Header(default='')):
    """No-op unless REEDD_API_TOKEN is set; see config.Settings.api_token."""
    expected = get_settings().api_token
    if not expected:
        return
    if authorization.removeprefix('Bearer ').strip() != expected:
        raise HTTPException(status_code=401, detail='invalid or missing API token')


def get_job(job_id: str) -> dict:
    try:
        return store().read(job_id)
    except JobNotFound:
        raise HTTPException(status_code=404, detail=f'no such job: {job_id}')


@app.get('/api/health')
def health():
    settings = get_settings()
    return {'status': 'ok', 'data_dir': str(settings.data_dir), 'broker': settings.broker_url}


@app.get('/api/voices')
def voices():
    return {'voices': known_voices(), 'default': get_settings().default_voice}


@app.post('/api/jobs', status_code=202, dependencies=[Depends(require_token)])
def create_job(file: UploadFile = File(...),
               voice: str = Form(default=None),
               speed: float = Form(default=None)):
    """Accept an .epub and queue it. Returns immediately with the job's id.

    Deliberately synchronous (`def`, not `async def`) so Starlette runs it in a
    worker thread: the upload is written with blocking I/O in 1 MB chunks, and
    a 200 MB book must not stall the event loop.
    """
    settings = get_settings()
    voice = voice or settings.default_voice
    speed = settings.default_speed if speed is None else speed

    valid = known_voices()
    if valid and voice not in valid:
        raise HTTPException(status_code=400, detail=f'unknown voice: {voice}')
    if not MIN_SPEED <= speed <= MAX_SPEED:
        raise HTTPException(status_code=400,
                            detail=f'speed must be between {MIN_SPEED} and {MAX_SPEED}')
    if not (file.filename or '').lower().endswith('.epub'):
        raise HTTPException(status_code=400, detail='expected a .epub file')

    jobs = store()
    manifest = jobs.create(file.filename, voice, speed)
    job_id = manifest['job_id']
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


@app.get('/api/jobs', dependencies=[Depends(require_token)])
def list_jobs(limit: int = 50):
    return {'jobs': store().list(limit=max(1, min(limit, 500)))}


@app.get('/api/jobs/{job_id}', dependencies=[Depends(require_token)])
def job_status(job_id: str):
    return get_job(job_id)


@app.delete('/api/jobs/{job_id}', dependencies=[Depends(require_token)])
def delete_job(job_id: str):
    """Cancel the job if it is still running, then delete everything it wrote."""
    manifest = get_job(job_id)
    if manifest['status'] not in TERMINAL_STATUSES:
        revoke(manifest.get('celery_task_id'))
    store().delete(job_id)
    return {'job_id': job_id, 'deleted': True}


def _completed_file(job_id: str, kind: str) -> Path:
    manifest = get_job(job_id)
    if manifest['status'] != DONE:
        detail = manifest.get('error') or f'job is {manifest["status"]}'
        # 409, not 404: the id is valid, the result just isn't there yet.
        raise HTTPException(status_code=409, detail=detail)
    entry = manifest.get(kind) or {}
    path = store().output_dir(job_id) / entry.get('file', '')
    if not entry.get('file') or not path.is_file():
        raise HTTPException(status_code=410, detail=f'{kind} is no longer on disk')
    return path


@app.get('/api/jobs/{job_id}/audiobook', dependencies=[Depends(require_token)])
def download_audiobook(job_id: str):
    """The .m4b. FileResponse honours Range, so an interrupted download resumes."""
    path = _completed_file(job_id, 'audiobook')
    return FileResponse(path, media_type='audio/mp4', filename=path.name)


@app.get('/api/jobs/{job_id}/sync', dependencies=[Depends(require_token)])
def download_sync(job_id: str):
    """The text-to-timestamp mapping. Format documented in audiblez/SYNC.md."""
    path = _completed_file(job_id, 'sync')
    return FileResponse(path, media_type='application/json', filename=path.name)


@app.get('/api/jobs/{job_id}/epub', dependencies=[Depends(require_token)])
def download_epub(job_id: str):
    """The originally uploaded .epub.

    Not gated on the job being done -- the upload is written before conversion
    starts and never changes after. This is what lets a device that never
    uploaded this book itself (a different device, a reinstall, wiped app
    storage) adopt a job the server already finished: it can fetch the epub,
    the audiobook and the sync file from a `GET /api/jobs` listing alone,
    without re-uploading or waiting through TTS again. See `GET /api/jobs`
    below and README.md, "Accumulating a library".
    """
    get_job(job_id)  # 404s on an unknown id
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


@app.post('/api/diagnostics/crash', status_code=202, dependencies=[Depends(require_token)])
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
         dependencies=[Depends(require_token)])
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


@app.get('/api/jobs/{job_id}/log', response_class=PlainTextResponse,
         dependencies=[Depends(require_token)])
def job_log(job_id: str):
    get_job(job_id)  # 404s on an unknown id
    path = store().log_path(job_id)
    if not path.is_file():
        raise HTTPException(status_code=404, detail='no log yet; the job has not started')
    return path.read_text(encoding='utf-8', errors='replace')
