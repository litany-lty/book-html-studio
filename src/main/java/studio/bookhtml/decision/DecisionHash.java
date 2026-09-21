package studio.bookhtml.decision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** J01：身份 hash——SHA-256 over 规范序列化 UTF-8 字节。mtime/size 绝不充当内容身份。 */
public final class DecisionHash {
    private DecisionHash() {}

    public static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String of(Object canonicalValue) {
        return sha256Hex(CanonicalJson.write(canonicalValue));
    }
}
