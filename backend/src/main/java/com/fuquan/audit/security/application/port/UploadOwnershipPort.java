package com.fuquan.audit.security.application.port;

import java.util.UUID;

/** Persisted ownership of uploads and their derived resources. Unknown ownership grants nothing. */
public interface UploadOwnershipPort {
    boolean ownsProject(UUID projectId, String actorId);
    boolean ownsContract(UUID contractId, UUID projectId, String actorId);
    boolean ownsBudgetTask(UUID taskId, String actorId);
    default boolean ownsProcurementBatch(String batchId, String actorId) { return false; }
}
