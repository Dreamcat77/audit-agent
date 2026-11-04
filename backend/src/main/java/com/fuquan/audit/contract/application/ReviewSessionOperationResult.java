package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.domain.ContractReviewSession;

public record ReviewSessionOperationResult(ContractReviewSession session, boolean replayed) {
}
