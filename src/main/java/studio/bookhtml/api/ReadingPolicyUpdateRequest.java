package studio.bookhtml.api;

import jakarta.validation.constraints.NotNull;
import studio.bookhtml.domain.ReadingPolicy;

public record ReadingPolicyUpdateRequest(
        @NotNull Long expectedRevision,
        @NotNull ReadingPolicy.WindowConfig defaultWindow
) {}
