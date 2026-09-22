package studio.bookhtml.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class WriteOriginFilterTest {
    private MockHttpServletRequest request(String method) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, "/api/books");
        r.setServerName("127.0.0.1"); r.setServerPort(18765); r.setRemoteAddr("127.0.0.1");
        return r;
    }
    private boolean allowed(MockHttpServletRequest r) throws Exception {
        AtomicBoolean passed = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new WriteOriginFilter().doFilter(r, response, (a,b) -> passed.set(true));
        if (!passed.get()) assertEquals(403, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        return passed.get();
    }
    @Test void samePortAllowedOtherLocalPortDenied() throws Exception {
        var good = request("POST"); good.addHeader("Origin", "http://127.0.0.1:18765"); assertTrue(allowed(good));
        var bad = request("POST"); bad.addHeader("Origin", "http://127.0.0.1:8000"); assertFalse(allowed(bad));
    }
    @Test void rebindAndCrossSiteGetDenied() throws Exception {
        var rebind = request("GET"); rebind.setServerName("rebinding.example"); assertFalse(allowed(rebind));
        var site = request("GET"); site.addHeader("Sec-Fetch-Site", "cross-site"); assertFalse(allowed(site));
        var remote = request("GET"); remote.setRemoteAddr("192.0.2.4"); assertFalse(allowed(remote));
    }
    @Test void localCliStillWorksAndNullOriginDoesNot() throws Exception {
        assertTrue(allowed(request("POST")));
        var opaque = request("POST"); opaque.addHeader("Origin", "null"); assertFalse(allowed(opaque));
        assertFalse(WriteOriginFilter.isLocal("http://user@localhost"));
        assertFalse(WriteOriginFilter.isLocal("file://localhost"));
        assertFalse(WriteOriginFilter.isLocal("http://localhost/path"));
    }
}
