package com.fuquan.audit.contract.application.project.identity;

import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import java.util.Objects;
import java.util.UUID;

public record IdentityConfirmationResult(ProjectFileIdentityDecision decision, boolean replayed,
        UUID candidateId, ContractCandidateMaterializationStatus candidateMaterializationStatus) {
    public IdentityConfirmationResult { Objects.requireNonNull(decision); }
    public IdentityConfirmationResult(ProjectFileIdentityDecision decision, boolean replayed) {
        this(decision, replayed, null, null);
    }
}
