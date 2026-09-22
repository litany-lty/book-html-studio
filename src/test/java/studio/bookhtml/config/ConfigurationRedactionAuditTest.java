package studio.bookhtml.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ConfigurationRedactionAuditTest {
    @Test void printableConfigurationNeverIncludesCredentialsOrSignedEndpoints() {
        String secret = "fixture-private-value";
        String signedUrl = "https://example.invalid/ocr?token=" + secret;
        AppProperties app = new AppProperties(null, 0, 0, 0, null, secret, null,
                signedUrl, 0, secret, null, signedUrl, 0, false);
        Object[] configurations = { app,
                new PaddleAiStudioProperties(secret, signedUrl, null, 0, 0, 0),
                new PpOcrProperties(signedUrl, 0) };
        for (Object configuration : configurations) {
            assertFalse(configuration.toString().contains(secret));
            assertFalse(configuration.toString().contains(signedUrl));
            assertTrue(configuration.toString().contains("REDACTED"));
        }
    }
}
