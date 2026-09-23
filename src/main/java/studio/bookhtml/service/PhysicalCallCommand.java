package studio.bookhtml.service;

import java.util.Objects;
import java.util.UUID;

/**
 * Unified physical model call command (B05 / G03).
 * Captures execution identity, purpose, provider, account scope, budget, and monotonic deadline.
 * Sensitive tokens/keys are never placed directly in this command or journal.
 */
public record PhysicalCallCommand(
        String bookId,
        int page,
        ExecutionKind kind,
        String attemptId,
        String logicalCallId,
        String purpose,
        String provider,
        String model,
        String accountScope,
        UUID consentId,
        String budgetRootId,
        long deadlineNanos,
        boolean foreground,
        String inputFingerprint
) {
    public PhysicalCallCommand {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(accountScope, "accountScope");
        if (logicalCallId == null || logicalCallId.isBlank()) {
            logicalCallId = UUID.randomUUID().toString();
        }
        if (budgetRootId == null || budgetRootId.isBlank()) {
            budgetRootId = attemptId != null ? attemptId : logicalCallId;
        }
    }

    public enum ExecutionKind {
        OCR,
        ASSIST_LAYOUT,
        ASSIST_TEXT_REVIEW,
        TOC_RECOVERY,
        DECISION_VISION,
        PADDLE_OCR,
        PP_OCR,
        PP_OCR_AUTH,
        MINIMAX_ASSIST
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String bookId;
        private int page = 1;
        private ExecutionKind kind = ExecutionKind.OCR;
        private String attemptId;
        private String logicalCallId;
        private String purpose = "ocr";
        private String provider;
        private String model;
        private String accountScope = "default";
        private UUID consentId;
        private String budgetRootId;
        private long deadlineNanos;
        private boolean foreground = true;
        private String inputFingerprint = "";

        public Builder bookId(String bookId) { this.bookId = bookId; return this; }
        public Builder page(int page) { this.page = page; return this; }
        public Builder kind(ExecutionKind kind) { this.kind = kind; return this; }
        public Builder attemptId(String attemptId) { this.attemptId = attemptId; return this; }
        public Builder logicalCallId(String logicalCallId) { this.logicalCallId = logicalCallId; return this; }
        public Builder purpose(String purpose) { this.purpose = purpose; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder accountScope(String accountScope) { this.accountScope = accountScope; return this; }
        public Builder consentId(UUID consentId) { this.consentId = consentId; return this; }
        public Builder budgetRootId(String budgetRootId) { this.budgetRootId = budgetRootId; return this; }
        public Builder deadlineNanos(long deadlineNanos) { this.deadlineNanos = deadlineNanos; return this; }
        public Builder foreground(boolean foreground) { this.foreground = foreground; return this; }
        public Builder inputFingerprint(String inputFingerprint) { this.inputFingerprint = inputFingerprint; return this; }

        public PhysicalCallCommand build() {
            return new PhysicalCallCommand(bookId, page, kind, attemptId, logicalCallId,
                    purpose, provider, model, accountScope, consentId, budgetRootId,
                    deadlineNanos, foreground, inputFingerprint);
        }
    }
}
