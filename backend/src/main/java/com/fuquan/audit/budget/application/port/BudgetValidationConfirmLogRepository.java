package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetValidationConfirmLog;
import java.util.List;
import java.util.UUID;

public interface BudgetValidationConfirmLogRepository {

    void save(BudgetValidationConfirmLog log);

    List<BudgetValidationConfirmLog> findByBatchId(UUID batchId);
}
