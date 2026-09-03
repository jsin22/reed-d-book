# -*- coding: utf-8 -*-
"""The Celery app, plus the two queue operations the API needs.

Importing this module must stay cheap: the web process imports it, and it must
not drag in torch/pocket_tts/spacy (several seconds and a GB of RAM).  That is why
the API dispatches by task *name* with ``send_task`` instead of importing the
task function -- only the worker imports :mod:`app.tasks`.
"""

from celery import Celery

from .config import get_settings

CONVERT_TASK = 'reedd.convert_epub'

settings = get_settings()

celery_app = Celery('reedd', broker=settings.broker_url, backend=settings.result_backend)
celery_app.conf.update(
    task_serializer='json',
    result_serializer='json',
    accept_content=['json'],
    timezone='UTC',
    enable_utc=True,
    # TTS saturates the machine, so one job at a time; without this a worker
    # would also hoard queued jobs it cannot start for hours.
    worker_prefetch_multiplier=1,
    task_acks_late=False,
    # Conversion has no useful time limit: a full-length book on CPU can run
    # for hours. Progress is visible in the manifest, so a stall is diagnosable.
    task_time_limit=None,
    task_soft_time_limit=None,
    # The manifest is the durable record; Celery results are only a debugging aid.
    result_expires=60 * 60 * 24,
)


def enqueue(job_id) -> str:
    """Queue a conversion. Returns Celery's task id (kept only so we can revoke it)."""
    return celery_app.send_task(CONVERT_TASK, args=[job_id]).id


def revoke(celery_task_id) -> None:
    """Cancel a queued job, killing it if it is already running."""
    if celery_task_id:
        celery_app.control.revoke(celery_task_id, terminate=True, signal='SIGTERM')
