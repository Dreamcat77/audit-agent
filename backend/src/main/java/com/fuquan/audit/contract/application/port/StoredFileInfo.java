package com.fuquan.audit.contract.application.port;

/** 稳定对象的流式检查摘要。 */
public record StoredFileInfo(long size, String sha256) {
}
