# -*- coding: utf-8 -*-
"""On-disk job store.

Each job owns a directory::

    <data_dir>/jobs/<job_id>/
        job.json                 the manifest: everything the poll endpoint returns
        book.epub                the uploaded file (original name, sanitised)
        out/                     audiblez' output folder
            book.m4b             the audiobook
            book.json            the sync mapping (see audiblez/SYNC.md)
            book_chapter_*.wav   intermediate, deleted unless keep_intermediate
        worker.log               audiblez' stdout for this job

The manifest -- not Celery's result backend -- is the source of truth for a
job's status.  Celery results expire (24h by default) and disappear if Redis is
flushed, and an expired id is reported as PENDING, indistinguishable from a job
that never existed.  The Android app may poll a job hours later, after being
closed and reopened, so the state it polls has to outlive both.  Celery remains
the queue proper; it just isn't asked to remember anything.
"""

import json
import os
import re
import shutil
import uuid
from datetime import datetime, timezone
from pathlib import Path

# Job status values.
QUEUED = 'queued'
RUNNING = 'running'
DONE = 'done'
ERROR = 'error'

TERMINAL_STATUSES = (DONE, ERROR)

MANIFEST_NAME = 'job.json'
OUTPUT_DIRNAME = 'out'
LOG_NAME = 'worker.log'

_SAFE_CHARS = re.compile(r'[^A-Za-z0-9._-]+')
_MAX_STEM = 80


class JobNotFound(Exception):
    """No job with that id (or the id isn't a job id at all)."""


class UploadTooLarge(Exception):
    """The uploaded file exceeded max_upload_bytes."""


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def safe_filename(name) -> str:
    """Reduce a client-supplied filename to something safe to write and read back.

    audiblez derives every output path from this name, so it has to survive a
    round trip through the filesystem without escaping the job directory.
    """
    stem = Path(name or '').name  # drops any directory component, including '..'
    if stem.lower().endswith('.epub'):
        stem = stem[:-len('.epub')]
    stem = _SAFE_CHARS.sub('_', stem).strip('._-')[:_MAX_STEM]
    return f'{stem or "book"}.epub'


def is_job_id(job_id) -> bool:
    """True only for ids this store generated, which keeps job_id out of path joins."""
    try:
        return str(uuid.UUID(str(job_id))) == str(job_id)
    except (ValueError, AttributeError, TypeError):
        return False


class JobStore:
    def __init__(self, jobs_dir: Path):
        self.jobs_dir = Path(jobs_dir)

    # -- paths ---------------------------------------------------------------

    def job_dir(self, job_id) -> Path:
        if not is_job_id(job_id):
            raise JobNotFound(job_id)
        return self.jobs_dir / str(job_id)

    def manifest_path(self, job_id) -> Path:
        return self.job_dir(job_id) / MANIFEST_NAME

    def output_dir(self, job_id) -> Path:
        return self.job_dir(job_id) / OUTPUT_DIRNAME

    def log_path(self, job_id) -> Path:
        return self.job_dir(job_id) / LOG_NAME

    def epub_path(self, job_id) -> Path:
        return self.job_dir(job_id) / self.read(job_id)['filename']

    # -- lifecycle -----------------------------------------------------------

    def create(self, filename, voice, speed, engine) -> dict:
        """Register a job and return its manifest. The epub is written separately."""
        job_id = str(uuid.uuid4())
        (self.jobs_dir / job_id / OUTPUT_DIRNAME).mkdir(parents=True, exist_ok=True)
        manifest = {
            'job_id': job_id,
            'status': QUEUED,
            'filename': safe_filename(filename),
            'engine': engine,
            'voice': voice,
            'speed': speed,
            'created_at': utcnow(),
            'started_at': None,
            'finished_at': None,
            'progress': 0,
            'eta': None,
            'chapters_done': 0,
            'error': None,
            'celery_task_id': None,
            'audiobook': None,
            'sync': None,
        }
        self.write(job_id, manifest)
        return manifest

    def exists(self, job_id) -> bool:
        try:
            return self.manifest_path(job_id).is_file()
        except JobNotFound:
            return False

    def read(self, job_id) -> dict:
        path = self.manifest_path(job_id)
        try:
            with open(path, encoding='utf-8') as f:
                return json.load(f)
        except FileNotFoundError as e:
            raise JobNotFound(job_id) from e

    def write(self, job_id, manifest) -> None:
        """Replace the manifest atomically.

        The worker rewrites this while the API is reading it, so a reader must
        never see a half-written file: write a sibling and rename over the top.
        """
        path = self.manifest_path(job_id)
        tmp = path.with_name(f'.{MANIFEST_NAME}.{os.getpid()}.tmp')
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(manifest, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)

    def update(self, job_id, **changes) -> dict:
        manifest = self.read(job_id)
        manifest.update(changes)
        self.write(job_id, manifest)
        return manifest

    def delete(self, job_id) -> None:
        job_dir = self.job_dir(job_id)
        if not job_dir.is_dir():
            raise JobNotFound(job_id)
        shutil.rmtree(job_dir)

    def list(self, limit=50) -> list:
        """Manifests, newest first. Directories without one are ignored."""
        if not self.jobs_dir.is_dir():
            return []
        manifests = []
        for entry in self.jobs_dir.iterdir():
            if not entry.is_dir() or not is_job_id(entry.name):
                continue
            try:
                manifests.append(self.read(entry.name))
            except (JobNotFound, ValueError):
                continue  # being created, or half-deleted
        manifests.sort(key=lambda m: m.get('created_at') or '', reverse=True)
        return manifests[:limit]

    # -- upload --------------------------------------------------------------

    def save_upload(self, job_id, source, max_bytes, chunk_size=1024 * 1024) -> int:
        """Stream an uploaded file to the job directory, refusing oversized ones.

        `source` is anything with a blocking ``read(n)``.  Reading in chunks
        keeps a 300 MB upload from being held in RAM on a handheld PC, and the
        size is enforced as we go rather than trusting Content-Length.
        """
        path = self.job_dir(job_id) / self.read(job_id)['filename']
        written = 0
        with open(path, 'wb') as f:
            while True:
                chunk = source.read(chunk_size)
                if not chunk:
                    break
                written += len(chunk)
                if written > max_bytes:
                    f.close()
                    path.unlink(missing_ok=True)
                    raise UploadTooLarge(written)
                f.write(chunk)
        return written


def looks_like_epub(path) -> bool:
    """Cheap sanity check: an epub is a zip, so it starts with a local file header.

    Catches the common mistakes (a .txt renamed, a truncated upload) before we
    hand the file to a worker that would take minutes to fail on it.
    """
    try:
        with open(path, 'rb') as f:
            return f.read(4) == b'PK\x03\x04'
    except OSError:
        return False
