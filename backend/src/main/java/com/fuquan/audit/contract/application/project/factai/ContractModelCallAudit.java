package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.BatchStatus;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Scope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Credential-free per-transport audit owned by exactly one durable operation. */
public record ContractModelCallAudit(UUID callAuditId, UUID factExtractionAttemptId,
        UUID reviewStepAttemptId, int attemptNo, Scope scope, int callOrdinal, CallPhase phase,
        String requestFingerprint, String provider, String modelName, CallOutcome outcome,
        String safeReason, long durationMs, String finishReason, Integer promptTokens,
        Integer completionTokens, Integer totalTokens, BatchStatus batchStatus,
        Instant createdAt) {
    public ContractModelCallAudit {
        Objects.requireNonNull(callAuditId); Objects.requireNonNull(phase);
        Objects.requireNonNull(outcome); Objects.requireNonNull(createdAt);
        boolean factOwner = factExtractionAttemptId != null;
        boolean reviewOwner = reviewStepAttemptId != null;
        if (factOwner == reviewOwner) throw new IllegalArgumentException("exactly one model call owner is required");
        if (factOwner != (scope != null && batchStatus != null)) {
            throw new IllegalArgumentException("fact model call scope/status ownership is incoherent");
        }
        if (attemptNo <= 0 || callOrdinal <= 0 || durationMs < 0) {
            throw new IllegalArgumentException("invalid model call counters");
        }
        required(requestFingerprint, "requestFingerprint"); required(provider, "provider");
        required(modelName, "modelName");
        if (!requestFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("request fingerprint must be SHA-256");
        }
        if (safeReason != null && !safeReason.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("invalid safe reason");
        }
        for (Integer count : new Integer[]{promptTokens, completionTokens, totalTokens}) {
            if (count != null && count < 0) throw new IllegalArgumentException("negative token count");
        }
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
