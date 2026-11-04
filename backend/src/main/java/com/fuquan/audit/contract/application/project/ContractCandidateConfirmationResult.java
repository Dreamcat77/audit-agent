package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;

public record ContractCandidateConfirmationResult(ContractCandidateConfirmation confirmation,
                                                   ConfirmedContractPayload payload, boolean replayed) { }
