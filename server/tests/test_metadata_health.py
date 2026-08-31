# -*- coding: utf-8 -*-
import unittest

from app.metadata_health import MetadataHealth


class MetadataHealthTest(unittest.TestCase):
    def setUp(self):
        import tempfile
        self.tmp = tempfile.TemporaryDirectory()
        self.health = MetadataHealth(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_server_that_has_never_attempted_a_lookup_reports_ok(self):
        status = self.health.status()
        self.assertTrue(status['ok'])
        self.assertIsNone(status['last_error'])
        self.assertIsNone(status['last_success_at'])

    def test_a_recorded_failure_flips_ok_to_false(self):
        self.health.record_failure('connection refused')
        status = self.health.status()
        self.assertFalse(status['ok'])
        self.assertEqual(status['last_error'], 'connection refused')
        self.assertIsNotNone(status['last_error_at'])

    def test_a_success_after_a_failure_clears_it(self):
        self.health.record_failure('connection refused')
        self.health.record_success()
        status = self.health.status()
        self.assertTrue(status['ok'])
        self.assertIsNone(status['last_error'])
        self.assertIsNone(status['last_error_at'])
        self.assertIsNotNone(status['last_success_at'])

    def test_a_later_failure_overwrites_an_earlier_one(self):
        self.health.record_failure('first problem')
        self.health.record_failure('second problem')
        self.assertEqual(self.health.status()['last_error'], 'second problem')

    def test_state_persists_across_instances(self):
        # A new MetadataHealth(same data_dir) is what GET /api/admin/
        # metadata-health actually constructs on every request -- this is
        # the property that makes that work.
        self.health.record_failure('boom')
        reloaded = MetadataHealth(self.tmp.name)
        self.assertFalse(reloaded.status()['ok'])


if __name__ == '__main__':
    unittest.main()
