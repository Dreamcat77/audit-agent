package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Provenance;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ContractFactExtractionAttempt(UUID attemptId, UUID projectId, UUID fileId,
        String fileSha256, UUID parseResultId, int parseVersion, String canonicalTextSha256,
        DocumentRole effectiveRole, UUID roleDecisionId, UUID identityDecisionId,
        String inputFingerprint, String actorId, String operationKey, Provenance provenance,
        Status status, int callCount, int acceptedCount, int rejectedCount,
        String failureReason, Instant createdAt, Instant updatedAt, long versionNo) {
    public enum Status { IN_PROGRESS, SUCCEEDED, FAILED, OUTCOME_UNKNOWN }

    public static Status failureStatus(String reason) {
        return java.util.Set.of("TIMEOUT", "TOTAL_BUDGET_EXCEEDED", "MODEL_OUTCOME_UNKNOWN", "OUTCOME_UNKNOWN", "UNEXPECTED_FAILURE")
                .contains(reason == null ? "UNSPECIFIED" : reason) ? Status.OUTCOME_UNKNOWN : Status.FAILED;
    }

    public ContractFactExtractionAttempt {
        Objects.requireNonNull(attemptId); Objects.requireNonNull(projectId); Objects.requireNonNull(fileId);
        Objects.requireNonNull(parseResultId); Objects.requireNonNull(effectiveRole);
        Objects.requireNonNull(roleDecisionId); Objects.requireNonNull(identityDecisionId);
        Objects.requireNonNull(provenance); Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        required(fileSha256, "fileSha256"); required(canonicalTextSha256, "canonicalTextSha256");
        required(inputFingerprint, "inputFingerprint"); required(actorId, "actorId");
        required(operationKey, "operationKey");
        if (parseVersion <= 0 || callCount <= 0 || acceptedCount < 0 || rejectedCount < 0 || versionNo < 0) {
            throw new IllegalArgumentException("invalid extraction attempt counters");
        }
    }

    public static ContractFactExtractionAttempt start(UUID attemptId, UUID projectId, UUID fileId,
            String fileSha256, UUID parseResultId, int parseVersion, String canonicalTextSha256,
            DocumentRole effectiveRole, UUID roleDecisionId, UUID identityDecisionId,
            String inputFingerprint, String actorId, String operationKey, Provenance provenance,
            Instant occurredAt) {
        return new ContractFactExtractionAttempt(attemptId, projectId, fileId, fileSha256,
                parseResultId, parseVersion, canonicalTextSha256, effectiveRole, roleDecisionId,
                identityDecisionId, inputFingerprint, actorId, operationKey, provenance,
                Status.IN_PROGRESS, 1, 0, 0, null, occurredAt, occurredAt, 0);
    }

    public ContractFactExtractionAttempt completed(Status terminal, int accepted, int rejected,
            String reason, Instant occurredAt) {
        if (terminal == Status.IN_PROGRESS) throw new IllegalArgumentException("terminal status is required");
        return new ContractFactExtractionAttempt(attemptId, projectId, fileId, fileSha256,
                parseResultId, parseVersion, canonicalTextSha256, effectiveRole, roleDecisionId,
                identityDecisionId, inputFingerprint, actorId, operationKey, provenance,
                terminal, callCount, accepted, rejected, reason, createdAt, occurredAt, versionNo + 1);
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
