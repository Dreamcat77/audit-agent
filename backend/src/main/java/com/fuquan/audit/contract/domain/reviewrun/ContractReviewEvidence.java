package com.fuquan.audit.contract.domain.reviewrun;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Parse-tuple-bound evidence; offsets are zero-based Unicode code points when available. */
public record ContractReviewEvidence(
        UUID evidenceId, UUID findingId, UUID runId, UUID stepId, UUID attemptId,
        int ordinal, UUID fileId, String fileSha256, UUID parseResultId,
        int parseVersion, String canonicalTextSha256, String evidenceRole,
        String validationStatus, String validationPolicyVersion, Integer pageNumber,
        Integer paragraphIndex, Integer tableIndex, Integer rowIndex, Integer cellIndex,
        Integer segmentIndex, boolean locatorAvailable, Integer startOffset,
        Integer endOffset, String excerptSha256, String boundedExcerpt,
        int locatorSchemaVersion, Instant createdAt, long versionNo) {
    private static final Set<String> ROLES = Set.of("PROBLEM", "COMPARISON", "SUPPORT");

    public ContractReviewEvidence {
        Objects.requireNonNull(evidenceId); Objects.requireNonNull(findingId);
        Objects.requireNonNull(runId); Objects.requireNonNull(stepId);
        Objects.requireNonNull(attemptId); Objects.requireNonNull(fileId);
        Objects.requireNonNull(parseResultId); Objects.requireNonNull(createdAt);
        if (ordinal <= 0 || parseVersion <= 0 || locatorSchemaVersion <= 0 || versionNo < 0)
            throw new IllegalArgumentException("invalid evidence versions");
        ContractReviewRun.requireHash(fileSha256);
        ContractReviewRun.requireHash(canonicalTextSha256);
        ContractReviewFinding.required(validationPolicyVersion, 100, "validationPolicyVersion");
        ContractReviewFinding.required(boundedExcerpt, 1000, "boundedExcerpt");
        if (boundedExcerpt.getBytes(StandardCharsets.UTF_8).length > 1000)
            throw new IllegalArgumentException("boundedExcerpt exceeds the durable UTF-8 byte limit");
        if (!ROLES.contains(evidenceRole)) throw new IllegalArgumentException("invalid evidenceRole");
        if (pageNumber != null && pageNumber <= 0
                || paragraphIndex != null && paragraphIndex < 0
                || tableIndex != null && tableIndex < 0
                || rowIndex != null && rowIndex < 0
                || cellIndex != null && cellIndex < 0
                || segmentIndex != null && segmentIndex < 0)
            throw new IllegalArgumentException("invalid evidence dimensions");
        if (locatorAvailable) {
            if (!"VALIDATED".equals(validationStatus) || startOffset == null || endOffset == null
                    || startOffset < 0 || endOffset <= startOffset)
                throw new IllegalArgumentException("invalid validated locator");
            ContractReviewRun.requireHash(excerptSha256);
        } else if (!"LOCATOR_UNAVAILABLE".equals(validationStatus) || startOffset != null
                || endOffset != null) {
            throw new IllegalArgumentException("unavailable locator cannot claim offsets");
        } else if (excerptSha256 != null) {
            ContractReviewRun.requireHash(excerptSha256);
        }
    }
}
