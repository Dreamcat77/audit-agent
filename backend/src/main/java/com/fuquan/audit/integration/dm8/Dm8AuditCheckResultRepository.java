package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8AuditCheckResultRepository implements AuditCheckResultRepository {

    private final AuditCheckResultMapper mapper;

    public Dm8AuditCheckResultRepository(AuditCheckResultMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendAll(List<AuditCheckResult> results) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("results must not be empty");
        }
        for (AuditCheckResult result : results) {
            if (mapper.insert(toRow(result)) != 1) {
                throw new IllegalStateException("audit check result insert did not affect one row");
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditCheckResult> findByTaskId(UUID taskId) {
        return mapper.findByTaskId(taskId.toString()).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditCheckResult> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id.toString())).map(this::toDomain);
    }

    private AuditCheckResultPersistenceRow toRow(AuditCheckResult result) {
        return new AuditCheckResultPersistenceRow(
                result.id().toString(), result.taskId().toString(), result.ruleCode(), result.itemKey(),
                result.checkStatus().name(), result.riskLevel().name(), result.blockSign() ? 1 : 0,
                result.actualValue(), result.expectedValue(), result.detail(), result.ruleVersion(),
                LocalDateTime.ofInstant(result.createdAt(), ZoneOffset.UTC));
    }

    private AuditCheckResult toDomain(AuditCheckResultPersistenceRow row) {
        return new AuditCheckResult(
                UUID.fromString(row.id()), UUID.fromString(row.taskId()), row.ruleCode(), row.itemKey(),
                CheckStatus.valueOf(row.checkStatus()), RiskLevel.valueOf(row.riskLevel()),
                row.blockSign() == 1, row.actualValue(), row.expectedValue(), row.detail(),
                row.ruleVersion(), row.createdAt().toInstant(ZoneOffset.UTC));
    }
}
