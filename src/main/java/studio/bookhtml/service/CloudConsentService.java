package studio.bookhtml.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.*;
import studio.bookhtml.domain.CloudConsent;
import studio.bookhtml.domain.ReadingPolicy;
import studio.bookhtml.store.CloudConsentStore;
import studio.bookhtml.store.OperationEpochStore;
import studio.bookhtml.store.ReadingPolicyStore;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Service managing CloudConsent, ReadingPolicy, and authorization checks (G06).
 */
@Service
public class CloudConsentService {
    private final CloudConsentStore consentStore;
    private final ReadingPolicyStore policyStore;
    private final OperationEpochStore epochStore;

    public CloudConsentService(CloudConsentStore consentStore, ReadingPolicyStore policyStore, OperationEpochStore epochStore) {
        this.consentStore = Objects.requireNonNull(consentStore, "consentStore");
        this.policyStore = Objects.requireNonNull(policyStore, "policyStore");
        this.epochStore = Objects.requireNonNull(epochStore, "epochStore");
    }

    public ReadingPolicy getReadingPolicy(String subjectId) {
        String sub = resolveSubject(subjectId);
        ReadingPolicy policy = policyStore.getPolicy(sub);
        List<CloudConsent> consents = consentStore.listForSubject(sub);
        List<ReadingPolicy.ConsentSummary> active = consents.stream()
                .filter(CloudConsent::isValid)
                .map(ReadingPolicy.ConsentSummary::from)
                .toList();
        return new ReadingPolicy(policy.schemaVersion(), policy.policyRevision(), sub,
                policy.defaultWindow(), active, policy.operationEpoch(), policy.updatedAt());
    }

    public ReadingPolicy updateReadingPolicy(String subjectId, ReadingPolicyUpdateRequest request) throws IOException {
        String sub = resolveSubject(subjectId);
        Objects.requireNonNull(request, "request");
        return policyStore.updatePolicy(sub, request.expectedRevision(), request.defaultWindow());
    }

    public CloudConsent createConsent(String subjectId, CreateConsentRequest request) throws IOException {
        String sub = resolveSubject(subjectId);
        Objects.requireNonNull(request, "request");
        if (request.clientOperationId() == null || request.clientOperationId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 clientOperationId");
        }
        if (request.scope() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 scope");
        }
        if (request.allowedProviders() == null || request.allowedProviders().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "allowedProviders 不能为空");
        }

        ReadingPolicy policy = policyStore.getPolicy(sub);
        if (request.expectedPolicyRevision() != null && request.expectedPolicyRevision() != policy.policyRevision()) {
            throw new ApiException(HttpStatus.CONFLICT, "ReadingPolicy revision 不一致: 当前 " + policy.policyRevision());
        }

        UUID consentId = UUID.randomUUID();
        CloudConsent consent = new CloudConsent(
                1,
                consentId,
                sub,
                request.scope(),
                policy.policyRevision(),
                request.allowedProviders(),
                "default",
                List.of(),
                request.mode() == null ? "AUTO_CURRENT" : request.mode(),
                request.preloadBefore() == null ? 1 : request.preloadBefore(),
                request.preloadAfter() == null ? 2 : request.preloadAfter(),
                Boolean.TRUE.equals(request.enhanceCurrent()),
                Boolean.TRUE.equals(request.enhanceAdjacent()),
                Boolean.TRUE.equals(request.regionalRecoveryAllowed()),
                Boolean.TRUE.equals(request.wholeBookAllowed()),
                request.maxOcrStartsPerPage() == null ? 1 : request.maxOcrStartsPerPage(),
                request.maxEnhancementSendsPerAttempt() == null ? 8 : request.maxEnhancementSendsPerAttempt(),
                request.monetaryLimits() == null ? CloudConsent.MonetaryLimits.cny(0) : request.monetaryLimits(),
                Instant.now(),
                request.expiresAt(),
                null
        );

        consentStore.write(consent);

        // Refresh policy summaries
        List<CloudConsent> consents = consentStore.listForSubject(sub);
        List<ReadingPolicy.ConsentSummary> active = consents.stream()
                .filter(CloudConsent::isValid)
                .map(ReadingPolicy.ConsentSummary::from)
                .toList();
        policyStore.updateConsents(sub, active);

        return consent;
    }

    public CloudConsent revokeConsent(String subjectId, UUID consentId, Long expectedPolicyRevision, String clientOperationId) throws IOException {
        String sub = resolveSubject(subjectId);
        Objects.requireNonNull(consentId, "consentId");
        CloudConsent consent = consentStore.read(consentId);
        if (consent == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "未找到该授权记录");
        }
        if (!sub.equals(consent.subjectId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权撤销其他主体的授权");
        }

        ReadingPolicy policy = policyStore.getPolicy(sub);
        if (expectedPolicyRevision != null && expectedPolicyRevision != policy.policyRevision()) {
            throw new ApiException(HttpStatus.CONFLICT, "ReadingPolicy revision 不一致: 当前 " + policy.policyRevision());
        }

        CloudConsent revoked = consentStore.revoke(consentId, Instant.now());

        // Refresh policy summaries
        List<CloudConsent> consents = consentStore.listForSubject(sub);
        List<ReadingPolicy.ConsentSummary> active = consents.stream()
                .filter(CloudConsent::isValid)
                .map(ReadingPolicy.ConsentSummary::from)
                .toList();
        policyStore.updateConsents(sub, active);

        return revoked;
    }

    public CloudConsent findActiveConsent(String subjectId, String bookId) {
        String sub = resolveSubject(subjectId);
        return consentStore.findActiveConsent(sub, bookId);
    }

    public boolean hasConsent(String bookId, String provider) {
        String sub = resolveSubject(null);
        CloudConsent consent = consentStore.findActiveConsent(sub, bookId);
        return consent != null && consent.permitsProvider(provider);
    }

    public void validateAuthorization(String subjectId, String bookId, String provider, boolean enhance, boolean regionalRecovery) {
        String sub = resolveSubject(subjectId);
        // Only cloud providers require persistent consent
        if (isLocalProvider(provider)) {
            return;
        }

        CloudConsent consent = consentStore.findActiveConsent(sub, bookId);
        if (consent == null || !consent.isValid()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "未授予云端处理权限，请先进行云端授权");
        }

        if (!consent.permitsBook(bookId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "该书籍未包含在云端授权范围内");
        }

        if (!consent.permitsProvider(provider)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "供应商未在授权列表中: " + provider);
        }

        if (enhance && !consent.permitsEnhance(false)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AI 辅助增强未被授权");
        }

        if (regionalRecovery && !consent.permitsRegionalRecovery()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "局部区域重识别未被授权");
        }
    }

    public OperationEpochSummary getEpochSummary(String subjectId, String bookId) {
        String sub = resolveSubject(subjectId);
        OperationEpochStore.EpochState state = epochStore.getState(bookId);
        return new OperationEpochSummary(
                state.epoch(),
                bookId,
                sub,
                state.activeOperations().size(),
                state.activeOperations().size() + state.archivedOperations().size(),
                OperationEpochStore.MAX_OPERATIONS_PER_EPOCH,
                state.epochStartedAt()
        );
    }

    private boolean isLocalProvider(String provider) {
        return provider == null || "tesseract".equalsIgnoreCase(provider) || "local".equalsIgnoreCase(provider);
    }

    private String resolveSubject(String requested) {
        return requested != null && !requested.isBlank() ? requested : "local-owner";
    }
}
