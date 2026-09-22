package studio.bookhtml.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/** Local, single-owner application boundary; binding publicly is not authentication. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WriteOriginFilter implements Filter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "same-origin");
        res.setHeader("X-Frame-Options", "DENY");
        if (req.getRequestURI().startsWith("/api/")) {
            res.setHeader("Cache-Control", "no-store");
            res.setHeader("Pragma", "no-cache");
            // Host validation also applies to GET: prevents a rebound foreign hostname reading local data.
            if (!loopback(req.getServerName()) || !loopback(req.getRemoteAddr())
                    || "cross-site".equals(req.getHeader("Sec-Fetch-Site"))) {
                reject(res); return;
            }
        }
        String origin = req.getHeader("Origin");
        if (!SAFE.contains(req.getMethod()) && origin != null && !sameOrigin(req, origin)) {
            reject(res); return;
        }
        chain.doFilter(request, response);
    }
    private static void reject(HttpServletResponse res) throws IOException {
        res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"拒绝非本机同源请求\"}");
    }
    static boolean loopback(String host) {
        return host != null && Set.of("127.0.0.1", "localhost", "::1", "[::1]", "0:0:0:0:0:0:0:1")
                .contains(host.toLowerCase(java.util.Locale.ROOT));
    }
    static boolean isLocal(String origin) {
        try {
            URI uri = URI.create(origin);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && loopback(uri.getHost()) && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (RuntimeException invalid) { return false; }
    }
    static boolean sameOrigin(HttpServletRequest request, String origin) {
        if (!isLocal(origin)) return false;
        URI uri = URI.create(origin);
        int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return uri.getScheme().equalsIgnoreCase(request.getScheme())
                && uri.getHost().replace("[", "").replace("]", "").equalsIgnoreCase(
                        request.getServerName().replace("[", "").replace("]", ""))
                && port == request.getServerPort();
    }
}
