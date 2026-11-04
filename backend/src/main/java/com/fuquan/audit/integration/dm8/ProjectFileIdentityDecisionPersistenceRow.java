package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ProjectFileIdentityDecisionPersistenceRow(
        String decisionId, String projectId, long projectVersionNo, String fileId, String fileSha256,
        String parseResultId, int parseVersion, String assessmentId, String resolution,
        String decidedBy, LocalDateTime decidedAt, String supersedesDecisionId,
        String reason, long versionNo) { }
