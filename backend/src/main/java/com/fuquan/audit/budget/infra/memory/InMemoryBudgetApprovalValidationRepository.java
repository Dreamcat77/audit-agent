package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetApprovalValidationRepository;
import com.fuquan.audit.budget.domain.BudgetApprovalValidation;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetApprovalValidationRepository implements BudgetApprovalValidationRepository {

    private final Map<UUID, BudgetApprovalValidation> validations = new ConcurrentHashMap<>();

    @Override
    public void create(BudgetApprovalValidation validation) {
        if (validations.putIfAbsent(validation.id(), validation) != null) {
            throw new IllegalStateException("budget approval validation already exists");
        }
    }

    @Override
    public Optional<BudgetApprovalValidation> findById(UUID id) {
        return Optional.ofNullable(validations.get(id));
    }

    @Override
    public List<BudgetApprovalValidation> findPage(UUID taskId, int offset, int limit) {
        return filtered(taskId).stream()
                .sorted(Comparator.comparing(BudgetApprovalValidation::createdAt).reversed()
                        .thenComparing(BudgetApprovalValidation::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long count(UUID taskId) {
        return filtered(taskId).size();
    }

    private List<BudgetApprovalValidation> filtered(UUID taskId) {
        return validations.values().stream()
                .filter(validation -> taskId == null || taskId.equals(validation.sourceTaskId()))
                .toList();
    }
}
