package studio.bookhtml.config;

import java.util.HashMap;
import java.util.Map;

final class EnvironmentSecretStore implements SecretStore {
    private final Map<String, String> values;

    EnvironmentSecretStore(Map<String, String> injected) {
        Map<String, String> copy = new HashMap<>();
        for (String slot : SLOTS) copy.put(SecretStore.environmentReference(slot), injected.getOrDefault(slot, ""));
        values = Map.copyOf(copy);
    }
    @Override public Mode mode() { return Mode.ENV_ONLY; }
    @Override public String resolve(String ref) {
        if (!values.containsKey(ref)) throw new Failure("SECRET_STORE_LOCKED_OR_REFERENCE_INVALID");
        return values.get(ref);
    }
    @Override public String put(String slot, String value) {
        String ref = SecretStore.environmentReference(slot);
        if (!resolve(ref).equals(value)) throw new Failure("SECRET_STORE_ENV_ONLY");
        return ref;
    }
    @Override public void remove(String ref) {
        // Environment injection cannot be mutated by a settings request.
    }
    @Override public String toString() { return "EnvironmentSecretStore[credentials=REDACTED]"; }
}
