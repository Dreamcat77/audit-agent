package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractReviewRunMaterialPersistenceRow(
        String runMaterialId, String runId, int ordinal, String projectId, String fileId,
        String fileSha256, String parseResultId, int parseVersion, String canonicalTextSha256,
        String effectiveRole, String roleDecisionId, String identityDecisionId,
        String sourceRoleDispositionId, String selectedBy, LocalDateTime snapshottedAt) { }
