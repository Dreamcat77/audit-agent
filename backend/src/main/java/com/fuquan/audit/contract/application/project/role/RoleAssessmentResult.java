package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleEvidence;
import com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
import java.util.List;
import java.util.Objects;

/** Reopenable durable role-assessment projection. */
public record RoleAssessmentResult(
        ProjectFileRoleAssessment assessment,
        RoleDecisionStatus decisionStatus,
        List<ProjectFileRoleEvidence> evidence,
        boolean replayed) {

    public RoleAssessmentResult {
        Objects.requireNonNull(assessment, "assessment must not be null");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        RoleDecisionStatus expected = assessment.executionStatus() == RoleAssessmentExecutionStatus.COMPLETED
                ? RoleDecisionStatus.NEEDS_CONFIRMATION : null;
        if (decisionStatus != expected) {
            throw new IllegalArgumentException("decision status does not match machine execution state");
        }
        if (assessment.executionStatus() != RoleAssessmentExecutionStatus.COMPLETED && !evidence.isEmpty()) {
            throw new IllegalArgumentException("non-completed assessment cannot expose evidence");
        }
    }
}
