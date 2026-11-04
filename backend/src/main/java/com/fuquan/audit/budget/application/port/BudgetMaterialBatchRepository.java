package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.application.port.BudgetHistoryFilter;
import com.fuquan.audit.budget.domain.BudgetMaterialBatch;
import com.fuquan.audit.budget.domain.BudgetMaterialBatch.Source;
import java.time.Instant;
import java.util.*;

public interface BudgetMaterialBatchRepository {
    record HistoryReference(UUID batchId, UUID taskId, Instant createdAt) {
        public HistoryReference {
            if ((batchId == null) == (taskId == null)) throw new IllegalArgumentException("history reference must identify one resource");
        }
        public static HistoryReference batch(UUID id, Instant createdAt) { return new HistoryReference(id, null, createdAt); }
        public static HistoryReference task(UUID id, Instant createdAt) { return new HistoryReference(null, id, createdAt); }
        public UUID resourceId() { return batchId != null ? batchId : taskId; }
    }
    Optional<BudgetMaterialBatch> find(UUID id);
    List<HistoryReference> findHistoryPage(String actorId, BudgetHistoryFilter filter, int offset, int limit);
    long countHistory(String actorId, BudgetHistoryFilter filter);
    void insert(BudgetMaterialBatch batch);
    List<BudgetMaterialBatch> pending(Instant now, int limit);
    boolean claim(UUID id, String token, Instant now, Instant until);
    void requireLease(UUID id, String token);
    void complete(UUID id, String token);
    void attach(Source source);
    Optional<Source> source(UUID taskId);
    List<Source> sources(UUID batchId);
    void extracted(UUID taskId, String factsJson);
    void result(UUID taskId, String resultJson);
}
