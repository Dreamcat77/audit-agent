package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.domain.ReviewDocument;

public record ReviewDocumentView(ReviewDocument document, boolean current) {
}
