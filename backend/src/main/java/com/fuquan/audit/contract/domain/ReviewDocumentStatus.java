package com.fuquan.audit.contract.domain;

/** 文件接入状态；不表示 OCR、AI 或解析任务状态。 */
public enum ReviewDocumentStatus {
    UPLOAD_PENDING,
    AVAILABLE,
    UPLOAD_FAILED
}
