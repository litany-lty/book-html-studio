package studio.bookhtml.config;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** Immutable per-reference AES-256-GCM records. Only settings.json publishes the active reference set. */
public final class EncryptedFileSecretStore implements SecretStore {
    private static final int MAGIC = 0x42534853; // BSHS
    private static final int VERSION = 1;
    private static final int MAX_RECORD_BYTES = 32 * 1024;
    private static final int MAX_FILES = 64;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path settingsDir;
    private final Path directory;
    private final SecretKeySpec key;
    private final String deploymentId;
    private final String keyId;
    private final SecretStore environment;

    public EncryptedFileSecretStore(Path dataDir, byte[] masterKey, String deploymentId, String keyId) {
        this(dataDir, masterKey, deploymentId, keyId, Map.of());
    }
    public EncryptedFileSecretStore(Path dataDir, byte[] masterKey, String deploymentId, String keyId,
                                    Map<String, String> injected) {
        if (masterKey == null || masterKey.length != 32) throw new Failure("INVALID_SECRET_MASTER_KEY");
        if (deploymentId == null || !deploymentId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || keyId == null || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new Failure("INVALID_SECRET_STORE_IDENTITY");
        this.settingsDir = dataDir.toAbsolutePath().normalize().resolve(".settings");
        this.directory = settingsDir.resolve("secrets");
        this.key = new SecretKeySpec(masterKey, "AES");
        this.deploymentId = deploymentId;
        this.keyId = keyId;
        this.environment = SecretStore.environment(injected);
    }
    @Override public Mode mode() { return Mode.ENCRYPTED_FILE; }

    @Override public synchronized String put(String slot, String value) {
        SecretStore.requireSlot(slot);
        if (value == null || value.isBlank() || value.length() > 4096
                || value.chars().anyMatch(c -> c < 32 || c == 127)) throw new Failure("INVALID_SECRET_VALUE");
        byte[] plaintext = null;
        try {
            ensureDirectory();
            try (var entries = Files.list(directory)) {
                if (entries.limit(MAX_FILES).count() >= MAX_FILES) throw new Failure("SECRET_STORE_CAPACITY");
            }
            String ref = "enc:v1:" + slot + ":" + UUID.randomUUID();
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            plaintext = value.getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(ref));
            byte[] encrypted = cipher.doFinal(plaintext);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeInt(MAGIC); out.writeByte(VERSION);
                out.writeUTF(ref); out.writeUTF(keyId); out.write(nonce);
                out.writeInt(encrypted.length); out.write(encrypted);
            }
            Path target = path(ref);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new Failure("SECRET_REFERENCE_COLLISION");
            PrivateSettingsFiles.atomicWrite(target, buffer.toByteArray());
            if (!value.equals(resolve(ref))) throw new Failure("SECRET_WRITE_VERIFICATION_FAILED");
            return ref;
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure("SECRET_STORE_WRITE_FAILED"); }
        finally { if (plaintext != null) Arrays.fill(plaintext, (byte) 0); }
    }

    @Override public synchronized String resolve(String ref) {
        if (ref != null && ref.startsWith("env:v1:")) return environment.resolve(ref);
        byte[] plaintext = null;
        try {
            PrivateSettingsFiles.requirePrivate(settingsDir, true);
            byte[] record = PrivateSettingsFiles.read(path(ref), MAX_RECORD_BYTES);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(record))) {
                if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION
                        || !in.readUTF().equals(ref) || !in.readUTF().equals(keyId))
                    throw new Failure("SECRET_RECORD_IDENTITY_MISMATCH");
                byte[] nonce = in.readNBytes(12);
                int length = in.readInt();
                if (nonce.length != 12 || length < 16 || length > 16_400 || length != in.available())
                    throw new Failure("SECRET_RECORD_INVALID");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
                cipher.updateAAD(aad(ref));
                plaintext = cipher.doFinal(in.readNBytes(length));
                String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString();
                if (value.isBlank() || value.length() > 4096 || value.chars().anyMatch(c -> c < 32 || c == 127))
                    throw new Failure("SECRET_RECORD_INVALID");
                return value;
            }
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure("SECRET_STORE_LOCKED_OR_DAMAGED"); }
        finally { if (plaintext != null) Arrays.fill(plaintext, (byte) 0); }
    }

    @Override public synchronized void remove(String ref) {
        if (ref != null && ref.startsWith("env:v1:")) return;
        try {
            PrivateSettingsFiles.requirePrivate(settingsDir, true);
            PrivateSettingsFiles.requirePrivate(directory, true);
            Path file = path(ref);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                PrivateSettingsFiles.requirePrivate(file, false);
                Files.delete(file);
            }
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure("SECRET_STORE_CLEANUP_FAILED"); }
    }

    @Override public synchronized void retain(Set<String> references) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            PrivateSettingsFiles.requirePrivate(settingsDir, true);
            PrivateSettingsFiles.requirePrivate(directory, true);
            Set<String> keep = new HashSet<>();
            for (String ref : references) if (ref.startsWith("enc:v1:")) keep.add(path(ref).getFileName().toString());
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (!keep.contains(name) && isManagedFile(name)) {
                        PrivateSettingsFiles.requirePrivate(entry, false);
                        Files.delete(entry);
                    }
                }
            }
        } catch (Exception e) { throw new Failure("SECRET_STORE_CLEANUP_FAILED"); }
    }
    private static boolean isManagedFile(String name) {
        for (String slot : SLOTS) if (name.matches(slot + "-[0-9a-f-]{36}\\.secret")) return true;
        return name.matches("private-[A-Za-z0-9-]+\\.tmp");
    }
    private Path path(String ref) {
        if (ref == null) throw new Failure("INVALID_SECRET_REFERENCE");
        String[] parts = ref.split(":", -1);
        if (parts.length != 4) throw new Failure("INVALID_SECRET_REFERENCE");
        SecretStore.requireReferenceForSlot(ref, parts[2]);
        if (!parts[0].equals("enc") || !parts[1].equals("v1")) throw new Failure("INVALID_SECRET_REFERENCE");
        return directory.resolve(parts[2] + "-" + parts[3] + ".secret");
    }
    private byte[] aad(String ref) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC); out.writeByte(VERSION);
            out.writeUTF(deploymentId); out.writeUTF(ref); out.writeUTF(keyId);
        }
        return buffer.toByteArray();
    }
    private void ensureDirectory() throws IOException {
        PrivateSettingsFiles.ensureDirectory(settingsDir);
        PrivateSettingsFiles.ensureDirectory(directory);
    }
    @Override public String toString() { return "EncryptedFileSecretStore[credentials=REDACTED]"; }
}
