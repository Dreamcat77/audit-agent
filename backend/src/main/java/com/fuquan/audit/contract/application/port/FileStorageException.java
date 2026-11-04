package com.fuquan.audit.contract.application.port;

/** 受控文件存储失败；消息不得包含绝对服务器路径。 */
public class FileStorageException extends RuntimeException {
    private final StorageFailureReason reason;

    public FileStorageException(String message) {
        this(StorageFailureReason.UNKNOWN, message, null);
    }

    public FileStorageException(String message, Throwable cause) {
        this(StorageFailureReason.UNKNOWN, message, cause);
    }

    public FileStorageException(StorageFailureReason reason, String message) {
        this(reason, message, null);
    }

    public FileStorageException(
            StorageFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "storage failure reason must not be null");
    }

    public StorageFailureReason reason() {
        return reason;
    }
}
