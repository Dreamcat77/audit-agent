package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Typed executor output only; no raw model response or prompt is accepted. */
public record StepComputationResult(
        String outputResourceType, UUID outputResourceId,
        List<ContractReviewFinding> findings, List<ContractReviewEvidence> evidence,
        List<ContractReviewClauseComparison> typedComparisons,
        List<ContractModelCallAudit> modelCalls,
        String providerRequestId, String provider, String model) {
    public StepComputationResult {
        Objects.requireNonNull(outputResourceType); Objects.requireNonNull(outputResourceId);
        if (outputResourceType.isBlank()) throw new IllegalArgumentException("blank output resource type");
        findings = List.copyOf(findings); evidence = List.copyOf(evidence);
        typedComparisons = List.copyOf(typedComparisons);
        modelCalls = List.copyOf(modelCalls);
    }

    public StepComputationResult(String outputResourceType, UUID outputResourceId,
            List<ContractReviewFinding> findings, List<ContractReviewEvidence> evidence,
            List<ContractReviewClauseComparison> typedComparisons,
            String providerRequestId, String provider, String model) {
        this(outputResourceType, outputResourceId, findings, evidence, typedComparisons,
                List.of(), providerRequestId, provider, model);
    }

    public StepComputationResult(String outputResourceType, UUID outputResourceId,
            List<ContractReviewFinding> findings, List<ContractReviewEvidence> evidence,
            String providerRequestId, String provider, String model) {
        this(outputResourceType, outputResourceId, findings, evidence, List.of(), List.of(),
                providerRequestId, provider, model);
    }
}
