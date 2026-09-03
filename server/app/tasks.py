# -*- coding: utf-8 -*-
"""The Celery worker side: run audiblez on an uploaded epub.

Only the worker imports this module; see :mod:`app.celery_app` for why.
audiblez itself is imported lazily inside the task so that even the worker pays
for torch/pocket_tts/spacy at first job rather than at import, and so tests can
substitute a fake.
"""

import contextlib
import os
import sys
import time
import traceback
from pathlib import Path

from .celery_app import CONVERT_TASK, celery_app
from .config import get_settings
from .cover_lookup import fetch_cover
from .store import DONE, ERROR, RUNNING, JobNotFound, JobStore, utcnow

# Keep the tail of a traceback: enough to diagnose, small enough to poll.
MAX_ERROR_CHARS = 2000


def _load_audiblez():
    """Return audiblez' entry point. Indirection exists so tests can replace it."""
    from audiblez.core import main
    return main


def _store():
    return JobStore(get_settings().jobs_dir)


def output_names(epub_filename):
    """The names audiblez will give its two deliverables for this upload.

    Derived exactly the way audiblez derives them -- from the uploaded
    filename -- rather than by globbing the output folder, which also contains
    the per-chapter ``*.wav.sync.json`` caches.
    """
    return (epub_filename.replace('.epub', '.m4b'),
            epub_filename.replace('.epub', '.json'))


#: Floor between two CORE_PROGRESS-driven disk writes when the whole-number
#: percentage has not moved. Percentage alone used to gate every write, but
#: audiblez now revises its ETA every sentence (see audiblez/BUGS.md BUG-21),
#: and a long book's percentage can sit at a single value -- especially 0 --
#: for well over a minute while the ETA underneath it is moving continuously;
#: without a second, time-based reason to write, that fresher ETA never
#: reached the manifest at all. Small enough that the app's poll (every few
#: seconds while a screen is open) reliably sees a value no older than this.
PROGRESS_SAVE_INTERVAL_SECONDS = 2.0


class _Progress:
    """Translates audiblez' post_event stream into manifest updates.

    CORE_PROGRESS fires once per *sentence*, which for a novel is tens of
    thousands of times, so it only touches the disk when the whole-number
    percentage moves, or -- see PROGRESS_SAVE_INTERVAL_SECONDS -- often
    enough to keep the ETA it also carries from going stale.
    """

    def __init__(self, store, job_id):
        self.store = store
        self.job_id = job_id
        self.percent = -1
        self.chapters_done = 0
        self.last_saved_at = 0.0

    def __call__(self, event, **kwargs):
        if event == 'CORE_PROGRESS':
            stats = kwargs.get('stats')
            # audiblez counts the intro line it prepends to chapter 1 as
            # processed characters but not as total ones, so its percentage
            # overshoots -- a real run of the sample epub reports 105. Clamp it
            # rather than hand the app a progress bar that goes past full.
            percent = min(100, max(0, int(getattr(stats, 'progress', 0) or 0)))
            now = time.monotonic()
            percent_changed = percent != self.percent
            if not percent_changed and now - self.last_saved_at < PROGRESS_SAVE_INTERVAL_SECONDS:
                return
            self.percent = percent
            self.last_saved_at = now
            self.save(progress=percent, eta=getattr(stats, 'eta', None))
        elif event == 'CORE_CHAPTER_FINISHED':
            self.chapters_done += 1
            self.save(chapters_done=self.chapters_done)

    def save(self, **changes):
        try:
            self.store.update(self.job_id, **changes)
        except JobNotFound:
            # The job was deleted while it was running; the task will notice
            # when it tries to record the result. Nothing to report meanwhile.
            pass


@contextlib.contextmanager
def capture_output(path):
    """Send everything this process prints -- subprocesses included -- to `path`.

    ``contextlib.redirect_stdout`` only rebinds ``sys.stdout``, which the ffmpeg
    that audiblez shells out to knows nothing about; a failed encode is the most
    common way to end up without an .m4b, so its complaints have to reach the
    job's log as well. Hence the redirection happens at the file descriptor.
    """
    log = open(path, 'w', encoding='utf-8', buffering=1)
    saved_out, saved_err = os.dup(1), os.dup(2)
    old_stdout, old_stderr = sys.stdout, sys.stderr
    try:
        sys.stdout.flush()
        sys.stderr.flush()
        os.dup2(log.fileno(), 1)
        os.dup2(log.fileno(), 2)
        # Same open file description as fd 1, so Python's line-buffered writes
        # and a subprocess' writes share one offset and stay in order.
        sys.stdout = sys.stderr = log
        yield log
    finally:
        sys.stdout, sys.stderr = old_stdout, old_stderr
        log.flush()
        os.dup2(saved_out, 1)
        os.dup2(saved_err, 2)
        os.close(saved_out)
        os.close(saved_err)
        log.close()


def _describe(path: Path):
    return {'file': path.name, 'bytes': path.stat().st_size}


def _cleanup_intermediates(output_dir: Path):
    """Drop the per-chapter .wav files, which are ~20x the size of the .m4b.

    This forfeits audiblez' resume-from-existing-.wav behaviour, which is the
    right trade here: a job is converted once, and the Pocket 4 has a laptop's
    disk, not a server's.
    """
    for pattern in ('*.wav', '*.wav.sync.json', 'chapters.txt'):
        for path in output_dir.glob(pattern):
            path.unlink(missing_ok=True)


@celery_app.task(name=CONVERT_TASK, bind=True)
def convert_epub(self, job_id):
    """Convert one uploaded epub to .m4b + sync .json (+ cover art, best-effort).
    Status goes to the manifest."""
    store = _store()
    manifest = store.read(job_id)  # raises if the job was deleted before we started
    output_dir = store.output_dir(job_id)
    epub_path = store.job_dir(job_id) / manifest['filename']

    store.update(job_id, status=RUNNING, started_at=utcnow(),
                 celery_task_id=self.request.id, error=None)

    progress = _Progress(store, job_id)
    try:
        audiblez_main = _load_audiblez()
        # audiblez (and ffmpeg) report everything by printing; keep it per job so
        # a failed conversion can be diagnosed without digging through a worker
        # log interleaved with every other job.
        with capture_output(store.log_path(job_id)):
            audiblez_main(
                str(epub_path),
                manifest['voice'],
                False,  # pick_manually: no TTY on a worker
                manifest['speed'],
                output_folder=str(output_dir),
                post_event=progress,
                workers=get_settings().conversion_workers,
                # Jobs created before engine selection existed have no
                # 'engine' key -- none exist on this server any more (every
                # real job's manifest already carries one), but the
                # historically-accurate fallback (kokoro) is no longer even
                # a valid engine (see audiblez.engines), so this defaults to
                # the one that's actually still there instead.
                engine=manifest.get('engine', 'pocket_tts'),
            )
    except Exception:
        _fail(store, job_id, traceback.format_exc())
        raise

    m4b_name, sync_name = output_names(manifest['filename'])
    m4b, sync = output_dir / m4b_name, output_dir / sync_name
    missing = [p.name for p in (m4b, sync) if not p.is_file()]
    if missing:
        _fail(store, job_id,
              f'audiblez finished without producing {", ".join(missing)}. '
              f'See {store.log_path(job_id).name}; a missing ffmpeg is the usual cause.')
        raise RuntimeError(f'job {job_id}: audiblez produced no {", ".join(missing)}')

    if not get_settings().keep_intermediate:
        _cleanup_intermediates(output_dir)

    # audiblez already wrote this if the epub had a cover of its own
    # (core.py's find_cover, muxed into the .m4b by create_m4b); this is
    # only reached for the epub-had-none case. Best-effort and never
    # allowed to fail the job -- see cover_lookup.fetch_cover's own doc.
    cover_path = output_dir / 'cover'
    if not cover_path.is_file():
        fetched = fetch_cover(manifest.get('title'), manifest.get('author'))
        if fetched:
            cover_path.write_bytes(fetched)

    update_fields = dict(status=DONE, finished_at=utcnow(), progress=100, eta=None,
                          audiobook=_describe(m4b), sync=_describe(sync))
    if cover_path.is_file():
        update_fields['cover'] = _describe(cover_path)

    try:
        store.update(job_id, **update_fields)
    except JobNotFound:
        return None  # deleted while converting; the output went with it
    return {'job_id': job_id, 'audiobook': m4b.name, 'sync': sync.name}


def _fail(store, job_id, message):
    try:
        store.update(job_id, status=ERROR, finished_at=utcnow(),
                     error=message[-MAX_ERROR_CHARS:], eta=None)
    except JobNotFound:
        pass
