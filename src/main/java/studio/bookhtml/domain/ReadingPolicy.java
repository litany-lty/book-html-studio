package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-authoritative reading policy per subject (G06).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReadingPolicy(
        int schemaVersion,
        long policyRevision,
        String subjectId,
        WindowConfig defaultWindow,
        List<ConsentSummary> validConsents,
        long operationEpoch,
        Instant updatedAt
) {
    public record WindowConfig(int preloadBefore, int preloadAfter, String mode) {
        public WindowConfig {
            preloadBefore = Math.max(0, Math.min(10, preloadBefore));
            preloadAfter = Math.max(0, Math.min(10, preloadAfter));
            mode = mode == null ? "AUTO_CURRENT" : mode;
        }

        public static WindowConfig standard() {
            return new WindowConfig(1, 2, "AUTO_CURRENT");
        }
    }

    public record ConsentSummary(
            UUID consentId,
            CloudConsent.Scope scope,
            List<String> allowedProviders,
            String mode,
            Instant expiresAt
    ) {
        public static ConsentSummary from(CloudConsent c) {
            return new ConsentSummary(c.consentId(), c.scope(), c.allowedProviders(), c.mode(), c.expiresAt());
        }
    }

    public ReadingPolicy {
        Objects.requireNonNull(subjectId, "subjectId");
        defaultWindow = defaultWindow == null ? WindowConfig.standard() : defaultWindow;
        validConsents = validConsents == null ? List.of() : List.copyOf(validConsents);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public static ReadingPolicy defaultPolicy(String subjectId) {
        return new ReadingPolicy(1, 1L, subjectId, WindowConfig.standard(), List.of(), 1L, Instant.now());
    }

    public ReadingPolicy withUpdatedWindow(long nextRevision, WindowConfig nextWindow, List<ConsentSummary> consents, Instant now) {
        return new ReadingPolicy(schemaVersion, nextRevision, subjectId, nextWindow, consents, operationEpoch, now);
    }
}
