package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractCandidateConfirmationPersistenceRow(
        String confirmationId, String projectId, String candidateId,
        long baseCandidateVersionNo, long resultCandidateVersionNo,
        String sourceFileId, String sourceParseResultId, String sourceRoleDecisionId,
        String sourceIdentityDecisionId, int payloadSchemaVersion, String payloadFingerprint,
        String canonicalPayloadJson, String confirmedBy, LocalDateTime confirmedAt,
        String supersedesConfirmationId, LocalDateTime createdAt) { }
