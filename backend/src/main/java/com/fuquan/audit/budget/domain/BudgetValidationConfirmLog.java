package com.fuquan.audit.budget.domain;

import java.time.Instant;
import java.util.Objects;

public record BudgetValidationConfirmLog(
        long id,
        String batchId,
        int rowNumber,
        String departmentCode,
        String departmentName,
        String confirmerUserId,
        String confirmerName,
        Instant confirmTime,
        String confirmStep,
        String abnormalReason,
        String remark,
        Instant createdAt) {

    public BudgetValidationConfirmLog {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId must not be blank");
        }
        if (confirmerUserId == null || confirmerUserId.isBlank()) {
            throw new IllegalArgumentException("confirmerUserId must not be blank");
        }
        if (confirmerName == null || confirmerName.isBlank()) {
            throw new IllegalArgumentException("confirmerName must not be blank");
        }
        if (confirmStep == null || confirmStep.isBlank()) {
            throw new IllegalArgumentException("confirmStep must not be blank");
        }
        Objects.requireNonNull(confirmTime, "confirmTime must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
