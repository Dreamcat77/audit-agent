package com.fuquan.audit.contract.application.port;

/** Stable, non-sensitive infrastructure diagnostic classification. */
public enum StorageFailureReason {
    ACCESS_DENIED,
    FILESYSTEM_ERROR,
    IO_ERROR,
    PROVIDER_ERROR,
    PUBLICATION_UNSUPPORTED,
    CONTENT_CONFLICT,
    UNKNOWN
}
