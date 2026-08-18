import unittest
from unittest import mock

from audiblez.core import resolve_worker_count


class ResolveWorkerCountTest(unittest.TestCase):
    def test_gpu_always_stays_sequential(self):
        with mock.patch('torch.cuda.is_available', return_value=True):
            self.assertEqual(resolve_worker_count(None, 20), 1)
            self.assertEqual(resolve_worker_count(8, 20), 1)

    def test_default_is_half_the_cpus_capped_at_six(self):
        with mock.patch('torch.cuda.is_available', return_value=False), \
             mock.patch('os.cpu_count', return_value=24):
            self.assertEqual(resolve_worker_count(None, 20), 6)

        with mock.patch('torch.cuda.is_available', return_value=False), \
             mock.patch('os.cpu_count', return_value=4):
            self.assertEqual(resolve_worker_count(None, 20), 2)

    def test_never_more_workers_than_chapters(self):
        with mock.patch('torch.cuda.is_available', return_value=False), \
             mock.patch('os.cpu_count', return_value=24):
            self.assertEqual(resolve_worker_count(None, 3), 3)
            self.assertEqual(resolve_worker_count(None, 0), 1)

    def test_explicit_workers_overrides_the_default(self):
        with mock.patch('torch.cuda.is_available', return_value=False):
            self.assertEqual(resolve_worker_count(2, 20), 2)
            # Still capped by the actual number of chapters to do.
            self.assertEqual(resolve_worker_count(10, 3), 3)


if __name__ == '__main__':
    unittest.main()
