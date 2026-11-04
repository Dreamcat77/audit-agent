package com.fuquan.audit.contract.application.project;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class ExtractionKeyFactoryV2 {
    public String create(UUID sourceFileId, UUID parseResultId, String extractionVersion, String factType,
                         String nullableGroupKey, String sourceLocator) {
        Objects.requireNonNull(sourceFileId);
        Objects.requireNonNull(parseResultId);
        MessageDigest digest = digest();
        lp(digest, "project-extraction-key-v2");
        lp(digest, sourceFileId.toString());
        lp(digest, parseResultId.toString());
        lp(digest, requireText(extractionVersion, "extractionVersion"));
        lp(digest, requireText(factType, "factType"));
        if (nullableGroupKey == null) {
            digest.update(ByteBuffer.allocate(4).putInt(-1).array());
        } else {
            lp(digest, nullableGroupKey);
        }
        lp(digest, requireText(sourceLocator, "sourceLocator"));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void lp(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
