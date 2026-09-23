package studio.bookhtml.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import studio.bookhtml.domain.CloudConsent;

import java.time.Instant;
import java.util.List;

public record CreateConsentRequest(
        @NotBlank String clientOperationId,
        @NotNull Long expectedPolicyRevision,
        @NotNull CloudConsent.Scope scope,
        @NotNull List<String> allowedProviders,
        String mode,
        Integer preloadBefore,
        Integer preloadAfter,
        Boolean enhanceCurrent,
        Boolean enhanceAdjacent,
        Boolean regionalRecoveryAllowed,
        Boolean wholeBookAllowed,
        Integer maxOcrStartsPerPage,
        Integer maxEnhancementSendsPerAttempt,
        CloudConsent.MonetaryLimits monetaryLimits,
        Instant expiresAt
) {}
