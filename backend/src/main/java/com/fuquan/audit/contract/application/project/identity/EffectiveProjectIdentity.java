package com.fuquan.audit.contract.application.project.identity;

import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityDecisionStatus;
import java.util.UUID;
import com.fuquan.audit.contract.application.project.AutomaticDecisionProvenance;

public record EffectiveProjectIdentity(EffectiveProjectIdentityStatus status,
        UUID assessmentId, Long assessmentVersion, ProjectIdentityAssessmentStatus assessmentStatus,
        ProjectIdentityDecisionStatus decisionStatus, UUID decisionId, Long decisionVersion,
        boolean identityCurrent, boolean excludedFromProjectUse,
        ProjectFileIdentityDecision currentHumanSameDecision,
        EffectiveProjectIdentityRecoveryReason recoveryReason) {

    public EffectiveProjectIdentity(EffectiveProjectIdentityStatus status,
            UUID assessmentId, ProjectIdentityAssessmentStatus assessmentStatus,
            ProjectIdentityDecisionStatus decisionStatus, UUID decisionId, Long decisionVersion,
            boolean identityCurrent, boolean excludedFromProjectUse,
            ProjectFileIdentityDecision currentHumanSameDecision) {
        this(status, assessmentId, null, assessmentStatus, decisionStatus, decisionId, decisionVersion,
                identityCurrent, excludedFromProjectUse, currentHumanSameDecision, null);
    }

    public EffectiveProjectIdentity(EffectiveProjectIdentityStatus status,
            UUID assessmentId, ProjectIdentityAssessmentStatus assessmentStatus,
            ProjectIdentityDecisionStatus decisionStatus, UUID decisionId, Long decisionVersion,
            boolean identityCurrent, boolean excludedFromProjectUse,
            ProjectFileIdentityDecision currentHumanSameDecision,
            EffectiveProjectIdentityRecoveryReason recoveryReason) {
        this(status, assessmentId, null, assessmentStatus, decisionStatus, decisionId, decisionVersion,
                identityCurrent, excludedFromProjectUse, currentHumanSameDecision, recoveryReason);
    }

    public boolean eligibleForProjectUse() {
        return status == EffectiveProjectIdentityStatus.SAME_PROJECT && currentHumanSameDecision != null;
    }

    public boolean systemAutoDecision() {
        return currentHumanSameDecision != null
                && AutomaticDecisionProvenance.isAutomatic(currentHumanSameDecision.reason());
    }

    public ProjectFileIdentityDecision currentAuthorizedSameDecision() {
        return currentHumanSameDecision;
    }
}
