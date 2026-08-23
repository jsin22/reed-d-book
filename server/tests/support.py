# -*- coding: utf-8 -*-
"""Shared test helpers: a throwaway data dir and a minimal valid epub."""

import io
import os
import shutil
import tempfile
import unittest
import zipfile

from app import config

ENV_KEYS = ('REEDD_DATA_DIR', 'REEDD_MAX_UPLOAD_BYTES',
            'REEDD_KEEP_INTERMEDIATE', 'REEDD_DEFAULT_VOICE',
            'REEDD_SMTP_HOST', 'REEDD_SMTP_PORT', 'REEDD_SMTP_USER',
            'REEDD_SMTP_APP_PASSWORD', 'REEDD_SMTP_FROM',
            'REEDD_PUBLIC_SERVER_URL', 'REEDD_APK_PATH')


def epub_bytes(name='chapter1.xhtml', body=b'<html><body><p>Hello.</p></body></html>'):
    """A real zip archive, which is all `looks_like_epub` and these tests need."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w') as z:
        z.writestr('mimetype', 'application/epub+zip')
        z.writestr(name, body)
    return buf.getvalue()


class TempDataDirTestCase(unittest.TestCase):
    """Points the server at a fresh data dir and restores the environment after."""

    env = {}

    def setUp(self):
        self._saved = {k: os.environ.get(k) for k in ENV_KEYS}
        self.data_dir = tempfile.mkdtemp(prefix='reedd-test-')
        os.environ['REEDD_DATA_DIR'] = self.data_dir
        for key, value in self.env.items():
            os.environ[key] = value
        config.get_settings.cache_clear()
        self.settings = config.get_settings()

    def tearDown(self):
        for key, value in self._saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        config.get_settings.cache_clear()
        shutil.rmtree(self.data_dir, ignore_errors=True)
