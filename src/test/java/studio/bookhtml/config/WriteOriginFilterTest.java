package studio.bookhtml.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class WriteOriginFilterTest {
    private MockHttpServletRequest request(String method) {
        var r = new MockHttpServletRequest(method, "/api/books");
        r.setServerName("127.0.0.1"); r.setServerPort(18765);
        r.setRemoteAddr("127.0.0.1"); r.setLocalAddr("127.0.0.1");
        return r;
    }
    private boolean allowed(MockHttpServletRequest r) throws Exception { return allowed(r, new WriteOriginFilter()); }
    private boolean allowed(MockHttpServletRequest r, WriteOriginFilter filter) throws Exception {
        var passed = new AtomicBoolean();
        var response = new MockHttpServletResponse();
        filter.doFilter(r, response, (a,b) -> passed.set(true));
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
        for (String invalid : new String[]{"http://user@localhost", "file://localhost", "http://localhost/path", "http://localhost?x=1", "http://localhost#x"})
            assertFalse(WriteOriginFilter.isLocal(invalid));
    }
    @Test void schemeAndLoopbackAliasesCannotBypassExactOrigin() throws Exception {
        for (String invalid : new String[]{"https://127.0.0.1:18765", "http://localhost:18765", "http://127.0.0.1:18765/", "http://127.0.0.1:0"}) {
            var r = request("POST"); r.addHeader("Origin", invalid); assertFalse(allowed(r), invalid);
        }
        var https = request("POST"); https.setScheme("https"); https.setServerPort(443);
        https.addHeader("Origin", "https://127.0.0.1"); assertTrue(allowed(https));
    }
    @Test void explicitLanBindPreservesSameOriginAccessWithoutTrustingArbitraryHosts() throws Exception {
        var filter = new WriteOriginFilter();
        ReflectionTestUtils.setField(filter, "bindAddress", "0.0.0.0");
        var r = request("POST"); r.setServerName("192.168.1.20"); r.setLocalAddr("192.168.1.20"); r.setRemoteAddr("192.168.1.99");
        r.addHeader("Origin", "http://192.168.1.20:18765"); assertTrue(allowed(r, filter));
        for (String origin : new String[]{"http://127.0.0.1:18765", "http://192.168.1.20:8000", "https://192.168.1.20:18765", "https://evil.example"}) {
            r.removeHeader("Origin"); r.addHeader("Origin", origin); assertFalse(allowed(r, filter));
        }
        r.removeHeader("Origin"); r.setServerName("rebind.example"); assertFalse(allowed(r, filter));
        r.setServerName("MacBook.local"); r.addHeader("Origin", "http://MacBook.local:18765");
        ReflectionTestUtils.setField(filter, "allowedHosts", "MacBook.local"); assertTrue(allowed(r, filter));
        r.addHeader("Sec-Fetch-Site", "cross-site"); assertFalse(allowed(r, filter));
    }
    @Test void ipv6LanDestinationWorksWithoutDnsLookups() throws Exception {
        var filter = new WriteOriginFilter(); ReflectionTestUtils.setField(filter, "bindAddress", "::");
        var r = request("POST"); r.setServerName("[fe80::1]"); r.setLocalAddr("fe80::1"); r.setRemoteAddr("fe80::2");
        r.addHeader("Origin", "http://[fe80::1]:18765"); assertTrue(allowed(r, filter));
    }
}
