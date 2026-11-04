package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractReviewRunPersistenceRow(
        String runId, String projectId, String contractId, long contractVersion,
        String sourceCandidateId, String sourceConfirmationId, int runSchemaVersion,
        String contractSnapshotCanonicalJson, String contractSnapshotSha256,
        String reviewProfileVersion, String ruleVersion, String modelPolicyVersion,
        String inputSnapshotIdentity, String requestedBy, String status,
        LocalDateTime requestedAt, LocalDateTime startedAt, LocalDateTime completedAt,
        LocalDateTime failedAt, LocalDateTime unknownAt, String safeReasonCode, long versionNo) { }
