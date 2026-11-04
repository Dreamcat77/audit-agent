package com.fuquan.audit.contract.application.port;

import java.io.InputStream;
import java.util.Objects;

/** API 下载使用的流及已知长度。 */
public record StoredFileContent(InputStream inputStream, long size) {
    public StoredFileContent {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
    }
}
