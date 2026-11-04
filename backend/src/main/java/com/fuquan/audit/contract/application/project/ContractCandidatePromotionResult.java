package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ProjectContract;

public record ContractCandidatePromotionResult(
        Contract contract,
        ProjectContract association,
        ContractCandidate candidate) {
}
