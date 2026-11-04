package com.fuquan.audit.contract.application.project.factai;

import java.util.Objects;
import java.util.UUID;

public record ContractFactExtractionAuthority(
        ContractFactCandidateValidator.CurrentInput current,
        UUID roleDecisionId,
        UUID identityDecisionId, boolean canonicalRowLayoutProven) {
    /** Explicit trusted canonical-row fixtures retain their construction seam. */
    public ContractFactExtractionAuthority(ContractFactCandidateValidator.CurrentInput current,
            UUID roleDecisionId,UUID identityDecisionId) {
        this(current,roleDecisionId,identityDecisionId,true);
    }
    public ContractFactExtractionAuthority {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(roleDecisionId, "roleDecisionId must not be null");
        Objects.requireNonNull(identityDecisionId, "identityDecisionId must not be null");
    }
}
