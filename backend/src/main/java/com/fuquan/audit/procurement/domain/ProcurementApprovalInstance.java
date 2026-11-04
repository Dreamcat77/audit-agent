package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 采购需求审批流程实例。 */
public record ProcurementApprovalInstance(
        UUID id, UUID projectId, String status, String currentNodeCode,
        String startedByUserId, String startedByName, Instant startedTime,
        Instant completedTime, Instant createdTime, Instant updatedTime, String returnSnapshot) {

    public ProcurementApprovalInstance(UUID id, UUID projectId, String status, String currentNodeCode,
                                       String startedByUserId, String startedByName, Instant startedTime,
                                       Instant completedTime, Instant createdTime, Instant updatedTime) {
        this(id, projectId, status, currentNodeCode, startedByUserId, startedByName, startedTime,
                completedTime, createdTime, updatedTime, null);
    }

    public ProcurementApprovalInstance {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status must not be blank");
        if (startedByUserId == null || startedByUserId.isBlank()) {
            throw new IllegalArgumentException("startedByUserId must not be blank");
        }
        if (startedByName == null || startedByName.isBlank()) {
            throw new IllegalArgumentException("startedByName must not be blank");
        }
        Objects.requireNonNull(startedTime, "startedTime must not be null");
        Objects.requireNonNull(createdTime, "createdTime must not be null");
        Objects.requireNonNull(updatedTime, "updatedTime must not be null");
    }
}
