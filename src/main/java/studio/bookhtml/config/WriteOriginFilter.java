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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WriteOriginFilter implements Filter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        if ("/api/config".equals(req.getRequestURI()) || "/api/settings".equals(req.getRequestURI())) {
            HttpServletResponse res = (HttpServletResponse) response;
            res.setHeader("Cache-Control", "no-store");
            res.setHeader("Pragma", "no-cache");
        }
        if (!SAFE.contains(req.getMethod())) {
            String origin = req.getHeader("Origin");
            if (origin != null && !isLocal(origin)) {
                HttpServletResponse res = (HttpServletResponse) response;
                res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"message\":\"拒绝非本机来源的写入请求\"}"); return;
            }
        }
        chain.doFilter(request, response);
    }
    static boolean isLocal(String origin) {
        try { String h = URI.create(origin).getHost(); return "127.0.0.1".equals(h) || "localhost".equalsIgnoreCase(h) || "[::1]".equals(h) || "::1".equals(h); }
        catch (RuntimeException e) { return false; }
    }
}
