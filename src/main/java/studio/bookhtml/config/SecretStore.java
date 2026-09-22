package studio.bookhtml.config;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/** Server-only credentials. References are identifiers, never reversible wrappers around a key. */
public interface SecretStore {
    Set<String> SLOTS = Set.of("paddleAccessToken", "ppocrApiKey", "ppocrSecretKey", "qwenApiKey", "jevApiKey");
    enum Mode { ENV_ONLY, ENCRYPTED_FILE }

    Mode mode();
    default boolean writable() { return mode() == Mode.ENCRYPTED_FILE; }
    String resolve(String secretRef);
    String put(String slot, String value);
    void remove(String secretRef);
    default boolean isConfigured(String ref) { return ref != null && !ref.isEmpty() && !resolve(ref).isBlank(); }
    /** Called only after the settings reference set has been durably published and validated. */
    default void retain(Set<String> references) { }

    static String environmentReference(String slot) {
        requireSlot(slot);
        return "env:v1:" + slot;
    }
    static void requireSlot(String slot) {
        if (!SLOTS.contains(slot == null ? "" : slot)) throw new Failure("INVALID_SECRET_REFERENCE");
    }
    static void requireReferenceForSlot(String ref, String slot) {
        requireSlot(slot);
        if (ref == null || !(ref.isEmpty() || ref.equals(environmentReference(slot))
                || ref.matches("enc:v1:" + slot + ":[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
            throw new Failure("INVALID_SECRET_REFERENCE");
        }
    }
    static SecretStore environment(Map<String, String> injected) { return new EnvironmentSecretStore(injected); }

    /** No automatic key generation and no plaintext fallback when the selected store is unavailable. */
    static SecretStore fromEnvironment(Path dataDir, Map<String, String> injected, Map<String, String> environment) {
        String selected = environment.getOrDefault("BOOK_SECRET_STORE_MODE", "ENV_ONLY");
        if (selected.equals("ENV_ONLY")) return environment(injected);
        if (!selected.equals("ENCRYPTED_FILE")) throw new Failure("INVALID_SECRET_STORE_MODE");
        byte[] key = null;
        try {
            String encoded = environment.get("BOOK_SECRET_MASTER_KEY");
            if (encoded == null || encoded.length() != 44) throw new Failure("SECRET_MASTER_KEY_REQUIRED");
            key = Base64.getDecoder().decode(encoded);
            if (key.length != 32 || !Base64.getEncoder().encodeToString(key).equals(encoded))
                throw new Failure("INVALID_SECRET_MASTER_KEY");
            String deployment = environment.get("BOOK_SECRET_DEPLOYMENT_ID");
            if (deployment == null || deployment.isBlank()) throw new Failure("SECRET_DEPLOYMENT_ID_REQUIRED");
            return new EncryptedFileSecretStore(dataDir, key, deployment,
                    environment.getOrDefault("BOOK_SECRET_KEY_ID", "primary"), injected);
        } catch (IllegalArgumentException e) {
            throw new Failure("INVALID_SECRET_MASTER_KEY");
        } finally {
            if (key != null) java.util.Arrays.fill(key, (byte) 0);
        }
    }

    /** Deliberately excludes causes, paths, parser fragments and credential values. */
    final class Failure extends IllegalStateException {
        public Failure(String code) { super(code); }
    }
}
