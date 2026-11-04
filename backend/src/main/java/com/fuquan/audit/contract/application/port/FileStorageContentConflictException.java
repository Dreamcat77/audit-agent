package com.fuquan.audit.contract.application.port;

/** A stable storage key exists but does not contain the bytes claimed by the durable operation. */
public class FileStorageContentConflictException extends FileStorageException {
    public FileStorageContentConflictException(String message) {
        super(StorageFailureReason.CONTENT_CONFLICT, message);
    }

    public FileStorageContentConflictException(String message, Throwable cause) {
        super(StorageFailureReason.CONTENT_CONFLICT, message, cause);
    }
}
