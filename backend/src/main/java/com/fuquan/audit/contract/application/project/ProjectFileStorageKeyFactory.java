package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.application.port.StorageKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class ProjectFileStorageKeyFactory {
    public StorageKey create(UUID projectFileId, String stagedSha256) {
        if (projectFileId == null || stagedSha256 == null || !stagedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid ProjectFile storage identity input");
        }
        MessageDigest digest = sha256();
        lp(digest, "project-file-storage-v1");
        lp(digest, projectFileId.toString());
        lp(digest, stagedSha256);
        return new StorageKey(HexFormat.of().formatHex(digest.digest()));
    }

    private static void lp(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
