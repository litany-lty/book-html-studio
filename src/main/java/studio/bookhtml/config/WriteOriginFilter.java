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

/** Local application boundary, not a replacement for authentication on an Internet deployment. */
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
        boolean api = req.getRequestURI().startsWith("/api/");
        if (api) { res.setHeader("Cache-Control", "no-store"); res.setHeader("Pragma", "no-cache"); }
        String origin = req.getHeader("Origin");
        boolean protectedRequest = api || !SAFE.contains(req.getMethod());
        // Reject DNS rebinding even when the attacker omits Origin on a simple GET.
        boolean rebound = api && localHost(req.getRemoteAddr()) && !localHost(req.getServerName());
        boolean crossSite = api && "cross-site".equalsIgnoreCase(req.getHeader("Sec-Fetch-Site"));
        if (protectedRequest && (rebound || crossSite || (origin != null && !sameOrigin(origin, req)))) {
            res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"拒绝跨来源请求\"}"); return;
        }
        // Non-browser localhost CLI requests without Origin retain their existing support.
        chain.doFilter(request, response);
    }
    static boolean sameOrigin(String origin, HttpServletRequest request) {
        try {
            URI uri = URI.create(origin);
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty()) || !isLocal(origin)) return false;
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return false;
            int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(scheme) ? 443 : 80) : uri.getPort();
            return scheme.equalsIgnoreCase(request.getScheme()) && port == request.getServerPort()
                    && normalize(uri.getHost()).equalsIgnoreCase(normalize(request.getServerName()));
        } catch (RuntimeException ignored) { return false; }
    }
    static boolean isLocal(String origin) {
        try { return localHost(URI.create(origin).getHost()); }
        catch (RuntimeException ignored) { return false; }
    }
    private static boolean localHost(String host) {
        String h = normalize(host);
        return Set.of("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1").contains(h.toLowerCase(java.util.Locale.ROOT));
    }
    private static String normalize(String host) { return host == null ? "" : host.replace("[", "").replace("]", ""); }
}
