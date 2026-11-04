package com.fuquan.audit.contract.application.project.identity;

import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityEvidence;
import java.util.List;
import java.util.Objects;

public record IdentityAssessmentCalculation(ProjectFileIdentityAssessment assessment,
        List<ProjectFileIdentityEvidence> evidence) {
    public IdentityAssessmentCalculation {
        Objects.requireNonNull(assessment);
        evidence = List.copyOf(Objects.requireNonNull(evidence));
    }
}
