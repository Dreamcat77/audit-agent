package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.math.BigDecimal;
import java.util.UUID;

public record ProjectFileRoleProjection(
        UUID parseResultId,
        Integer parseVersion,
        String parseOrigin,
        UUID roleAssessmentId,
        DocumentRole suggestedRole,
        BigDecimal roleConfidence,
        String roleExecutionStatus,
        String roleAssessmentSource,
        String roleSafeReasonCode,
        String roleModelProvider,
        String roleModelName,
        String roleModelRevision,
        String rolePromptVersion,
        String roleSchemaVersion,
        UUID aiRoleAssessmentId,
        String aiRoleExecutionStatus,
        String aiRoleSafeReasonCode,
        String roleDecisionStatus,
        DocumentRole effectiveRole,
        EffectiveDocumentRoleSource effectiveRoleSource,
        UUID roleDecisionId,
        Long roleDecisionVersion,
        boolean roleConflict) {
    public ProjectFileRoleProjection(UUID parseResultId, Integer parseVersion, String parseOrigin,
            UUID roleAssessmentId, DocumentRole suggestedRole, BigDecimal roleConfidence,
            String roleExecutionStatus, String roleAssessmentSource, String roleSafeReasonCode,
            String roleModelProvider, String roleModelName, String roleModelRevision,
            String rolePromptVersion, String roleSchemaVersion, UUID aiRoleAssessmentId,
            String aiRoleExecutionStatus, String aiRoleSafeReasonCode, String roleDecisionStatus,
            DocumentRole effectiveRole, EffectiveDocumentRoleSource effectiveRoleSource,
            UUID roleDecisionId, Long roleDecisionVersion) {
        this(parseResultId, parseVersion, parseOrigin, roleAssessmentId, suggestedRole,
                roleConfidence, roleExecutionStatus, roleAssessmentSource, roleSafeReasonCode,
                roleModelProvider, roleModelName, roleModelRevision, rolePromptVersion,
                roleSchemaVersion, aiRoleAssessmentId, aiRoleExecutionStatus, aiRoleSafeReasonCode,
                roleDecisionStatus, effectiveRole, effectiveRoleSource, roleDecisionId,
                roleDecisionVersion, false);
    }
}
