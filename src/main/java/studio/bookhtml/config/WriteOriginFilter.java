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
            if (origin != null && !isSameOrigin(origin, req.getHeader("Host"))) {
                HttpServletResponse res = (HttpServletResponse) response;
                res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"message\":\"拒绝非本站来源的写入请求\"}"); return;
            }
        }
        chain.doFilter(request, response);
    }
    static boolean isLocal(String origin) {
        try { String h = URI.create(origin).getHost(); return "127.0.0.1".equals(h) || "localhost".equalsIgnoreCase(h) || "[::1]".equals(h) || "::1".equals(h); }
        catch (RuntimeException e) { return false; }
    }

    /**
     * U7-LAN：同源写入放行。手机经局域网 IP/主机名访问时，Origin 与 Host 同源
     * （浏览器同源策略的本来含义）；跨站 Origin 仍拒绝，CSRF 保护不变。
     * 无 Origin 头的非浏览器调用沿用旧行为（放行）。
     */
    static boolean isSameOrigin(String origin, String hostHeader) {
        if (origin == null) return true;
        if (isLocal(origin)) return true;
        try {
            String originHost = URI.create(origin).getHost();
            String requestHost = hostOf(hostHeader);
            return originHost != null && requestHost != null
                    && originHost.equalsIgnoreCase(requestHost);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String hostOf(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) return null;
        String value = hostHeader.strip();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(0, end + 1) : null;
        }
        int colon = value.lastIndexOf(':');
        // 纯 IPv6 无端口（多个冒号）整体视为 host；host:port 取冒号前。
        if (colon >= 0 && value.indexOf(':') != colon) return value;
        return colon >= 0 ? value.substring(0, colon) : value;
    }
}
