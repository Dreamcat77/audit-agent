package com.fuquan.audit.contract.application.port;

import java.util.Arrays;
import java.util.Objects;

/** 私有 staging 对象的 opaque handle 与流式计算结果。 */
public record StagedFile(String stagingId, long size, String sha256, byte[] header) {
    public StagedFile {
        Objects.requireNonNull(stagingId, "stagingId must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        Objects.requireNonNull(header, "header must not be null");
        if (size <= 0 || sha256.length() != 64) {
            throw new IllegalArgumentException("staged file metadata is invalid");
        }
        header = Arrays.copyOf(header, header.length);
    }

    @Override
    public byte[] header() {
        return Arrays.copyOf(header, header.length);
    }
}
