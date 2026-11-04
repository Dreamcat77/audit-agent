package com.fuquan.audit.contract.application.port;

/** A safe, expected rejection of user-provided file bytes. */
public class FileStorageUserRejectionException extends RuntimeException {

    public enum Reason {
        EMPTY,
        SIZE_LIMIT
    }

    private final Reason reason;

    public FileStorageUserRejectionException(Reason reason, String safeMessage) {
        super(safeMessage);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
