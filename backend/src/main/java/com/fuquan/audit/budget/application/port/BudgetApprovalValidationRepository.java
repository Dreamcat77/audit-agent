package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetApprovalValidation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetApprovalValidationRepository {
    void create(BudgetApprovalValidation validation);

    Optional<BudgetApprovalValidation> findById(UUID id);

    List<BudgetApprovalValidation> findPage(UUID taskId, int offset, int limit);

    long count(UUID taskId);
}
