package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.port.BudgetApprovalCheckRepository;
import com.fuquan.audit.budget.domain.BudgetCheckResult;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BudgetApprovalCheckRepository implements BudgetApprovalCheckRepository {

    private final BudgetApprovalCheckMapper mapper;
    private final FlowDebugLogger flowDebugLogger;

    public Dm8BudgetApprovalCheckRepository(BudgetApprovalCheckMapper mapper, FlowDebugLogger flowDebugLogger) {
        this.mapper = mapper;
        this.flowDebugLogger = flowDebugLogger;
    }

    @Override
    @Transactional
    public void saveAll(List<BudgetCheckResult> results) {
        long startedAt = System.nanoTime();
        List<BudgetApprovalCheckPersistenceRow> rows = new ArrayList<>(results.size());
        for (BudgetCheckResult result : results) {
            rows.add(toRow(result));
        }
        if (!rows.isEmpty()) {
            mapper.insertBatch(rows);
        }
        flowDebugLogger.log("BUDGET_CHECK_PERSIST", "BUDGET", "REPOSITORY", "saveAll",
                Map.of("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                        "count", rows.size()));
    }

    private BudgetApprovalCheckPersistenceRow toRow(BudgetCheckResult result) {
        BudgetApprovalCheckPersistenceRow row = new BudgetApprovalCheckPersistenceRow();
        row.setId(result.id().toString());
        row.setProcurementId(result.procurementId());
        row.setCheckType(result.checkType());
        row.setSubjectCode(result.subjectCode());
        row.setExpectedValue(result.expectedValue());
        row.setActualValue(result.actualValue());
        row.setPassed(result.passed());
        row.setErrorDetail(result.errorDetail());
        row.setCheckTime(toLocalDateTime(result.checkTime()));
        return row;
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneOffset.UTC) : LocalDateTime.now();
    }
}
