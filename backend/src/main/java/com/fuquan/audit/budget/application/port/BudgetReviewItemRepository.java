package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetReviewItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetReviewItemRepository {

    void saveAll(List<BudgetReviewItem> items);

    default void deleteByTaskAndRow(UUID taskId, int rowNumber) {
    }

    List<BudgetReviewItem> findByTaskId(UUID taskId);

    Optional<BudgetReviewItem> findById(UUID id);
}
