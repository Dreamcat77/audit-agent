package com.fuquan.audit.contract.application.port;

import java.util.Objects;

/** Type-safe result for storage reconciliation; UNKNOWN must never be treated as absence. */
public record StoredFileInspection(
        Status status, StoredFileInfo file, StorageFailureReason safeReason) {

    public enum Status {
        PRESENT,
        DEFINITELY_MISSING,
        UNKNOWN
    }

    public StoredFileInspection {
        Objects.requireNonNull(status, "inspection status must not be null");
        if ((status == Status.PRESENT) != (file != null)) {
            throw new IllegalArgumentException("only PRESENT inspection may contain file metadata");
        }
        if ((status == Status.UNKNOWN) != (safeReason != null)) {
            throw new IllegalArgumentException("only UNKNOWN inspection must contain a safe reason");
        }
    }

    public static StoredFileInspection present(StoredFileInfo file) {
        return new StoredFileInspection(Status.PRESENT, Objects.requireNonNull(file), null);
    }

    public static StoredFileInspection definitelyMissing() {
        return new StoredFileInspection(Status.DEFINITELY_MISSING, null, null);
    }

    public static StoredFileInspection unknown() {
        return unknown(StorageFailureReason.UNKNOWN);
    }

    public static StoredFileInspection unknown(StorageFailureReason safeReason) {
        return new StoredFileInspection(
                Status.UNKNOWN, null, Objects.requireNonNull(safeReason, "safe reason must not be null"));
    }
}
