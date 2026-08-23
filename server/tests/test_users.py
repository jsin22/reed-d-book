# -*- coding: utf-8 -*-
"""The user store: token issuance, lookup, and the write-a-plaintext-nowhere rule."""

import json

from app.users import EmailAlreadyInvited, UserNotFound, UserStore

from .support import TempDataDirTestCase


class UserStoreTest(TempDataDirTestCase):
    def setUp(self):
        super().setUp()
        self.store = UserStore(self.settings.data_dir)

    def test_create_returns_a_record_and_a_plaintext_token(self):
        user, token = self.store.create('a@example.com')
        self.assertEqual(user['email'], 'a@example.com')
        self.assertFalse(user['is_admin'])
        self.assertTrue(token)
        self.assertNotEqual(user['token_hash'], token)

    def test_the_plaintext_token_is_never_written_to_disk(self):
        user, token = self.store.create('a@example.com')
        raw = (self.settings.data_dir / 'users.json').read_text()
        self.assertNotIn(token, raw)
        self.assertIn(user['token_hash'], raw)

    def test_find_by_token_round_trips(self):
        user, token = self.store.create('a@example.com')
        found = self.store.find_by_token(token)
        self.assertEqual(found['user_id'], user['user_id'])

    def test_find_by_token_rejects_garbage(self):
        self.store.create('a@example.com')
        self.assertIsNone(self.store.find_by_token('not-a-real-token'))
        self.assertIsNone(self.store.find_by_token(''))

    def test_find_by_email_is_case_insensitive(self):
        self.store.create('Person@Example.com')
        self.assertIsNotNone(self.store.find_by_email('person@example.com'))

    def test_email_can_only_be_invited_once(self):
        self.store.create('a@example.com')
        self.assertRaises(EmailAlreadyInvited, self.store.create, 'A@Example.com')

    def test_get_unknown_user_raises(self):
        self.assertRaises(UserNotFound, self.store.get, 'nonsense')

    def test_list_is_newest_first(self):
        # create()'s timestamps have second resolution and two calls in a row
        # can land in the same second, so write distinct created_at values
        # directly to make the order unambiguous (mirrors test_store.py's
        # JobStoreTest.test_list_is_newest_first_and_ignores_junk).
        self.store._write_all([
            {'user_id': 'u1', 'email': 'a@example.com', 'token_hash': 'x', 'is_admin': False,
             'created_at': '2026-01-01T00:00:00+00:00', 'invited_by': None},
            {'user_id': 'u2', 'email': 'b@example.com', 'token_hash': 'y', 'is_admin': False,
             'created_at': '2026-01-02T00:00:00+00:00', 'invited_by': None},
        ])
        self.assertEqual([u['user_id'] for u in self.store.list()], ['u2', 'u1'])

    def test_write_leaves_no_partial_file_behind(self):
        self.store.create('a@example.com')
        leftovers = list(self.settings.data_dir.glob('.users.json*'))
        self.assertEqual(leftovers, [])
        json.loads((self.settings.data_dir / 'users.json').read_text())


if __name__ == '__main__':
    import unittest
    unittest.main()
