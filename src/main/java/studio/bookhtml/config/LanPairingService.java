package studio.bookhtml.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * G13 / B11: 局域网访问授权与动态配对机制。
 * 支持三种访问模式：
 *  - LOOPBACK: 本地访问，全能力直接放行
 *  - LAN_SHARED: 默认可信私网共享阅读/上传，不发放管理或收费能力
 *  - LAN_PAIRED: 已限制的部署或非直连私网，按现有令牌能力处理
 *  - TRUSTED_PROXY: 受信任反向代理访问
 * 提供动态配对 PIN、防暴力破解锁定、令牌生命周期管理与细粒度权限校验。
 */
@Service
public class LanPairingService {
    public enum AccessMode {
        LOOPBACK,
        LAN_PAIRED,
        LAN_SHARED,
        TRUSTED_PROXY
    }

    public enum LanCapability {
        READ,
        UPLOAD,
        EDIT,
        PAID,
        MANAGE
    }

    public record PairingToken(
            String token,
            Set<LanCapability> capabilities,
            Instant createdAt,
            Instant expiresAt,
            String remoteAddress
    ) {
        public boolean isExpired() {
            return !Instant.now().isBefore(expiresAt);
        }

        public boolean hasCapability(LanCapability capability) {
            if (isExpired()) return false;
            return capabilities.contains(capability) || capabilities.contains(LanCapability.MANAGE);
        }
    }

    public record PairingResult(
            boolean success,
            String token,
            Set<LanCapability> capabilities,
            String message
    ) {
        public static PairingResult success(String token, Set<LanCapability> capabilities) {
            return new PairingResult(true, token, Set.copyOf(capabilities), "配对成功");
        }

        public static PairingResult failure(String message) {
            return new PairingResult(false, null, Set.of(), message);
        }
    }

    public static final String BROWSER_COOKIE = "BOOK_LAN_SESSION";
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofSeconds(60);
    private static final Duration DEFAULT_PIN_TTL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(24);
    private static final int MAX_ACTIVE_TOKENS = 256;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, PairingToken> activeTokens = new ConcurrentHashMap<>();
    private final AtomicInteger failedAttempts = new AtomicInteger(0);

    private String currentPin;
    private Instant pinExpiresAt = Instant.EPOCH;
    private volatile Instant lockUntil = Instant.EPOCH;

    @Value("${app.lan.read-requires-pairing:false}")
    private boolean lanReadRequiresPairing = false;

    @Value("${app.trusted-proxies:}")
    private String trustedProxies = "";

    public LanPairingService() {
        generateNewPin();
    }

    /**
     * 生成新的 6 位数字配对码，重置失败尝试计数。
     */
    public synchronized String generateNewPin() {
        int pinNum = 100_000 + random.nextInt(900_000);
        this.currentPin = String.valueOf(pinNum);
        this.pinExpiresAt = Instant.now().plus(DEFAULT_PIN_TTL);
        this.failedAttempts.set(0);
        this.lockUntil = Instant.EPOCH;
        return currentPin;
    }

    /**
     * 获取当前有效配对码；若已过期则自动重新生成。
     */
    public synchronized String currentPin() {
        if (currentPin == null || Instant.now().isAfter(pinExpiresAt)) {
            return generateNewPin();
        }
        return currentPin;
    }

    /**
     * 允许测试或运维显式指定配对码。
     */
    public synchronized void setPin(String pin) {
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("配对码不能为空");
        }
        this.currentPin = pin.strip();
        this.pinExpiresAt = Instant.now().plus(DEFAULT_PIN_TTL);
        this.failedAttempts.set(0);
        this.lockUntil = Instant.EPOCH;
    }

    /**
     * 执行配对：校验 PIN 码并颁发具备指定能力的令牌。
     */
    public PairingResult pair(String pin, String remoteAddress) {
        return pair(pin, remoteAddress, Set.of(LanCapability.READ, LanCapability.UPLOAD));
    }

    public synchronized PairingResult pair(String pin, String remoteAddress, Set<LanCapability> capabilities) {
        if (Instant.now().isBefore(lockUntil)) {
            long remaining = Duration.between(Instant.now(), lockUntil).toSeconds();
            return PairingResult.failure("配对尝试失败过多，已被锁定，请在 " + remaining + " 秒后重试");
        }

        if (pin == null || pin.isBlank() || currentPin == null || Instant.now().isAfter(pinExpiresAt)) {
            int fails = failedAttempts.incrementAndGet();
            if (fails >= MAX_FAILED_ATTEMPTS) {
                lockUntil = Instant.now().plus(LOCKOUT_DURATION);
            }
            return PairingResult.failure("配对码错误或已失效");
        }

        if (!currentPin.equals(pin.strip())) {
            int fails = failedAttempts.incrementAndGet();
            if (fails >= MAX_FAILED_ATTEMPTS) {
                lockUntil = Instant.now().plus(LOCKOUT_DURATION);
                return PairingResult.failure("配对失败次数超限，锁定 60 秒");
            }
            return PairingResult.failure("配对码错误或已失效");
        }

        // 配对成功：重置锁定
        failedAttempts.set(0);
        lockUntil = Instant.EPOCH;

        // 生成安全令牌
        String token = "lan_" + UUID.randomUUID().toString().replace("-", "")
                + Long.toHexString(random.nextLong());
        Set<LanCapability> granted = capabilities == null || capabilities.isEmpty()
                ? Set.of(LanCapability.READ, LanCapability.UPLOAD)
                : Set.copyOf(capabilities);

        evictExpiredTokens();
        if (activeTokens.size() >= MAX_ACTIVE_TOKENS) {
            // 淘汰最早过期的令牌
            activeTokens.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().expiresAt()))
                    .ifPresent(e -> activeTokens.remove(e.getKey()));
        }

        PairingToken pairingToken = new PairingToken(
                token,
                granted,
                Instant.now(),
                Instant.now().plus(DEFAULT_TOKEN_TTL),
                remoteAddress
        );
        activeTokens.put(token, pairingToken);

        return PairingResult.success(token, granted);
    }

    /**
     * 判断指定令牌是否有效。
     */
    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) return false;
        PairingToken pt = activeTokens.get(token);
        if (pt == null) return false;
        if (pt.isExpired()) {
            activeTokens.remove(token);
            return false;
        }
        return true;
    }

    /**
     * 判断令牌是否持有指定能力。
     */
    public boolean hasCapability(String token, LanCapability capability) {
        if (token == null || token.isBlank() || capability == null) return false;
        PairingToken pt = activeTokens.get(token);
        if (pt == null) return false;
        if (pt.isExpired()) {
            activeTokens.remove(token);
            return false;
        }
        return pt.hasCapability(capability);
    }

    /**
     * 获取令牌当前所持有的全部能力。
     */
    public Set<LanCapability> getCapabilities(String token) {
        if (token == null || token.isBlank()) return Set.of();
        PairingToken pt = activeTokens.get(token);
        if (pt == null || pt.isExpired()) {
            if (pt != null) activeTokens.remove(token);
            return Set.of();
        }
        return pt.capabilities();
    }

    /**
     * 撤销特定令牌。
     */
    public void revokeToken(String token) {
        if (token != null) activeTokens.remove(token);
    }

    /**
     * 撤销所有已颁发的令牌。
     */
    public void revokeAll() {
        activeTokens.clear();
    }

    /**
     * 注册或覆盖令牌（主要供测试或外部显式预配凭据）。
     */
    public void registerToken(String token, Set<LanCapability> capabilities, Duration ttl) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(capabilities, "capabilities");
        Duration duration = ttl != null ? ttl : DEFAULT_TOKEN_TTL;
        activeTokens.put(token, new PairingToken(
                token,
                Set.copyOf(capabilities),
                Instant.now(),
                Instant.now().plus(duration),
                "127.0.0.1"
        ));
    }

    /**
     * 判断请求的访问模式。
     */
    public AccessMode determineAccessMode(HttpServletRequest req) {
        String remoteAddr = req.getRemoteAddr();
        if (WriteOriginFilter.loopbackPeer(remoteAddr)) {
            return AccessMode.LOOPBACK;
        }
        if (isTrustedProxy(remoteAddr)) {
            return AccessMode.TRUSTED_PROXY;
        }
        return sharesWithoutPairing(req) ? AccessMode.LAN_SHARED : AccessMode.LAN_PAIRED;
    }

    /**
     * 检查请求是否有权执行需要指定能力的操作。
     */
    public boolean isAllowed(HttpServletRequest req, LanCapability required) {
        Set<LanCapability> capabilities=effectiveCapabilities(req);
        return required!=null && (capabilities.contains(required) || capabilities.contains(LanCapability.MANAGE));
    }

    /** Single capability projection shared by enforcement and UI; a shared shelf is not shared admin. */
    public Set<LanCapability> effectiveCapabilities(HttpServletRequest req) {
        AccessMode mode=determineAccessMode(req);
        if(mode==AccessMode.LOOPBACK || mode==AccessMode.TRUSTED_PROXY) return Set.of(LanCapability.values());
        Set<LanCapability> capabilities=EnumSet.noneOf(LanCapability.class);
        capabilities.addAll(getCapabilities(extractToken(req)));
        if(!lanReadRequiresPairing) capabilities.add(LanCapability.READ);
        if(mode==AccessMode.LAN_SHARED) capabilities.add(LanCapability.UPLOAD);
        return Set.copyOf(capabilities);
    }

    private boolean sharesWithoutPairing(HttpServletRequest req) {
        // Preserve an operator's explicit restricted deployment. Do not trust forwarding
        // headers to manufacture a LAN peer, or treat a private reverse proxy as a reader.
        return !lanReadRequiresPairing && privatePeer(req.getRemoteAddr())
                && req.getHeader("Forwarded")==null && req.getHeader("X-Forwarded-For")==null
                && req.getHeader("X-Real-IP")==null;
    }

    static boolean privatePeer(String value) {
        if(value==null || value.isEmpty() || value.length()>96) return false;
        byte[] bytes;
        if(value.indexOf(':')<0) {
            bytes=ipv4(value);if(bytes==null)return false;
        } else {
            String literal=value;
            int zone=literal.indexOf('%');
            if(zone>=0) {
                if(!literal.substring(zone+1).matches("[A-Za-z0-9_.-]{1,32}"))return false;
                literal=literal.substring(0,zone);
            }
            if(!literal.matches("[0-9A-Fa-f:.]+"))return false;
            if(literal.indexOf('.')>=0 && ipv4(literal.substring(literal.lastIndexOf(':')+1))==null)return false;
            // Only a validated numeric IPv6 literal reaches this API: never a DNS name.
            try { bytes=java.net.InetAddress.getByName(literal).getAddress(); }
            catch(java.net.UnknownHostException invalid) { return false; }
        }
        if(bytes.length==16)return (bytes[0]&0xfe)==0xfc || (bytes[0]&0xff)==0xfe && (bytes[1]&0xc0)==0x80;
        int first=bytes[0]&0xff,second=bytes[1]&0xff;
        return first==10 || first==172 && second>=16 && second<=31 || first==192 && second==168
                || first==169 && second==254;
    }
    private static byte[] ipv4(String value) {
        String[] parts=value.split("\\.",-1);if(parts.length!=4)return null;
        byte[] bytes=new byte[4];
        for(int i=0;i<4;i++) {
            if(!parts[i].matches("0|[1-9][0-9]{0,2}"))return null;
            int part=Integer.parseInt(parts[i]);if(part>255)return null;bytes[i]=(byte)part;
        }
        return bytes;
    }

    /**
     * 从请求头或参数中提取配对令牌。
     * 优先检查显式请求头，其次 HttpOnly 浏览器 Cookie；不接受 URL 查询凭据。
     */
    public static String extractToken(HttpServletRequest req) {
        String header = req.getHeader("X-Lan-Pairing-Token");
        if (header != null && !header.isBlank()) {
            return header.strip();
        }
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String candidate = auth.substring(7).strip();
            if (!candidate.isBlank()) return candidate;
        }
        // Browser credentials never belong in URLs, logs, localStorage or image links.
        String browserToken=null;
        if(req.getCookies()!=null)for(var cookie:req.getCookies())if(BROWSER_COOKIE.equals(cookie.getName())) {
            if(browserToken!=null) return null; // Ambiguous cookie paths must not choose a principal.
            browserToken=cookie.getValue();
        }
        return browserToken;
    }

    public boolean isLanReadRequiresPairing() {
        return lanReadRequiresPairing;
    }

    public void setLanReadRequiresPairing(boolean lanReadRequiresPairing) {
        this.lanReadRequiresPairing = lanReadRequiresPairing;
    }

    public int activeTokenCount() {
        evictExpiredTokens();
        return activeTokens.size();
    }

    public boolean isLocked() {
        return Instant.now().isBefore(lockUntil);
    }

    public synchronized void resetLock() {
        failedAttempts.set(0);
        lockUntil = Instant.EPOCH;
    }

    private void evictExpiredTokens() {
        activeTokens.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank() || trustedProxies == null || trustedProxies.isBlank()) {
            return false;
        }
        return Arrays.stream(trustedProxies.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .anyMatch(remoteAddr::equalsIgnoreCase);
    }
}
