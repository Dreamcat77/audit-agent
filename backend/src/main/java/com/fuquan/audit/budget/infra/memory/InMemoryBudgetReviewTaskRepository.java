package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository;
import com.fuquan.audit.budget.application.port.BudgetHistoryFilter;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetReviewTaskRepository implements BudgetReviewTaskRepository {

    private final Map<UUID, BudgetReviewTask> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(BudgetReviewTask task) {
        tasks.put(task.id(), task);
    }

    @Override
    public Optional<BudgetReviewTask> findById(UUID id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public List<BudgetReviewTask> findPage(
            String batchId, String budgetYear, String departmentCode, int offset, int limit) {
        return filtered(batchId, budgetYear, departmentCode).stream()
                .sorted(Comparator.comparing(BudgetReviewTask::createdTime).reversed())
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long count(String batchId, String budgetYear, String departmentCode) {
        return filtered(batchId, budgetYear, departmentCode).size();
    }

    private List<BudgetReviewTask> filtered(
            String batchId, String budgetYear, String departmentCode) {
        return tasks.values().stream()
                .filter(task -> !java.util.Set.of("MATERIAL_CALL_TIMEOUT","MATERIAL_TIME_LIMIT","MATERIAL_QUEUE_TIMEOUT").contains(java.util.Objects.toString(task.errorDetail(),"")))
                .filter(task -> isBlank(batchId) || task.id().toString().equals(batchId))
                .filter(task -> isBlank(budgetYear) || budgetYear.equals(task.budgetYear()))
                .filter(task -> isBlank(departmentCode) || departmentCode.equals(task.departmentCode()))
                .toList();
    }
    public List<BudgetReviewTask> timedOutForCleanup(int limit){return tasks.values().stream().filter(t->t.status().equals("FAILED")&&t.parsedLineCount()==0&&t.tempFilePath()!=null&&java.util.Set.of("MATERIAL_CALL_TIMEOUT","MATERIAL_TIME_LIMIT","MATERIAL_QUEUE_TIMEOUT").contains(java.util.Objects.toString(t.errorDetail(),""))).limit(limit).toList();}

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public List<BudgetReviewTask> findHistoryPage(String batchId, String budgetYear, String departmentCode,
            BudgetHistoryFilter filter, int offset, int limit) {
        return history(batchId, budgetYear, departmentCode, filter)
                .sorted(Comparator.comparing(BudgetReviewTask::createdTime).reversed()
                        .thenComparing(task -> task.id().toString(), Comparator.reverseOrder()))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public long countHistory(String batchId, String budgetYear, String departmentCode, BudgetHistoryFilter filter) {
        return history(batchId, budgetYear, departmentCode, filter).count();
    }

    private java.util.stream.Stream<BudgetReviewTask> history(String batchId, String budgetYear,
            String departmentCode, BudgetHistoryFilter filter) {
        return filtered(batchId, budgetYear, departmentCode).stream()
                .filter(task -> filter.fileName() == null || task.fileName().toLowerCase(java.util.Locale.ROOT)
                        .contains(filter.fileName().toLowerCase(java.util.Locale.ROOT)))
                .filter(task -> filter.createdFrom() == null || !task.createdTime().isBefore(filter.createdFrom()))
                .filter(task -> filter.createdBefore() == null || task.createdTime().isBefore(filter.createdBefore()));
    }
}
