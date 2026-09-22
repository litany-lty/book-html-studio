package studio.bookhtml.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WriteOriginFilterTest {
    @Test void loopbackOriginsStillAllowed() {
        assertTrue(WriteOriginFilter.isSameOrigin("http://127.0.0.1:18765", "127.0.0.1:18765"));
        assertTrue(WriteOriginFilter.isSameOrigin("http://localhost:18765", "localhost:18765"));
        assertTrue(WriteOriginFilter.isSameOrigin(null, "127.0.0.1:18765"));
    }

    @Test void lanSameOriginAllowed() {
        assertTrue(WriteOriginFilter.isSameOrigin("http://192.168.1.20:18765", "192.168.1.20:18765"));
        assertTrue(WriteOriginFilter.isSameOrigin("http://MacBook.local:18765", "MacBook.local:18765"));
        assertTrue(WriteOriginFilter.isSameOrigin("http://[fe80::1]:18765", "[fe80::1]:18765"));
    }

    @Test void crossSiteOriginsStillRejected() {
        assertFalse(WriteOriginFilter.isSameOrigin("https://evil.example.com", "192.168.1.20:18765"));
        assertFalse(WriteOriginFilter.isSameOrigin("http://192.168.1.99:18765", "192.168.1.20:18765"));
        assertFalse(WriteOriginFilter.isSameOrigin("not-a-uri", "192.168.1.20:18765"));
        // 本机页面沿旧语义放行（内容本就运行在用户机器上）。
        assertTrue(WriteOriginFilter.isSameOrigin("http://127.0.0.1:18765", "192.168.1.20:18765"));
    }
}
