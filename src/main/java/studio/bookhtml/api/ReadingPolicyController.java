package studio.bookhtml.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import studio.bookhtml.domain.CloudConsent;
import studio.bookhtml.domain.ReadingPolicy;
import studio.bookhtml.service.CloudConsentService;

import java.io.IOException;
import java.util.UUID;

/**
 * Controller exposing reading policy, cloud consent, and operation-epoch endpoints (G06 / B04).
 */
@RestController
public class ReadingPolicyController {
    private final CloudConsentService consentService;

    public ReadingPolicyController(CloudConsentService consentService) {
        this.consentService = consentService;
    }

    @GetMapping("/api/reading-policy")
    public ReadingPolicy getReadingPolicy(@RequestHeader(value = "X-Subject-Id", required = false) String subjectId) {
        return consentService.getReadingPolicy(subjectId);
    }

    @PutMapping("/api/reading-policy")
    public ReadingPolicy updateReadingPolicy(
            @RequestHeader(value = "X-Subject-Id", required = false) String subjectId,
            @Valid @RequestBody ReadingPolicyUpdateRequest request) throws IOException {
        return consentService.updateReadingPolicy(subjectId, request);
    }

    @PostMapping("/api/cloud-consents")
    public CloudConsent createConsent(
            @RequestHeader(value = "X-Subject-Id", required = false) String subjectId,
            @Valid @RequestBody CreateConsentRequest request) throws IOException {
        return consentService.createConsent(subjectId, request);
    }

    @PostMapping("/api/cloud-consents/{id}/revoke")
    public CloudConsent revokeConsent(
            @PathVariable("id") UUID consentId,
            @RequestHeader(value = "X-Subject-Id", required = false) String subjectId,
            @Valid @RequestBody RevokeConsentRequest request) throws IOException {
        return consentService.revokeConsent(subjectId, consentId, request.expectedPolicyRevision(), request.clientOperationId());
    }

    @GetMapping("/api/books/{id}/operation-epoch")
    public OperationEpochSummary getOperationEpoch(
            @PathVariable("id") String bookId,
            @RequestHeader(value = "X-Subject-Id", required = false) String subjectId) {
        return consentService.getEpochSummary(subjectId, bookId);
    }
}
