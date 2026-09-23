package studio.bookhtml.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RevokeConsentRequest(
        @NotNull Long expectedPolicyRevision,
        @NotBlank String clientOperationId
) {}
