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
 *  - LAN_PAIRED: 局域网访问，根据配对令牌进行能力分级（READ / EDIT / PAID / MANAGE）
 *  - TRUSTED_PROXY: 受信任反向代理访问
 * 提供动态配对 PIN、防暴力破解锁定、令牌生命周期管理与细粒度权限校验。
 */
@Service
public class LanPairingService {
    public enum AccessMode {
        LOOPBACK,
        LAN_PAIRED,
        TRUSTED_PROXY
    }

    public enum LanCapability {
        READ,
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
            return Instant.now().isAfter(expiresAt);
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
        return pair(pin, remoteAddress, Set.of(LanCapability.READ, LanCapability.EDIT));
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
                ? Set.of(LanCapability.READ, LanCapability.EDIT)
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
        if (WriteOriginFilter.loopback(remoteAddr)) {
            return AccessMode.LOOPBACK;
        }
        if (isTrustedProxy(remoteAddr)) {
            return AccessMode.TRUSTED_PROXY;
        }
        return AccessMode.LAN_PAIRED;
    }

    /**
     * 检查请求是否有权执行需要指定能力的操作。
     */
    public boolean isAllowed(HttpServletRequest req, LanCapability required) {
        AccessMode mode = determineAccessMode(req);
        if (mode == AccessMode.LOOPBACK) {
            return true;
        }
        if (mode == AccessMode.TRUSTED_PROXY) {
            return true;
        }
        // LAN_PAIRED
        String token = extractToken(req);
        return hasCapability(token, required);
    }

    /**
     * 从请求头或参数中提取配对令牌。
     * 优先检查 X-Lan-Pairing-Token，其次 Authorization: Bearer，再次请求参数 lanToken。
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
        String param = req.getParameter("lanToken");
        if (param != null && !param.isBlank()) {
            return param.strip();
        }
        String pairingParam = req.getParameter("pairingToken");
        if (pairingParam != null && !pairingParam.isBlank()) {
            return pairingParam.strip();
        }
        return null;
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
