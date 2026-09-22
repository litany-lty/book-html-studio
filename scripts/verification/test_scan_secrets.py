import unittest
from scan_secrets import findings

class SecretGuardTest(unittest.TestCase):
    def test_detects_without_returning_values(self):
        value = b'sk-' + b'X4a9' * 10
        hits = findings(b'key="' + value + b'"')
        self.assertEqual('provider-key', hits[0]['rule'])
        self.assertNotIn(value.decode(), str(hits))

    def test_private_key_is_rejected(self):
        value = b'-----BEGIN ' + b'PRIVATE KEY-----'
        self.assertTrue(findings(value))

    def test_empty_environment_and_placeholder_are_not_secrets(self):
        self.assertEqual([], findings(b'apiKey=""\nsecretKey="${SECRET_KEY:}"'))
        self.assertEqual([], findings(b'apiKey="your-api-key-replace-with-secret"'))

    def test_literal_long_credential_is_detected(self):
        token = b'A8zB2kM3' * 6
        self.assertTrue(findings(b'accessToken="' + token + b'"'))
