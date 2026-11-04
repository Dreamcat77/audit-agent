package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.application.port.StagedFile;

record ValidatedReviewFile(
        StagedFile stagedFile,
        String safeFileName,
        String extension,
        String canonicalContentType) {
}
