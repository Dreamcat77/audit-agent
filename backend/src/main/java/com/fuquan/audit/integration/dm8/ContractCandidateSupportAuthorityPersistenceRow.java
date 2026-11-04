package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractCandidateSupportAuthorityPersistenceRow(
        String supportAuthorityId, String projectId, String candidateId, String sourceFileId,
        String fileSha256, String parseResultId, int parseVersion, long projectVersionNo,
        String effectiveRole, String roleDecisionId, String identityResolution, String identityDecisionId,
        String sourceFactFingerprint, long authorityVersion, String createdBy, LocalDateTime createdAt) { }
