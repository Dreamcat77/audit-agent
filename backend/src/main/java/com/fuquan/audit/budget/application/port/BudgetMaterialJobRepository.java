package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetMaterialJob;
import java.time.Instant;
import java.util.*;

public interface BudgetMaterialJobRepository {
    Optional<BudgetMaterialJob> find(UUID id);
    Optional<BudgetMaterialJob> operation(String actor, String key);
    List<BudgetMaterialJob> pending(Instant now, int limit);
    void insert(BudgetMaterialJob job);
    boolean claim(UUID id, String token, Instant now, Instant until);
    boolean renew(UUID id, String token, Instant until);
    boolean complete(UUID id, String token, String status, Instant now);
    /** Hold the job row lock until the surrounding short result transaction commits. */
    void requireLease(UUID id, String token);
}
