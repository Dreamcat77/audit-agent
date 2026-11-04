package com.fuquan.audit.contract.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 使用稳定字段名、长度前缀和 UTF-8 生成业务指纹/opaque storage key。 */
final class ReviewSessionFingerprint {
    private final MessageDigest digest;

    private ReviewSessionFingerprint() {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java 17", exception);
        }
    }

    static ReviewSessionFingerprint create() {
        return new ReviewSessionFingerprint();
    }

    ReviewSessionFingerprint field(String name, Object value) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = String.valueOf(value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(nameBytes.length).array());
        digest.update(nameBytes);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(valueBytes.length).array());
        digest.update(valueBytes);
        return this;
    }

    String hex() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
