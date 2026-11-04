package com.fuquan.audit.budget.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BudgetReviewTask(
        UUID id,
        String fileName,
        String contentType,
        long fileSize,
        String tempFilePath,
        String status,
        int parsedLineCount,
        String errorDetail,
        String budgetYear,
        String departmentCode,
        int reviewCursor,
        Integer pendingRowNumber,
        String pendingStep,
        String pendingReason,
        Instant createdTime,
        Instant updatedTime) {

    public BudgetReviewTask {
        Objects.requireNonNull(id, "id must not be null");
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (fileSize < 0) {
            throw new IllegalArgumentException("fileSize must not be negative");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        Objects.requireNonNull(createdTime, "createdTime must not be null");
        Objects.requireNonNull(updatedTime, "updatedTime must not be null");
    }

    public BudgetReviewTask withStatus(String nextStatus, int nextLineCount, String nextError) {
        return new BudgetReviewTask(
                id, fileName, contentType, fileSize, tempFilePath, nextStatus,
                nextLineCount, nextError, budgetYear, departmentCode, reviewCursor,
                pendingRowNumber, pendingStep, pendingReason, createdTime, Instant.now());
    }
    public BudgetReviewTask withoutStoredMaterial() {
        return new BudgetReviewTask(id,fileName,contentType,fileSize,null,status,parsedLineCount,errorDetail,budgetYear,
            departmentCode,reviewCursor,pendingRowNumber,pendingStep,pendingReason,createdTime,Instant.now());
    }

    public BudgetReviewTask withReviewState(
            String nextStatus,
            int nextCursor,
            Integer nextPendingRowNumber,
            String nextPendingStep,
            String nextPendingReason) {
        return new BudgetReviewTask(
                id, fileName, contentType, fileSize, tempFilePath, nextStatus,
                parsedLineCount, errorDetail, budgetYear, departmentCode, nextCursor,
                nextPendingRowNumber, nextPendingStep, nextPendingReason,
                createdTime, Instant.now());
    }

    public BudgetReviewTask(
            UUID id,
            String fileName,
            String contentType,
            long fileSize,
            String tempFilePath,
            String status,
            int parsedLineCount,
            String errorDetail,
            Instant createdTime,
            Instant updatedTime) {
        this(id, fileName, contentType, fileSize, tempFilePath, status, parsedLineCount,
                errorDetail, null, null, 0, null, null, null, createdTime, updatedTime);
    }

    public BudgetReviewTask(
            UUID id,
            String fileName,
            String contentType,
            long fileSize,
            String tempFilePath,
            String status,
            int parsedLineCount,
            String errorDetail,
            String budgetYear,
            String departmentCode,
            Instant createdTime,
            Instant updatedTime) {
        this(id, fileName, contentType, fileSize, tempFilePath, status, parsedLineCount,
                errorDetail, budgetYear, departmentCode, 0, null, null, null,
                createdTime, updatedTime);
    }
}
