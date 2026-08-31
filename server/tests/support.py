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
            'REEDD_PUBLIC_SERVER_URL', 'REEDD_APK_PATH',
            'REEDD_GEMINI_API_KEY', 'REEDD_GEMINI_MODEL')


def epub_bytes(name='chapter1.xhtml', body=b'<html><body><p>Hello.</p></body></html>'):
    """A real zip archive, which is all `looks_like_epub` and these tests need."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w') as z:
        z.writestr('mimetype', 'application/epub+zip')
        z.writestr(name, body)
    return buf.getvalue()


def epub_with_metadata(title=None, author=None):
    """A real, ebooklib-readable epub with DC title/creator metadata set (or
    left unset) -- for app.epub_meta, which reads OPF metadata that
    `epub_bytes`'s bare zip does not have. Built by round-tripping through
    ebooklib's own writer rather than hand-written OPF XML, so this cannot
    drift from what ebooklib actually produces/expects.
    """
    from ebooklib import epub

    book = epub.EpubBook()
    if title:
        book.set_title(title)
    if author:
        book.add_author(author)
    book.set_language('en')
    chapter = epub.EpubHtml(title='Chapter 1', file_name='chap1.xhtml', lang='en')
    chapter.content = '<html><body><p>Hello.</p></body></html>'
    book.add_item(chapter)
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = ['nav', chapter]

    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, 'book.epub')
        epub.write_epub(path, book)
        with open(path, 'rb') as f:
            return f.read()


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
