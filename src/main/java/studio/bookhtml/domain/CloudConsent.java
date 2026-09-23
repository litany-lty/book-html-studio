package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-authoritative persistent consent for cloud model access (G06).
 * Enforces bounded scope, providers, destinations, preload limits and monetary caps.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudConsent(
        int schemaVersion,
        UUID consentId,
        String subjectId,
        Scope scope,
        long policyRevision,
        List<String> allowedProviders,
        String credentialScopeId,
        List<String> allowedDestinations,
        String mode,
        int preloadBefore,
        int preloadAfter,
        boolean enhanceCurrent,
        boolean enhanceAdjacent,
        boolean regionalRecoveryAllowed,
        boolean wholeBookAllowed,
        int maxOcrStartsPerPage,
        int maxEnhancementSendsPerAttempt,
        MonetaryLimits monetaryLimits,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt
) {
    public record Scope(String kind, String bookId) {
        public Scope {
            if (kind == null || !("BOOK".equals(kind) || "ALL_BOOKS".equals(kind))) {
                throw new IllegalArgumentException("invalid scope kind");
            }
            if ("BOOK".equals(kind) && (bookId == null || bookId.isBlank())) {
                throw new IllegalArgumentException("bookId required for BOOK scope");
            }
        }

        public static Scope book(String bookId) {
            return new Scope("BOOK", Objects.requireNonNull(bookId, "bookId"));
        }

        public static Scope allBooks() {
            return new Scope("ALL_BOOKS", null);
        }
    }

    public record MonetaryLimits(String currency, long minorUnits) {
        public MonetaryLimits {
            if (currency == null || currency.isBlank() || minorUnits < 0) {
                throw new IllegalArgumentException("invalid monetary limits");
            }
        }

        public static MonetaryLimits cny(long minorUnits) {
            return new MonetaryLimits("CNY", minorUnits);
        }
    }

    public CloudConsent {
        Objects.requireNonNull(consentId, "consentId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(issuedAt, "issuedAt");
        allowedProviders = allowedProviders == null ? List.of() : List.copyOf(allowedProviders);
        allowedDestinations = allowedDestinations == null ? List.of() : List.copyOf(allowedDestinations);
        mode = mode == null ? "AUTO_CURRENT" : mode;
        maxOcrStartsPerPage = maxOcrStartsPerPage <= 0 ? 1 : maxOcrStartsPerPage;
        maxEnhancementSendsPerAttempt = maxEnhancementSendsPerAttempt <= 0 ? 8 : maxEnhancementSendsPerAttempt;
        monetaryLimits = monetaryLimits == null ? MonetaryLimits.cny(0) : monetaryLimits;
    }

    public boolean isValid() {
        return revokedAt == null && (expiresAt == null || Instant.now().isBefore(expiresAt));
    }

    public boolean permitsBook(String targetBookId) {
        if (!isValid()) return false;
        if ("ALL_BOOKS".equals(scope.kind())) return true;
        return targetBookId != null && targetBookId.equals(scope.bookId());
    }

    public boolean permitsProvider(String provider) {
        if (!isValid()) return false;
        return allowedProviders.contains(provider) || allowedProviders.contains("*");
    }

    public boolean permitsEnhance(boolean adjacent) {
        if (!isValid()) return false;
        return adjacent ? enhanceAdjacent : enhanceCurrent;
    }

    public boolean permitsRegionalRecovery() {
        if (!isValid()) return false;
        return regionalRecoveryAllowed;
    }

    public CloudConsent withRevocation(Instant now) {
        return new CloudConsent(schemaVersion, consentId, subjectId, scope, policyRevision,
                allowedProviders, credentialScopeId, allowedDestinations, mode, preloadBefore,
                preloadAfter, enhanceCurrent, enhanceAdjacent, regionalRecoveryAllowed,
                wholeBookAllowed, maxOcrStartsPerPage, maxEnhancementSendsPerAttempt,
                monetaryLimits, issuedAt, expiresAt, Objects.requireNonNull(now));
    }
}
