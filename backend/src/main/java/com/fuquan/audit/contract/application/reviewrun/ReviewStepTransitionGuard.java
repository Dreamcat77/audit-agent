package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.util.Objects;

/** Shared Java validation before adapter-level STATUS/VERSION compare-and-set. */
public final class ReviewStepTransitionGuard {
    private ReviewStepTransitionGuard() { }

    public static void requireValid(ContractReviewRun expected, ContractReviewRun next) {
        if (!expected.runId().equals(next.runId())
                || !expected.projectId().equals(next.projectId())
                || !expected.contractId().equals(next.contractId())
                || expected.contractVersion() != next.contractVersion()
                || !Objects.equals(expected.sourceCandidateId(), next.sourceCandidateId())
                || !Objects.equals(expected.sourceConfirmationId(), next.sourceConfirmationId())
                || expected.runSchemaVersion() != next.runSchemaVersion()
                || !expected.contractSnapshotCanonicalJson().equals(next.contractSnapshotCanonicalJson())
                || !expected.contractSnapshotSha256().equals(next.contractSnapshotSha256())
                || !expected.reviewProfileVersion().equals(next.reviewProfileVersion())
                || !expected.ruleVersion().equals(next.ruleVersion())
                || !expected.modelPolicyVersion().equals(next.modelPolicyVersion())
                || !expected.inputSnapshotIdentity().equals(next.inputSnapshotIdentity())
                || !expected.requestedBy().equals(next.requestedBy())
                || !expected.requestedAt().equals(next.requestedAt())
                || next.versionNo() != expected.versionNo() + 1
                || !allowed(expected.status(), next.status())) throw conflict();
    }

    public static void requireValid(ContractReviewStep expected, ContractReviewStep next) {
        if (!expected.stepId().equals(next.stepId()) || !expected.runId().equals(next.runId())
                || expected.stepType() != next.stepType() || expected.required() != next.required()
                || !expected.stepPolicyVersion().equals(next.stepPolicyVersion())
                || expected.currentAttemptNo() != next.currentAttemptNo()
                || next.versionNo() != expected.versionNo() + 1
                || !allowed(expected.status(), next.status())) throw conflict();
    }

    public static void requireValid(ContractReviewStepAttempt expected, ContractReviewStepAttempt next) {
        if (!expected.attemptId().equals(next.attemptId()) || !expected.runId().equals(next.runId())
                || !expected.stepId().equals(next.stepId())
                || expected.attemptNo() != next.attemptNo()
                || !expected.operationIdentity().equals(next.operationIdentity())
                || next.versionNo() != expected.versionNo() + 1
                || !allowed(expected.status(), next.status())) throw conflict();
        if (expected.status() == ContractReviewStepStatus.RUNNING
                && next.status() == ContractReviewStepStatus.RUNNING
                && (expected.externalCallState() != ReviewStepExternalCallState.NOT_STARTED
                || next.externalCallState() != ReviewStepExternalCallState.REQUESTED
                || next.externalRequestFingerprint() == null)) throw conflict();
    }

    private static boolean allowed(ContractReviewRunStatus from, ContractReviewRunStatus to) {
        return from == ContractReviewRunStatus.PENDING && to == ContractReviewRunStatus.RUNNING
                || from == ContractReviewRunStatus.RUNNING && (to == ContractReviewRunStatus.RUNNING
                || to == ContractReviewRunStatus.COMPLETED
                || to == ContractReviewRunStatus.FAILED || to == ContractReviewRunStatus.UNKNOWN);
    }

    private static boolean allowed(ContractReviewStepStatus from, ContractReviewStepStatus to) {
        return from == ContractReviewStepStatus.PENDING && to == ContractReviewStepStatus.RUNNING
                || from == ContractReviewStepStatus.RUNNING && (to == ContractReviewStepStatus.COMPLETED
                || to == ContractReviewStepStatus.FAILED || to == ContractReviewStepStatus.UNKNOWN
                || to == ContractReviewStepStatus.RUNNING);
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT, "review step transition conflict");
    }
}
