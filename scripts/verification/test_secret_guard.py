import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("secret_guard", Path(__file__).parents[1] / "security/scan_secrets.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

class SecretGuardTest(unittest.TestCase):
    def test_provider_key_is_reported_without_value(self):
        value = b"sk-" + b"B1a2C3d4E5f6G7h8I9j0K1l2M3n4"
        result = guard.findings(b"key=" + value, "fixture.txt")
        self.assertTrue(result)
        self.assertNotIn(value.decode(), str(result))
        self.assertEqual(1, result[0]["line"])

    def test_private_key_and_bearer(self):
        pem = b"-----BEGIN " + b"PRIVATE KEY-----"
        bearer = b"Bearer " + b"M1n2B3v4C5x6Z7a8S9d0F1g2H3j4"
        self.assertTrue(guard.findings(pem, "fixture"))
        self.assertTrue(guard.findings(bearer, "fixture"))

    def test_generic_assignment_and_placeholders(self):
        value = b"P9j4T7x2R6b1M8w3N5y0D4q9"
        self.assertTrue(guard.findings(b'api_key="' + value + b'"', "fixture"))
        self.assertTrue(guard.findings(b'PROVIDER_ACCESS_TOKEN=' + value, "fixture.env"))
        self.assertFalse(guard.findings(b'api_key="test-credential-placeholder-only"', "fixture"))
        self.assertFalse(guard.findings(b'apiKey = state.qwenApiKey()', "fixture"))

    def test_fixture_prefix_does_not_exempt_real_shaped_values(self):
        for prefix in (b'test-', b'your-', b'fake-', b'example-'):
            value = prefix + b'P9j4T7x2R6b1M8w3N5y0D4q9'
            self.assertTrue(guard.findings(b'api_key="' + value + b'"','test.py'))
        for value in guard.REVIEWED_SENTINELS:
            self.assertFalse(guard.findings(b'api_key="' + value + b'"','fixture'))

    def test_shallow_history_is_incomplete_not_pass(self):
        from unittest.mock import patch
        with patch.object(guard, 'git', return_value=b'true\n'):
            self.assertRaises(ValueError,guard.history_scan)

    def test_empty_env_cannot_capture_the_next_setting(self):
        self.assertFalse(guard.findings(b'API_KEY=\nQWEN_MODEL=some-long-non-secret-model-name\n', 'fixture.env'))

    def test_binary_does_not_echo_bytes(self):
        self.assertFalse(guard.findings(b"\0" + bytes(range(100)), "fixture"))

if __name__ == "__main__":
    unittest.main()
