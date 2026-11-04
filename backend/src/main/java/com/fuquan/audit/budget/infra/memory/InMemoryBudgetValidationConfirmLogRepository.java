package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetValidationConfirmLogRepository;
import com.fuquan.audit.budget.domain.BudgetValidationConfirmLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetValidationConfirmLogRepository
        implements BudgetValidationConfirmLogRepository {

    private final AtomicLong sequence = new AtomicLong();
    private final List<BudgetValidationConfirmLog> logs = new CopyOnWriteArrayList<>();

    @Override
    public void save(BudgetValidationConfirmLog log) {
        logs.add(new BudgetValidationConfirmLog(
                log.id() == 0 ? sequence.incrementAndGet() : log.id(),
                log.batchId(), log.rowNumber(), log.departmentCode(), log.departmentName(),
                log.confirmerUserId(), log.confirmerName(), log.confirmTime(),
                log.confirmStep(), log.abnormalReason(), log.remark(), log.createdAt()));
    }

    @Override
    public List<BudgetValidationConfirmLog> findByBatchId(UUID batchId) {
        return new ArrayList<>(logs).stream()
                .filter(log -> batchId.toString().equals(log.batchId()))
                .sorted(Comparator.comparing(BudgetValidationConfirmLog::confirmTime))
                .toList();
    }
}
