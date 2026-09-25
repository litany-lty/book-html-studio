package studio.bookhtml.config;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * G13 / B11: 出站网络目的地址安全策略（SSRF 与外发白名单防护）。
 * 实施：
 *  - 严格协议限制（仅允许 http / https）
 *  - 屏蔽凭据注入（禁止 UserInfo）
 *  - 阻断云服务元数据端点（AWS/Azure 169.254.169.254, GCP metadata.google.internal, 阿里 100.100.100.200）
 *  - 阻断私有网段（RFC 1918 10/8, 172.16/12, 192.168/16）与链路本地/组播广播地址
 *  - 防范 IP 进制混淆（十进制整型、八进制、十六进制、IPv6-Mapped IPv4 等）
 *  - 统一外发白名单治理（Dashscope、百度文心/PP-OCR、MiniMax 及明确配置的自定义节点）
 */
@Component
public class OutboundDestinationPolicy {

    public record ValidationResult(boolean isAllowed, String reason) {
        public static ValidationResult allow() {
            return new ValidationResult(true, "ALLOWED");
        }

        public static ValidationResult block(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    public static final Set<String> DEFAULT_ALLOWED_DOMAINS = Set.of(
            "aliyuncs.com",
            "baidubce.com",
            "baidu.com",
            "minimax.chat",
            "example.com",
            "example.org"
    );

    private static final Pattern NUMERIC_INTEGER_IP = Pattern.compile("^\\d+$");
    private static final Pattern HEX_OR_OCTAL = Pattern.compile("0[xX][0-9a-fA-F]+|0\\d+");

    private final boolean allowLoopback;
    private final boolean allowPrivateNetworks;
    private final Set<String> customAllowedHosts = ConcurrentHashMap.newKeySet();

    public OutboundDestinationPolicy() {
        this(false, false, Collections.emptySet());
    }

    public OutboundDestinationPolicy(boolean allowLoopback, boolean allowPrivateNetworks, Collection<String> customAllowedHosts) {
        this.allowLoopback = allowLoopback;
        this.allowPrivateNetworks = allowPrivateNetworks;
        if (customAllowedHosts != null) {
            for (String host : customAllowedHosts) {
                if (host != null && !host.isBlank()) {
                    this.customAllowedHosts.add(normalizeHost(host));
                }
            }
        }
    }

    public void addAllowedHost(String host) {
        if (host != null && !host.isBlank()) {
            this.customAllowedHosts.add(normalizeHost(host));
        }
    }

    public boolean isAllowed(URI uri) {
        return validate(uri).isAllowed();
    }

    public boolean isAllowed(String url) {
        return validate(url).isAllowed();
    }

    public void checkAllowed(URI uri) {
        ValidationResult result = validate(uri);
        if (!result.isAllowed()) {
            throw new SecurityException("外发请求目的地址被策略阻断: " + result.reason());
        }
    }

    public ValidationResult validate(String url) {
        if (url == null || url.isBlank()) {
            return ValidationResult.block("URL 不能为空");
        }
        if (url.contains("\r") || url.contains("\n") || url.contains("\0")) {
            return ValidationResult.block("URL 包含非法控制字符");
        }
        try {
            URI uri = URI.create(url);
            return validate(uri);
        } catch (Exception e) {
            return ValidationResult.block("URL 格式非法: " + e.getMessage());
        }
    }

    public ValidationResult validate(URI uri) {
        if (uri == null) {
            return ValidationResult.block("URI 不能为空");
        }

        // 1. 协议检查
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return ValidationResult.block("非法协议 scheme: " + scheme + " (仅支持 http 或 https)");
        }

        // 2. 凭据注入检查
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            return ValidationResult.block("禁止在 URI 中携带用户信息凭据 (UserInfo Smuggling)");
        }

        // 3. 主机提取与标准化
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return ValidationResult.block("主机名不能为空");
        }
        String normalizedHost = normalizeHost(host);

        // 4. 检查是否在显式自定义白名单中
        if (customAllowedHosts.contains(normalizedHost)) {
            return ValidationResult.allow();
        }

        // 5. 云元数据端点阻断（SSRF 最高危）
        if (isCloudMetadata(normalizedHost)) {
            return ValidationResult.block("阻断访问云服务元数据端点: " + host);
        }

        // 6. IP 混淆检测（十进制整型、十六进制或八进制格式）
        if (isObfuscatedIp(normalizedHost)) {
            return ValidationResult.block("阻断异常格式与混淆 IP 主机名: " + host);
        }

        // 7. IP 地址性质判断（回环、私网、链路本地、组播等）
        InetAddress inet = tryResolveDirectIp(normalizedHost);
        if (inet != null) {
            ValidationResult ipCheck = validateIpAddress(inet);
            if (!ipCheck.isAllowed()) {
                return ipCheck;
            }
        }

        // 8. 默认云服务白名单匹配
        // This is the exact HTTPS endpoint already shipped in application.properties;
        // do not allow arbitrary *.minimax.cn hosts or downgrade its credentials to HTTP.
        if ("api.minimax.cn".equals(normalizedHost)) {
            return "https".equalsIgnoreCase(scheme) && (uri.getPort()==-1 || uri.getPort()==443)
                    ? ValidationResult.allow() : ValidationResult.block("MiniMax默认端点需要HTTPS标准端口");
        }
        if (isDefaultWhitelistedDomain(normalizedHost)) {
            return ValidationResult.allow();
        }

        // 9. 如果允许本地回环且命中 loopback
        if (allowLoopback && (inet != null && inet.isLoopbackAddress() || "localhost".equals(normalizedHost))) {
            return ValidationResult.allow();
        }

        // 10. 如果允许私网且命中 private
        if (allowPrivateNetworks && inet != null && inet.isSiteLocalAddress()) {
            return ValidationResult.allow();
        }

        return ValidationResult.block("目标主机不在允许的外发域名白名单内: " + host);
    }

    private static String normalizeHost(String host) {
        String h = host.strip();
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h.toLowerCase(Locale.ROOT);
    }

    private boolean isDefaultWhitelistedDomain(String host) {
        for (String domain : DEFAULT_ALLOWED_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCloudMetadata(String host) {
        if ("169.254.169.254".equals(host)) return true;
        if ("100.100.100.200".equals(host)) return true;
        if ("metadata.google.internal".equals(host)) return true;
        if ("metadata".equals(host)) return true;
        if (host.contains("169.254.169.254")) return true;
        return false;
    }

    private boolean isObfuscatedIp(String host) {
        if (NUMERIC_INTEGER_IP.matcher(host).matches()) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length == 4) {
            for (String part : parts) {
                if (HEX_OR_OCTAL.matcher(part).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    private InetAddress tryResolveDirectIp(String host) {
        // 如果是 IPv4 或 IPv6 字面量直接解析，不触发网络 DNS 查询
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private ValidationResult validateIpAddress(InetAddress addr) {
        // IPv6 映射的 IPv4 特别处理
        byte[] raw = addr.getAddress();
        if (raw.length == 16) {
            // Check if ::ffff:a.b.c.d
            boolean isV4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) { isV4Mapped = false; break; }
            }
            if (isV4Mapped && raw[10] == (byte) 0xff && raw[11] == (byte) 0xff) {
                byte[] v4 = new byte[]{raw[12], raw[13], raw[14], raw[15]};
                try {
                    addr = InetAddress.getByAddress(v4);
                } catch (UnknownHostException ignored) {}
            }
        }

        // 云元数据与链路本地 (169.254.0.0/16, fe80::/10)
        if (addr.isLinkLocalAddress()) {
            return ValidationResult.block("阻断链路本地地址 (Link-local): " + addr.getHostAddress());
        }

        // 组播与广播
        if (addr.isMulticastAddress()) {
            return ValidationResult.block("阻断组播地址 (Multicast): " + addr.getHostAddress());
        }

        // 全 0 或全 1 广播
        if (addr.isAnyLocalAddress()) {
            return ValidationResult.block("阻断通配本地地址 (AnyLocal): " + addr.getHostAddress());
        }

        // 回环检查 (127.0.0.0/8, ::1)
        if (addr.isLoopbackAddress()) {
            if (!allowLoopback) {
                return ValidationResult.block("未授权访问本地回环地址: " + addr.getHostAddress());
            }
            return ValidationResult.allow();
        }

        // 私网检查 (10/8, 172.16/12, 192.168/16, fc00::/7)
        if (addr.isSiteLocalAddress()) {
            if (!allowPrivateNetworks) {
                return ValidationResult.block("阻断私有内网地址 (RFC 1918): " + addr.getHostAddress());
            }
            return ValidationResult.allow();
        }

        return ValidationResult.allow();
    }
}
