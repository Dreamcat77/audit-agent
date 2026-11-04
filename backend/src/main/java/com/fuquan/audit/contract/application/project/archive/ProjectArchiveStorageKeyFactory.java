package com.fuquan.audit.contract.application.project.archive;

import com.fuquan.audit.contract.application.port.StorageKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class ProjectArchiveStorageKeyFactory {
    public StorageKey create(UUID rootFileId, String normalizedPath, String childSha256) {
        if (rootFileId == null || normalizedPath == null || normalizedPath.isBlank()
                || childSha256 == null || !childSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid archive child storage identity");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{"project-archive-child-v1", rootFileId.toString(), normalizedPath, childSha256}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return new StorageKey(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
