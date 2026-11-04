package com.fuquan.audit.contract.application.port;

import java.io.InputStream;

/** 流式、opaque 且可替换的文件存储边界。 */
public interface FileStoragePort {
    StagedFile stage(InputStream inputStream, long sizeLimit);

    void promote(StagedFile stagedFile, StorageKey storageKey);

    StoredFileContent open(StorageKey storageKey);

    /** Compensating cleanup for an object whose durable metadata could not be saved. */
    default void delete(StorageKey storageKey) {
        throw new UnsupportedOperationException("stored object deletion is not supported");
    }

    void discard(StagedFile stagedFile);

    StoredFileInspection inspect(StorageKey storageKey);
}
