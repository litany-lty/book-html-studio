package studio.bookhtml.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Local-tool boundary: exact browser origin, with explicit LAN binding preserved.
 * This is not user authentication. Public deployment still requires an authenticated proxy.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WriteOriginFilter implements Filter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    @Value("${server.address:127.0.0.1}")
    private String bindAddress = "127.0.0.1";
    @Value("${app.allowed-hosts:}")
    private String allowedHosts = "";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LanPairingService lanPairingService;

    public void setLanPairingService(LanPairingService lanPairingService) {
        this.lanPairingService = lanPairingService;
    }

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "same-origin");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; " +
                "frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        boolean api = req.getRequestURI().startsWith("/api/");
        if (api) {
            res.setHeader("Cache-Control", "no-store");
            res.setHeader("Pragma", "no-cache");
            if (!allowedRequestHost(req) || "cross-site".equals(req.getHeader("Sec-Fetch-Site"))) {
                reject(res, 403, "拒绝非本站来源或未授权主机的请求"); return;
            }
            // LAN pairing capability enforcement
            if (lanPairingService != null && !loopback(req.getRemoteAddr())) {
                String uri = req.getRequestURI();
                if (!uri.equals("/api/lan/pair") && !uri.equals("/api/lan/status")) {
                    if (SAFE.contains(req.getMethod())) {
                        if (lanPairingService.isLanReadRequiresPairing()
                                && !lanPairingService.isAllowed(req, LanPairingService.LanCapability.READ)) {
                            reject(res, 401, "局域网只读访问需先配对"); return;
                        }
                    } else {
                        LanPairingService.LanCapability required;
                        if (uri.startsWith("/api/settings") || (uri.startsWith("/api/books") && "DELETE".equalsIgnoreCase(req.getMethod()))) {
                            required = LanPairingService.LanCapability.MANAGE;
                        } else if (uri.startsWith("/api/jobs") || uri.contains("/dispatch")) {
                            required = LanPairingService.LanCapability.PAID;
                        } else {
                            required = LanPairingService.LanCapability.EDIT;
                        }
                        if (!lanPairingService.isAllowed(req, required)) {
                            String token = LanPairingService.extractToken(req);
                            if (token == null || token.isBlank() || !lanPairingService.isValidToken(token)) {
                                reject(res, 401, "局域网写操作需先通过配对码授权");
                            } else {
                                reject(res, 403, "当前配对凭证缺乏所需权限: " + required);
                            }
                            return;
                        }
                    }
                }
            }
        }
        String origin = req.getHeader("Origin");
        if ((api || !SAFE.contains(req.getMethod())) && origin != null && !sameOrigin(req, origin)) {
            reject(res, 403, "拒绝非同源请求"); return;
        }
        String referer = req.getHeader("Referer");
        if ((api || !SAFE.contains(req.getMethod())) && origin == null && referer != null) {
            if (!sameOriginReferer(req, referer)) {
                reject(res, 403, "拒绝非同源 Referer 请求"); return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean allowedRequestHost(HttpServletRequest req) {
        String host = normalize(req.getServerName());
        if (loopback(bindAddress)) return loopback(host) && loopback(req.getRemoteAddr());
        // LAN access was explicitly selected by BIND. Accept the actual destination
        // interface (no DNS lookup), or an operator-provided hostname, not arbitrary Host.
        if (host.isEmpty()) return false;
        if (host.equals(normalize(req.getLocalAddr())) || host.equals(normalize(bindAddress))) return true;
        if (loopback(host)) return loopback(req.getRemoteAddr());
        return Arrays.stream(allowedHosts.split(",", -1)).limit(32)
                .map(String::strip).map(WriteOriginFilter::normalize)
                .filter(s -> !s.isEmpty() && !s.equals("*"))
                .anyMatch(host::equals);
    }

    private static void reject(HttpServletResponse res) throws IOException {
        reject(res, 403, "拒绝非本站来源或未授权主机的请求");
    }

    private static void reject(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"" + message + "\"}");
    }
    static boolean loopback(String host) {
        return Set.of("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1").contains(normalize(host));
    }
    private static String normalize(String host) {
        return host == null ? "" : host.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
    }
    private static URI originUri(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getPort() >= -1 && uri.getPort() != 0 && uri.getPort() <= 65535 ? uri : null;
        } catch (RuntimeException invalid) { return null; }
    }
    static boolean isLocal(String origin) {
        URI uri = originUri(origin);
        return uri != null && loopback(uri.getHost());
    }
    static boolean sameOrigin(HttpServletRequest request, String origin) {
        URI uri = originUri(origin);
        if (uri == null) return false;
        int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return uri.getScheme().equalsIgnoreCase(request.getScheme())
                && normalize(uri.getHost()).equals(normalize(request.getServerName()))
                && port == request.getServerPort();
    }
    static boolean sameOriginReferer(HttpServletRequest request, String referer) {
        try {
            URI ref = URI.create(referer);
            if (!"http".equalsIgnoreCase(ref.getScheme()) && !"https".equalsIgnoreCase(ref.getScheme())) return false;
            if (ref.getHost() == null || ref.getUserInfo() != null) return false;
            int port = ref.getPort() < 0 ? ("https".equalsIgnoreCase(ref.getScheme()) ? 443 : 80) : ref.getPort();
            return ref.getScheme().equalsIgnoreCase(request.getScheme())
                    && normalize(ref.getHost()).equals(normalize(request.getServerName()))
                    && port == request.getServerPort();
        } catch (Exception ignored) {
            return false;
        }
    }
}
