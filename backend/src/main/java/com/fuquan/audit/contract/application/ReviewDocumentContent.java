package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.domain.ReviewDocument;

public record ReviewDocumentContent(ReviewDocument document, StoredFileContent content) {
}
