package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import java.util.List;
import java.util.UUID;

/** Safe durable association projection; no extracted values or source text. */
public record ContractCandidateSupportReplacementResult(
        UUID candidateId, long candidateVersion, long supportAuthorityVersion, CandidateAuthorityMode authorityMode,
        List<ContractCandidateSupportAuthority> supportAssociations, boolean replayed) {
    public ContractCandidateSupportReplacementResult {
        supportAssociations = List.copyOf(supportAssociations);
    }
}
