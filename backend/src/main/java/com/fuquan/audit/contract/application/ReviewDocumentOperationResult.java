package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.domain.ContractReviewSession;
import com.fuquan.audit.contract.domain.MaterialBinding;
import com.fuquan.audit.contract.domain.ReviewDocument;

public record ReviewDocumentOperationResult(
        ReviewDocument document,
        MaterialBinding currentBinding,
        ContractReviewSession session,
        boolean replayed) {
}
