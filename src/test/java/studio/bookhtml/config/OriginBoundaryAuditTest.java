package studio.bookhtml.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class OriginBoundaryAuditTest {
    private int send(String method, String host, String origin, String site) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/books");
        request.setRemoteAddr("127.0.0.1"); request.setServerName(host); request.setServerPort(18765); request.setScheme("http");
        if (origin != null) request.addHeader("Origin", origin);
        if (site != null) request.addHeader("Sec-Fetch-Site", site);
        MockHttpServletResponse response = new MockHttpServletResponse(); AtomicBoolean reached = new AtomicBoolean();
        new WriteOriginFilter().doFilter(request, response, (req, res) -> reached.set(true));
        assertEquals(response.getStatus() == 200, reached.get());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        return response.getStatus();
    }
    @Test void sameOriginAllowedButDifferentPortOrSchemeDenied() throws Exception {
        assertEquals(200, send("POST", "127.0.0.1", "http://127.0.0.1:18765", "same-origin"));
        assertEquals(403, send("POST", "127.0.0.1", "http://127.0.0.1:3000", "same-site"));
        assertEquals(403, send("POST", "127.0.0.1", "https://127.0.0.1:18765", null));
    }
    @Test void nullOriginAndRebindingAndCrossSiteReadsDenied() throws Exception {
        assertEquals(403, send("POST", "localhost", "null", null));
        assertEquals(403, send("GET", "rebind.example", null, null));
        assertEquals(403, send("GET", "localhost", null, "cross-site"));
    }
    @Test void localNonBrowserRequestsRemainAvailable() throws Exception {
        assertEquals(200, send("POST", "localhost", null, null));
        assertEquals(200, send("GET", "localhost", null, "same-origin"));
    }
}
