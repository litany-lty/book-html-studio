"""Guard tests use constructed synthetic strings, never real credentials."""
import unittest
from scan_secrets import findings

class RedactedGuardTest(unittest.TestCase):
    def test_constructed_provider_token_is_detected_without_returning_value(self):
        value = "sk" + "-" + "AB37xyPQ918jqZpaRCmN67KLpoTU42vw"
        hits = findings("line one\napi=" + value)
        self.assertIn((2, "provider-token"), hits)
        self.assertNotIn(value, str(hits))

    def test_placeholder_is_not_a_credential(self):
        self.assertEqual([], findings('apiKey="your-placeholder-value-not-a-real-secret"'))

    def test_private_key_header_is_detected(self):
        value = "-----BEGIN " + "PRIVATE KEY-----"
        self.assertEqual([(1, "private-key")], findings(value))

if __name__ == "__main__":
    unittest.main()
