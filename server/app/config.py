# -*- coding: utf-8 -*-
"""Server settings, all overridable by environment variable.

Every name is prefixed with ``REEDD_``, so ``data_dir`` comes from
``REEDD_DATA_DIR``.  Defaults are chosen so that ``uvicorn app.main:app`` and
``celery -A app.tasks worker`` both work with no configuration at all, as long
as Redis is listening on localhost.
"""

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

ENV_PREFIX = 'REEDD_'

DEFAULT_REDIS = 'redis://127.0.0.1:6379'


def _env(name, default=None):
    return os.environ.get(ENV_PREFIX + name, default)


def _env_bool(name, default=False):
    raw = _env(name)
    if raw is None:
        return default
    return raw.strip().lower() in ('1', 'true', 'yes', 'on')


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    broker_url: str
    result_backend: str
    default_engine: str
    default_voice: str
    default_speed: float
    max_upload_bytes: int
    keep_intermediate: bool
    conversion_workers: int | None
    smtp_host: str
    smtp_port: int
    smtp_user: str
    smtp_app_password: str
    smtp_from: str
    public_server_url: str
    apk_path: str

    @property
    def jobs_dir(self) -> Path:
        return self.data_dir / 'jobs'

    @property
    def crashes_dir(self) -> Path:
        """Crash reports posted by the app.

        The app cannot show its own stack trace after it has died, and the
        emulator does not run on this machine, so the server is the most
        convenient place for a phone to leave one.
        """
        return self.data_dir / 'crashes'

    @property
    def voice_samples_dir(self) -> Path:
        """Cached preview clips for the app's voice browser, one per voice --
        see `GET /api/voices/{voice}/sample` in app/main.py. Generated once,
        on first request, and kept forever after; never cleaned up
        automatically, but each clip is one short sentence, so the total is
        negligible next to a single job's audiobook.
        """
        return self.data_dir / 'voice_samples'


def load_settings() -> Settings:
    """Build a Settings from the current environment (not cached)."""
    here = Path(__file__).resolve().parent.parent
    return Settings(
        data_dir=Path(_env('DATA_DIR', str(here / 'data'))).expanduser(),
        broker_url=_env('BROKER_URL', f'{DEFAULT_REDIS}/0'),
        result_backend=_env('RESULT_BACKEND', f'{DEFAULT_REDIS}/1'),
        default_engine=_env('DEFAULT_ENGINE', 'pocket_tts'),
        default_voice=_env('DEFAULT_VOICE', 'af_heart'),
        default_speed=float(_env('DEFAULT_SPEED', '1.0')),
        # Epubs are text; 200 MB is already absurdly generous and stops a bad
        # client from filling the Pocket 4's disk with a single request.
        max_upload_bytes=int(_env('MAX_UPLOAD_BYTES', str(200 * 1024 * 1024))),
        # The per-chapter .wav files dwarf the .m4b (roughly 20x), so they are
        # deleted once the audiobook exists unless you are debugging.
        keep_intermediate=_env_bool('KEEP_INTERMEDIATE', False),
        # None means audiblez picks its own default (roughly half the CPUs, see
        # audiblez.core.resolve_worker_count) -- unset unless a run has shown a
        # different number works better for this machine's RAM/thermals.
        conversion_workers=int(_env('CONVERSION_WORKERS')) if _env('CONVERSION_WORKERS') else None,
        # Gmail's SMTP relay, used to send invite emails (see app/mailer.py).
        # Empty user/password just means invites aren't emailed -- the token
        # is still returned from POST /api/admin/users so it can be
        # hand-delivered instead.
        smtp_host=_env('SMTP_HOST', 'smtp.gmail.com'),
        smtp_port=int(_env('SMTP_PORT', '587')),
        smtp_user=_env('SMTP_USER', ''),
        smtp_app_password=_env('SMTP_APP_PASSWORD', ''),
        smtp_from=_env('SMTP_FROM', _env('SMTP_USER', '')),
        # The externally-reachable URL (e.g. a Tailscale Funnel HTTPS URL),
        # only needed to build the /download/app link in invite emails --
        # the Android app has its own default baked in at build time and
        # never needs to be told this.
        public_server_url=_env('PUBLIC_SERVER_URL', ''),
        # Path to the APK served at GET /download/app. Empty disables that
        # route (404) rather than serving nothing silently.
        apk_path=_env('APK_PATH', ''),
    )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return load_settings()
