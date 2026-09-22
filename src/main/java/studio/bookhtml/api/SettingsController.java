package studio.bookhtml.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studio.bookhtml.config.SettingsService;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private static final String TOKEN_ATTRIBUTE = "bookHtmlSettingsCsrf";
    private static final int MAX_BODY_BYTES = 16_384;
    private final SettingsService settings;
    private final ObjectMapper json;

    public SettingsController(SettingsService settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        requireLocal(request);
        HttpSession session = request.getSession(true);
        String token = (String) session.getAttribute(TOKEN_ATTRIBUTE);
        if (token == null) {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(TOKEN_ATTRIBUTE, token);
        }
        Map<String, Object> result = new LinkedHashMap<>(settings.view());
        result.put("csrfToken", token);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache").body(result);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> put(HttpServletRequest request) throws IOException {
        requireLocal(request);
        requireSameOrigin(request);
        HttpSession session = request.getSession(false);
        String expected = session == null ? null : (String) session.getAttribute(TOKEN_ATTRIBUTE);
        String supplied = request.getHeader("X-Settings-Token");
        if (expected == null || supplied == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII)))
            throw new ApiException(HttpStatus.FORBIDDEN, "设置会话令牌无效，请刷新设置页");
        if (request.getContentLengthLong() > MAX_BODY_BYTES)
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "设置请求过大");
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "设置请求过大");
        JsonNode body;
        try { body = json.readTree(bytes); }
        catch (Exception e) { throw new ApiException(HttpStatus.BAD_REQUEST, "设置请求 JSON 无效"); }
        Map<String, Object> result = new LinkedHashMap<>(settings.update(body));
        result.put("csrfToken", expected);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache").body(result);
    }

    private static void requireLocal(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String host = request.getServerName();
        boolean localRemote = "127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
        boolean localHost = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
        if (!localRemote || !localHost) throw new ApiException(HttpStatus.FORBIDDEN, "设置只允许本机访问");
    }

    private static void requireSameOrigin(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(site)) throw new ApiException(HttpStatus.FORBIDDEN, "拒绝跨站设置请求");
        String origin = request.getHeader("Origin");
        if (origin == null) return; // non-browser clients still need the session token
        try {
            URI uri = URI.create(origin);
            if (!request.getScheme().equalsIgnoreCase(uri.getScheme())
                    || !request.getServerName().equalsIgnoreCase(uri.getHost())
                    || request.getServerPort() != (uri.getPort() == -1 ? defaultPort(uri.getScheme()) : uri.getPort())
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                throw new IllegalArgumentException();
        } catch (Exception e) { throw new ApiException(HttpStatus.FORBIDDEN, "拒绝跨源设置请求"); }
    }

    private static int defaultPort(String scheme) { return "https".equalsIgnoreCase(scheme) ? 443 : 80; }
}
