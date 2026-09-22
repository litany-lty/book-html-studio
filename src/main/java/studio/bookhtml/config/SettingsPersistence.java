package studio.bookhtml.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** The settings file is the sole commit point; immutable secret records are prepared before it. */
final class SettingsPersistence {
    static final int FORMAT_VERSION = 2;
    private static final int MAX_BYTES = 65_536;
    private static final List<String> SLOT_ORDER = List.of("paddleAccessToken", "ppocrApiKey", "ppocrSecretKey", "qwenApiKey", "jevApiKey");
    private final ObjectMapper json;
    private final Path directory;
    private final Path file;
    private final SecretStore secrets;
    private final AtomicSettingsWriter writer;
    private Map<String, String> references = Map.of();
    private boolean legacy;
    private boolean cleanupPending;

    @FunctionalInterface interface AtomicSettingsWriter { void write(Path target, byte[] bytes) throws IOException; }

    SettingsPersistence(Path dataDir, ObjectMapper json, SecretStore secrets) {
        this(dataDir, json, secrets, PrivateSettingsFiles::atomicWrite);
    }
    SettingsPersistence(Path dataDir, ObjectMapper json, SecretStore secrets, AtomicSettingsWriter writer) {
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.directory = dataDir.toAbsolutePath().normalize().resolve(".settings");
        this.file = directory.resolve("settings.json");
        this.secrets = secrets;
        this.writer = writer;
    }
    SettingsService.State read(SettingsService.State baseline) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) PrivateSettingsFiles.requirePrivate(directory, true);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return baseline;
        JsonNode parsed = json.readTree(PrivateSettingsFiles.read(file, MAX_BYTES));
        if (!(parsed instanceof ObjectNode root)) throw new IOException("INVALID_SETTINGS_DOCUMENT");
        if (!root.has("schemaVersion")) {
            SettingsService.State state = json.treeToValue(root, SettingsService.State.class);
            legacy = true;
            return state;
        }
        JsonNode version = root.remove("schemaVersion");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != FORMAT_VERSION)
            throw new IOException("UNSUPPORTED_SETTINGS_VERSION");
        JsonNode refs = root.remove("secretRefs");
        if (refs == null || !refs.isObject() || refs.size() != SLOT_ORDER.size())
            throw new IOException("INVALID_SETTINGS_REFERENCES");
        Map<String, String> loaded = new LinkedHashMap<>();
        for (String slot : SLOT_ORDER) {
            if (root.has(slot) || !refs.path(slot).isTextual()) throw new IOException("INVALID_SETTINGS_REFERENCES");
            String ref = refs.path(slot).textValue();
            SecretStore.requireReferenceForSlot(ref, slot);
            root.put(slot, ref.isEmpty() ? "" : secrets.resolve(ref));
            loaded.put(slot, ref);
        }
        SettingsService.State state = json.treeToValue(root, SettingsService.State.class);
        references = Map.copyOf(loaded);
        return state;
    }
    /** Run only after all legacy/current settings fields have passed the normal service validation. */
    void finishLoad(SettingsService.State validState) throws IOException {
        if (legacy) write(validState); // Same revision; a storage migration is not a new user operation.
        else if (!references.isEmpty()) cleanup();
    }
    void write(SettingsService.State next) throws IOException {
        PrivateSettingsFiles.ensureDirectory(directory);
        Map<String, String> nextRefs = new LinkedHashMap<>();
        List<String> prepared = new ArrayList<>();
        boolean published = false;
        try {
            ObjectNode document = json.valueToTree(next);
            for (String slot : SLOT_ORDER) {
                JsonNode field = document.remove(slot);
                if (field == null || !field.isTextual()) throw new IOException("INVALID_SECRET_FIELD");
                String value = field.textValue();
                String previous = references.getOrDefault(slot, "");
                String ref;
                if (value.isEmpty()) ref = secrets.mode() == SecretStore.Mode.ENV_ONLY ? secrets.put(slot, value) : "";
                else if (!previous.isEmpty() && secrets.resolve(previous).equals(value)) ref = previous;
                else {
                    ref = secrets.put(slot, value);
                    SecretStore.requireReferenceForSlot(ref, slot);
                    if (ref.isEmpty() || !secrets.resolve(ref).equals(value))
                        throw new SecretStore.Failure("SECRET_WRITE_VERIFICATION_FAILED");
                    if (!references.containsValue(ref)) prepared.add(ref);
                }
                nextRefs.put(slot, ref);
            }
            document.put("schemaVersion", FORMAT_VERSION);
            document.set("secretRefs", json.valueToTree(nextRefs));
            byte[] bytes = json.writeValueAsBytes(document);
            if (bytes.length > MAX_BYTES) throw new IOException("SETTINGS_FILE_TOO_LARGE");
            writer.write(file, bytes);
            published = true;
            references = Map.copyOf(nextRefs);
            legacy = false;
            cleanup(); // Non-critical maintenance must not turn a committed save into a failed save.
        } finally {
            if (!published) {
                for (String ref : prepared) {
                    try { secrets.remove(ref); } catch (RuntimeException ignored) { cleanupPending = true; }
                }
            }
        }
    }
    boolean cleanupPending() { return cleanupPending; }
    private void cleanup() {
        try {
            secrets.retain(new HashSet<>(references.values()));
            cleanupPending = false;
        } catch (RuntimeException ignored) { cleanupPending = true; }
    }
    static Map<String, String> values(SettingsService.State s) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("paddleAccessToken", nonNull(s.paddleAccessToken()));
        values.put("ppocrApiKey", nonNull(s.ppocrApiKey()));
        values.put("ppocrSecretKey", nonNull(s.ppocrSecretKey()));
        values.put("qwenApiKey", nonNull(s.qwenApiKey()));
        values.put("jevApiKey", nonNull(s.jevApiKey()));
        return Map.copyOf(values);
    }
    private static String nonNull(String value) { return value == null ? "" : value; }
}
