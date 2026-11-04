package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.application.project.ContractCandidateMaterializationStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import java.util.Objects;
import java.util.UUID;

public record RoleConfirmationResult(ProjectFileRoleDecision decision, boolean replayed,
                                     UUID candidateId,
                                     ContractCandidateMaterializationStatus candidateMaterializationStatus) {
    public RoleConfirmationResult {
        Objects.requireNonNull(decision, "decision must not be null");
    }

    public RoleConfirmationResult(ProjectFileRoleDecision decision, boolean replayed) {
        this(decision, replayed, null, null);
    }
}
