"""Precision exceptions must not turn test files or placeholder prefixes into bypasses."""
import importlib.util
from pathlib import Path
import unittest

_spec = importlib.util.spec_from_file_location('redacted_guard_under_test', Path(__file__).with_name('scan_secrets.py'))
_guard = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_guard)


def assignment(value):
    return ('apiKey="' + value + '"').encode('utf-8')


class PreciseSecretSentinelTest(unittest.TestCase):
    def test_only_reviewed_exact_sentinels_are_ignored(self):
        for value in _guard.REVIEWED_SENTINELS:
            self.assertEqual([], _guard.findings(assignment(value), 'test.py'))

    def test_prefix_is_not_an_exemption(self):
        value = 'your-' + 'A8zB2kM3qR7sH5jP' * 3
        self.assertTrue(_guard.findings(assignment(value), 'test.py'))

    def test_extended_sentinel_is_still_inspected(self):
        value = 'your-placeholder-value-not-a-real-secret' + 'Q7mK2zR8pN4vH5dX'
        self.assertTrue(_guard.findings(assignment(value), 'test.py'))

    def test_test_file_not_exempt_and_result_redacted(self):
        value = 'A8zB2kM3qR7sH5jP' * 3
        hits = _guard.findings(assignment(value), 'scripts/security/test_sample.py')
        self.assertTrue(hits)
        self.assertNotIn(value, str(hits))

    def test_provider_rule_is_not_bypassed(self):
        value = 'sk' + '-' + 'A8zB2kM3qR7sH5jP' * 3
        hits = _guard.findings(value.encode('utf-8'), 'test.py')
        self.assertIn('provider-token', [hit['rule'] for hit in hits])


if __name__ == '__main__':
    unittest.main()
