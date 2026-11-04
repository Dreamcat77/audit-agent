package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BudgetReviewTaskRepository implements BudgetReviewTaskRepository {

    private final BudgetReviewTaskMapper mapper;

    public Dm8BudgetReviewTaskRepository(BudgetReviewTaskMapper mapper) {
        this.mapper = mapper;
    }
    public List<BudgetReviewTask> timedOutForCleanup(int limit){return mapper.timedOutForCleanup(limit).stream().map(Dm8BudgetReviewTaskRepository::toDomain).toList();}

    @Override
    @Transactional
    public void save(BudgetReviewTask task) {
        BudgetReviewTaskPersistenceRow row = toRow(task);
        if (mapper.findById(row.getId()) == null) {
            mapper.insert(row);
        } else {
            mapper.update(row);
        }
    }

    @Override
    public Optional<BudgetReviewTask> findById(UUID id) {
        BudgetReviewTaskPersistenceRow row = mapper.findById(id.toString());
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BudgetReviewTask> findByIdForUpdate(UUID id) {
        BudgetReviewTaskPersistenceRow row = mapper.findByIdForUpdate(id.toString());
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    private static BudgetReviewTaskPersistenceRow toRow(BudgetReviewTask task) {
        BudgetReviewTaskPersistenceRow row = new BudgetReviewTaskPersistenceRow();
        row.setId(task.id().toString());
        row.setFileName(task.fileName());
        row.setContentType(task.contentType());
        row.setFileSize(task.fileSize());
        row.setTempFilePath(task.tempFilePath());
        row.setStatus(task.status());
        row.setParsedLineCount(task.parsedLineCount());
        row.setErrorDetail(task.errorDetail());
        row.setBudgetYear(task.budgetYear());
        row.setDepartmentCode(task.departmentCode());
        row.setReviewCursor(task.reviewCursor());
        row.setPendingRowNumber(task.pendingRowNumber());
        row.setPendingStep(task.pendingStep());
        row.setPendingReason(task.pendingReason());
        row.setCreatedTime(java.time.LocalDateTime.ofInstant(task.createdTime(), ZoneOffset.UTC));
        row.setUpdatedTime(java.time.LocalDateTime.ofInstant(task.updatedTime(), ZoneOffset.UTC));
        return row;
    }

    private static BudgetReviewTask toDomain(BudgetReviewTaskPersistenceRow row) {
        return new BudgetReviewTask(
                UUID.fromString(row.getId()),
                row.getFileName(),
                row.getContentType(),
                row.getFileSize() == null ? 0L : row.getFileSize(),
                row.getTempFilePath(),
                row.getStatus(),
                row.getParsedLineCount() == null ? 0 : row.getParsedLineCount(),
                row.getErrorDetail(),
                row.getBudgetYear(),
                row.getDepartmentCode(),
                row.getReviewCursor() == null ? 0 : row.getReviewCursor(),
                row.getPendingRowNumber(),
                row.getPendingStep(),
                row.getPendingReason(),
                row.getCreatedTime().toInstant(ZoneOffset.UTC),
                row.getUpdatedTime().toInstant(ZoneOffset.UTC));
    }

    @Override
    public List<BudgetReviewTask> findPage(
            String batchId, String budgetYear, String departmentCode, int offset, int limit) {
        return mapper.findPage(batchId, budgetYear, departmentCode, null, null, null, offset, limit).stream()
                .map(Dm8BudgetReviewTaskRepository::toDomain)
                .toList();
    }

    @Override
    public long count(String batchId, String budgetYear, String departmentCode) {
        return mapper.count(batchId, budgetYear, departmentCode, null, null, null);
    }

    @Override
    public List<BudgetReviewTask> findHistoryPage(String batchId, String budgetYear, String departmentCode,
            com.fuquan.audit.budget.application.port.BudgetHistoryFilter filter, int offset, int limit) {
        return mapper.findPage(batchId, budgetYear, departmentCode, filter.fileName(),
                utc(filter.createdFrom()), utc(filter.createdBefore()), offset, limit).stream()
                .map(Dm8BudgetReviewTaskRepository::toDomain).toList();
    }

    @Override
    public long countHistory(String batchId, String budgetYear, String departmentCode,
            com.fuquan.audit.budget.application.port.BudgetHistoryFilter filter) {
        return mapper.count(batchId, budgetYear, departmentCode, filter.fileName(),
                utc(filter.createdFrom()), utc(filter.createdBefore()));
    }

    private static java.time.LocalDateTime utc(java.time.Instant value) {
        return value == null ? null : java.time.LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
