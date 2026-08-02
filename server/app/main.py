# -*- coding: utf-8 -*-
"""FastAPI front end for the conversion server.

The contract the Android app codes against:

    POST   /api/jobs                    upload an .epub  -> 202 {job_id, status}
    GET    /api/jobs/{job_id}           poll             -> {status, progress, eta, ...}
    GET    /api/jobs/{job_id}/audiobook the .m4b         (Range-resumable)
    GET    /api/jobs/{job_id}/sync      the timing .json
    GET    /api/jobs/{job_id}/log       audiblez' output for this job
    DELETE /api/jobs/{job_id}           cancel and/or reclaim the disk
    GET    /api/jobs                    every job, newest first
    GET    /api/voices                  Kokoro voices, for a picker
    GET    /api/health

Upload returns as soon as the file is on disk; the conversion happens in a
Celery worker.  This process never imports torch or kokoro.
"""

from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
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


@app.get('/api/jobs/{job_id}/log', response_class=PlainTextResponse,
         dependencies=[Depends(require_token)])
def job_log(job_id: str):
    get_job(job_id)  # 404s on an unknown id
    path = store().log_path(job_id)
    if not path.is_file():
        raise HTTPException(status_code=404, detail='no log yet; the job has not started')
    return path.read_text(encoding='utf-8', errors='replace')
