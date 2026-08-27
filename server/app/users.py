# -*- coding: utf-8 -*-
"""On-disk user store: who can call the API, and who is the admin.

Every account is a bearer token minted at invite time. Unlike jobs (one
directory per job, since there can be thousands), users are few and looked
up on every request, so they live in a single file::

    <data_dir>/users.json

Only a token's sha256 is ever written to disk; the plaintext is returned
once, from `create()`, and is not recoverable afterward. This is the same
shape as a password hash without the complexity of a real KDF, because the
token itself is already 32 bytes of `secrets.token_urlsafe` entropy rather
than something a person chose and might reuse.
"""

import hashlib
import hmac
import json
import os
import secrets
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path

USERS_FILENAME = 'users.json'

# Guards the read-modify-write in create(): unlikely to matter with one
# uvicorn process and a handful of invites, but it's one lock for the
# insurance against two near-simultaneous invites clobbering each other's
# write to the single shared users.json.
_lock = threading.Lock()


class UserNotFound(Exception):
    """No user with that id."""


class EmailAlreadyInvited(Exception):
    """That email (case-insensitive) already has an account."""


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode('utf-8')).hexdigest()


class UserStore:
    def __init__(self, data_dir: Path):
        self.path = Path(data_dir) / USERS_FILENAME

    def _read_all(self) -> list:
        try:
            with open(self.path, encoding='utf-8') as f:
                return json.load(f)
        except FileNotFoundError:
            return []

    def _write_all(self, users: list) -> None:
        """Same atomic-write shape as JobStore.write: temp file + os.replace,
        so a concurrent reader never sees a half-written file."""
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(f'.{USERS_FILENAME}.{os.getpid()}.tmp')
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(users, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self.path)

    def create(self, email: str, is_admin: bool = False, invited_by: str | None = None):
        """Create a user and return ``(record, plaintext_token)``.

        The token is visible here and nowhere else again -- only its hash is
        ever stored. Raises EmailAlreadyInvited if the email is already
        registered (case-insensitive).
        """
        email = email.strip()
        with _lock:
            users = self._read_all()
            if any(u['email'].lower() == email.lower() for u in users):
                raise EmailAlreadyInvited(email)
            token = secrets.token_urlsafe(32)
            user = {
                'user_id': str(uuid.uuid4()),
                'email': email,
                'token_hash': _hash_token(token),
                'is_admin': is_admin,
                'created_at': utcnow(),
                'invited_by': invited_by,
            }
            users.append(user)
            self._write_all(users)
        return user, token

    def find_by_token(self, token: str):
        """The user that token belongs to, or None.

        Hashes the incoming token and compares with hmac.compare_digest --
        never plaintext, never `==` -- so a mistyped token can't be brute
        forced by timing the comparison.
        """
        if not token:
            return None
        target = _hash_token(token)
        for user in self._read_all():
            if hmac.compare_digest(user['token_hash'], target):
                return user
        return None

    def find_by_email(self, email: str):
        email = email.strip().lower()
        for user in self._read_all():
            if user['email'].lower() == email:
                return user
        return None

    def get(self, user_id: str) -> dict:
        for user in self._read_all():
            if user['user_id'] == user_id:
                return user
        raise UserNotFound(user_id)

    def list(self) -> list:
        """Every user, newest first."""
        users = self._read_all()
        users.sort(key=lambda u: u.get('created_at') or '', reverse=True)
        return users

    def delete(self, user_id: str) -> None:
        """Revoke a user's access -- their token stops matching find_by_token
        the moment this returns. Jobs they own are left alone: their `owner`
        field simply stops pointing at anyone, which _visible() in main.py
        already treats as admin-only (the same fallback pre-owner-field jobs
        get), not deleted or reassigned.
        """
        with _lock:
            users = self._read_all()
            remaining = [u for u in users if u['user_id'] != user_id]
            if len(remaining) == len(users):
                raise UserNotFound(user_id)
            self._write_all(remaining)


def _bootstrap_admin(email: str) -> None:
    from .config import get_settings
    store = UserStore(get_settings().data_dir)
    user, token = store.create(email, is_admin=True)
    print(f'Created admin {user["email"]} ({user["user_id"]})')
    print('Token (shown once -- paste it into the app\'s Settings screen):')
    print(token)


if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(description='reed-d-book user management')
    sub = parser.add_subparsers(dest='command', required=True)
    create_admin = sub.add_parser('create-admin',
                                  help="create an admin account and print its token once")
    create_admin.add_argument('email')
    args = parser.parse_args()
    if args.command == 'create-admin':
        _bootstrap_admin(args.email)
