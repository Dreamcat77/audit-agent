package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.BudgetApprovalStepSnapshotCodec;
import com.fuquan.audit.budget.application.port.BudgetApprovalValidationRepository;
import com.fuquan.audit.budget.domain.BudgetApprovalValidation;
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
public class Dm8BudgetApprovalValidationRepository implements BudgetApprovalValidationRepository {

    private final BudgetApprovalValidationMapper mapper;

    public Dm8BudgetApprovalValidationRepository(BudgetApprovalValidationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(BudgetApprovalValidation validation) {
        if (mapper.insert(toRow(validation)) != 1) {
            throw new IllegalStateException("budget approval validation insert did not affect exactly one row");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BudgetApprovalValidation> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id.toString())).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BudgetApprovalValidation> findPage(UUID taskId, int offset, int limit) {
        return mapper.findPage(taskId == null ? null : taskId.toString(), offset, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(UUID taskId) {
        return mapper.count(taskId == null ? null : taskId.toString());
    }

    private static BudgetApprovalValidationPersistenceRow toRow(BudgetApprovalValidation validation) {
        return new BudgetApprovalValidationPersistenceRow(
                validation.id().toString(),
                validation.ruleSetCode(),
                validation.sourceTaskId().toString(),
                validation.sourceItemId().toString(),
                validation.sourceRowNumber(),
                validation.declaredAmount(),
                validation.actualSource(),
                BudgetApprovalStepSnapshotCodec.encode(validation.requiredProcedureSteps()),
                validation.requiredAuthorityStep(),
                BudgetApprovalStepSnapshotCodec.encode(validation.actualSteps()),
                BudgetApprovalStepSnapshotCodec.encode(validation.missingProcedureSteps()),
                flag(validation.procedureOrderValid()),
                flag(validation.authorityMatched()),
                flag(validation.passed()),
                validation.resultSummary(),
                validation.createdBy(),
                LocalDateTime.ofInstant(validation.createdAt(), ZoneOffset.UTC));
    }

    private BudgetApprovalValidation toDomain(BudgetApprovalValidationPersistenceRow row) {
        return new BudgetApprovalValidation(
                UUID.fromString(row.id()),
                row.ruleSetCode(),
                UUID.fromString(row.sourceTaskId()),
                UUID.fromString(row.sourceItemId()),
                row.sourceRowNumber(),
                row.declaredAmount(),
                row.actualSource(),
                BudgetApprovalStepSnapshotCodec.decode(row.requiredProcedureSteps()),
                row.requiredAuthorityStep(),
                BudgetApprovalStepSnapshotCodec.decode(row.actualSteps()),
                BudgetApprovalStepSnapshotCodec.decode(row.missingProcedureSteps()),
                flag(row.procedureOrderValid()),
                flag(row.authorityMatched()),
                flag(row.passed()),
                row.resultSummary(),
                row.createdBy(),
                row.createdAt().toInstant(ZoneOffset.UTC));
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private static boolean flag(Integer value) {
        return Integer.valueOf(1).equals(value);
    }
}
