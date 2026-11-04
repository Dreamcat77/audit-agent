package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ProjectFileRoleDecisionPersistenceRow(
        String decisionId, String projectId, String fileId, String fileSha256, String parseResultId,
        int parseVersion, String assessmentId, String selectedRole, String decisionStatus, String decidedBy,
        LocalDateTime decidedAt, String supersedesDecisionId, String reason, long versionNo) {
}
