package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetReviewItemRepository;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetReviewItemRepository implements BudgetReviewItemRepository {

    private final Map<UUID, BudgetReviewItem> items = new ConcurrentHashMap<>();

    @Override
    public void saveAll(List<BudgetReviewItem> itemsToSave) {
        itemsToSave.forEach(item -> items.put(item.id(), item));
    }

    @Override
    public void deleteByTaskAndRow(UUID taskId, int rowNumber) {
        items.entrySet().removeIf(entry ->
                taskId.equals(entry.getValue().taskId())
                        && Integer.valueOf(rowNumber).equals(entry.getValue().rowNumber()));
    }

    @Override
    public List<BudgetReviewItem> findByTaskId(UUID taskId) {
        return items.values().stream()
                .filter(item -> taskId.equals(item.taskId()))
                .sorted(java.util.Comparator.comparing(
                        BudgetReviewItem::rowNumber,
                        java.util.Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    @Override
    public Optional<BudgetReviewItem> findById(UUID id) {
        return Optional.ofNullable(items.get(id));
    }
}
