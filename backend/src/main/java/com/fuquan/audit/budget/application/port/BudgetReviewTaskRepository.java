package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetReviewTask;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface BudgetReviewTaskRepository {

    void save(BudgetReviewTask task);
    default List<BudgetReviewTask> timedOutForCleanup(int limit){return List.of();}

    Optional<BudgetReviewTask> findById(UUID id);

    /**
     * Returns the task while holding the persistence transaction's write lock when supported.
     * Local/test adapters keep the ordinary lookup semantics.
     */
    default Optional<BudgetReviewTask> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    default List<BudgetReviewTask> findPage(
            String batchId, String budgetYear, String departmentCode, int offset, int limit) {
        return List.of();
    }

    default long count(String batchId, String budgetYear, String departmentCode) {
        return 0L;
    }

    default List<BudgetReviewTask> findHistoryPage(String batchId, String budgetYear,
            String departmentCode, BudgetHistoryFilter filter, int offset, int limit) {
        throw new UnsupportedOperationException("History filtering is not implemented");
    }

    default long countHistory(String batchId, String budgetYear,
            String departmentCode, BudgetHistoryFilter filter) {
        throw new UnsupportedOperationException("History filtering is not implemented");
    }
}
