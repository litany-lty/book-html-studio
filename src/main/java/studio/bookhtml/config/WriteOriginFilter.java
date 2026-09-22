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

/** Same-origin browser writes. This is not authentication for an Internet deployment. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WriteOriginFilter implements Filter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Content-Security-Policy", "frame-ancestors 'none'; object-src 'none'; base-uri 'self'");
        String path = req.getRequestURI();
        if (path.startsWith("/api/")) {
            res.setHeader("Cache-Control", "no-store");
            res.setHeader("Pragma", "no-cache");
            // Local binding alone does not stop DNS rebinding to attacker-controlled Host names.
            if (localHost(req.getLocalAddr()) && !localHost(req.getServerName())) {
                reject(res); return;
            }
            if (!SAFE.contains(req.getMethod())) {
                String origin = req.getHeader("Origin");
                if ((origin != null && !sameOrigin(origin, req)) || "cross-site".equals(req.getHeader("Sec-Fetch-Site"))) {
                    reject(res); return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse res) throws IOException {
        res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"拒绝非同源请求；请从本机服务页面操作\"}");
    }

    static boolean sameOrigin(String value, HttpServletRequest request) {
        try {
            URI origin = URI.create(value);
            String scheme = origin.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return false;
            if (origin.getHost() == null || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())) return false;
            int port = origin.getPort() == -1 ? ("https".equalsIgnoreCase(scheme) ? 443 : 80) : origin.getPort();
            return scheme.equalsIgnoreCase(request.getScheme()) && origin.getHost().equalsIgnoreCase(request.getServerName())
                    && port == request.getServerPort();
        } catch (RuntimeException ignored) { return false; }
    }

    static boolean isLocal(String origin) {
        try { return localHost(URI.create(origin).getHost()); }
        catch (RuntimeException ignored) { return false; }
    }
    private static boolean localHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host) || "[0:0:0:0:0:0:0:1]".equals(host);
    }
}
