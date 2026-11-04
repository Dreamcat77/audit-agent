package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.port.BudgetValidationConfirmLogRepository;
import com.fuquan.audit.budget.domain.BudgetValidationConfirmLog;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BudgetValidationConfirmLogRepository
        implements BudgetValidationConfirmLogRepository {

    private final BudgetValidationConfirmLogMapper mapper;

    public Dm8BudgetValidationConfirmLogRepository(BudgetValidationConfirmLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(BudgetValidationConfirmLog log) {
        BudgetValidationConfirmLogPersistenceRow row = new BudgetValidationConfirmLogPersistenceRow();
        row.setBatchId(log.batchId());
        row.setRowNumber(log.rowNumber());
        row.setDepartmentCode(log.departmentCode());
        row.setDepartmentName(log.departmentName());
        row.setConfirmerUserId(log.confirmerUserId());
        row.setConfirmerName(log.confirmerName());
        row.setConfirmTime(java.time.LocalDateTime.ofInstant(log.confirmTime(), ZoneOffset.UTC));
        row.setConfirmStep(log.confirmStep());
        row.setAbnormalReason(log.abnormalReason());
        row.setRemark(log.remark());
        row.setCreatedAt(java.time.LocalDateTime.ofInstant(log.createdAt(), ZoneOffset.UTC));
        mapper.insert(row);
    }

    @Override
    public List<BudgetValidationConfirmLog> findByBatchId(UUID batchId) {
        return mapper.findByBatchId(batchId.toString()).stream()
                .map(Dm8BudgetValidationConfirmLogRepository::toDomain)
                .toList();
    }

    private static BudgetValidationConfirmLog toDomain(
            BudgetValidationConfirmLogPersistenceRow row) {
        return new BudgetValidationConfirmLog(
                row.getId() == null ? 0L : row.getId(),
                row.getBatchId(),
                row.getRowNumber() == null ? 0 : row.getRowNumber(),
                row.getDepartmentCode(),
                row.getDepartmentName(),
                row.getConfirmerUserId(),
                row.getConfirmerName(),
                row.getConfirmTime().toInstant(ZoneOffset.UTC),
                row.getConfirmStep(),
                row.getAbnormalReason(),
                row.getRemark(),
                row.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
