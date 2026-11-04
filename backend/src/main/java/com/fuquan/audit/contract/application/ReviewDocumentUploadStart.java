package com.fuquan.audit.contract.application;

import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.contract.domain.MaterialBinding;
import com.fuquan.audit.contract.domain.ReviewDocument;

record ReviewDocumentUploadStart(
        IdempotencyRecord idempotency,
        ReviewDocument document,
        MaterialBinding bindingBefore,
        ReviewDocumentOperationResult replay) {

    static ReviewDocumentUploadStart executing(
            IdempotencyRecord idempotency, ReviewDocument document, MaterialBinding bindingBefore) {
        return new ReviewDocumentUploadStart(idempotency, document, bindingBefore, null);
    }

    static ReviewDocumentUploadStart replay(ReviewDocumentOperationResult result) {
        return new ReviewDocumentUploadStart(null, null, null, result);
    }

    boolean isReplay() {
        return replay != null;
    }
}
