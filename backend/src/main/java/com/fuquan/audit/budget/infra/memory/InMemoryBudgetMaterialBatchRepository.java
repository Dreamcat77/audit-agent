package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetMaterialBatchRepository;
import com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository;
import com.fuquan.audit.budget.domain.BudgetMaterialBatch;
import com.fuquan.audit.budget.domain.BudgetMaterialBatch.Source;
import com.fuquan.audit.budget.application.port.BudgetHistoryFilter;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository @Profile("local")
public class InMemoryBudgetMaterialBatchRepository implements BudgetMaterialBatchRepository {
    private final Map<UUID,BudgetMaterialBatch> batches = new LinkedHashMap<>();
    private final Map<UUID,Source> sources = new LinkedHashMap<>();
    private final com.fuquan.audit.budget.application.port.BudgetMaterialJobRepository jobs;
    private final BudgetReviewTaskRepository tasks;
    public InMemoryBudgetMaterialBatchRepository(com.fuquan.audit.budget.application.port.BudgetMaterialJobRepository jobs,
            BudgetReviewTaskRepository tasks){this.jobs=jobs;this.tasks=tasks;}
    public synchronized Optional<BudgetMaterialBatch> find(UUID id) { return Optional.ofNullable(batches.get(id)); }
    public synchronized List<BudgetMaterialBatchRepository.HistoryReference> findHistoryPage(String actorId, BudgetHistoryFilter filter, int offset, int limit) {
        return filtered(actorId, filter).stream().skip(offset).limit(limit).toList();
    }
    public synchronized long countHistory(String actorId, BudgetHistoryFilter filter) {
        return filtered(actorId, filter).size();
    }
    private List<BudgetMaterialBatchRepository.HistoryReference> filtered(String actorId, BudgetHistoryFilter filter) {
        String keyword = filter.fileName() == null ? null : filter.fileName().toLowerCase(Locale.ROOT);
        var references = new ArrayList<BudgetMaterialBatchRepository.HistoryReference>();
        batches.values().stream()
                .filter(batch -> batch.actorId().equals(actorId))
                .filter(batch -> keyword == null || sources(batch.id()).stream()
                        .map(source -> tasks.findById(source.taskId()).map(task -> task.fileName()).orElse(""))
                        .anyMatch(name -> name.toLowerCase(Locale.ROOT).contains(keyword)))
                .filter(batch -> filter.createdFrom() == null || !batch.createdAt().isBefore(filter.createdFrom()))
                .filter(batch -> filter.createdBefore() == null || batch.createdAt().isBefore(filter.createdBefore()))
                .forEach(batch -> references.add(BudgetMaterialBatchRepository.HistoryReference.batch(batch.id(), batch.createdAt())));
        tasks.findHistoryPage(null, null, null, filter, 0, Integer.MAX_VALUE).stream()
                .filter(task -> !sources.containsKey(task.id()))
                .filter(task -> jobs.find(task.id()).map(job -> job.actorId().equals(actorId)).orElse(false))
                .forEach(task -> references.add(BudgetMaterialBatchRepository.HistoryReference.task(task.id(), task.createdTime())));
        return references.stream().sorted(Comparator.comparing(BudgetMaterialBatchRepository.HistoryReference::createdAt).reversed()
                .thenComparing(reference -> reference.resourceId().toString(), Comparator.reverseOrder())).toList();
    }
    public synchronized void insert(BudgetMaterialBatch b) {
        if(batches.putIfAbsent(b.id(),b)!=null) throw new DuplicateKeyException("duplicate budget batch");
    }
    public synchronized List<BudgetMaterialBatch> pending(Instant now,int limit) {
        return batches.values().stream().filter(b->!b.status().equals("REVIEWED")
                && (b.leaseUntil()==null || !b.leaseUntil().isAfter(now))
                && sources(b.id()).size()==b.members().size()
                && sources(b.id()).stream().allMatch(s->jobs.find(s.taskId()).map(j->Set.of("PARSED","FAILED","UNKNOWN").contains(j.status())).orElse(false))).limit(limit).toList();
    }
    public synchronized boolean claim(UUID id,String token,Instant now,Instant until) {
        var b=batches.get(id);
        if(b==null || b.status().equals("REVIEWED") || b.leaseUntil()!=null&&b.leaseUntil().isAfter(now)) return false;
        batches.put(id,new BudgetMaterialBatch(id,b.actorId(),b.budgetYear(),b.departmentCode(),b.members(),"PROCESSING",token,until,b.createdAt()));
        return true;
    }
    public synchronized void requireLease(UUID id,String token) {
        var b=batches.get(id);
        if(b==null||!b.status().equals("PROCESSING")||!Objects.equals(token,b.leaseToken())) throw new IllegalStateException("budget batch lease lost");
    }
    public synchronized void complete(UUID id,String token) {
        requireLease(id,token);var b=batches.get(id);
        batches.put(id,new BudgetMaterialBatch(id,b.actorId(),b.budgetYear(),b.departmentCode(),b.members(),"REVIEWED",token,null,b.createdAt()));
    }
    public synchronized void attach(Source s) {
        if(sources.putIfAbsent(s.taskId(),s)!=null) throw new DuplicateKeyException("duplicate budget source");
    }
    public synchronized Optional<Source> source(UUID id) { return Optional.ofNullable(sources.get(id)); }
    public synchronized List<Source> sources(UUID id) { return sources.values().stream().filter(s->s.batchId().equals(id)).toList(); }
    public synchronized void extracted(UUID id,String json) {
        var s=source(id).orElseThrow();
        if(s.factsJson()!=null) throw new IllegalStateException("immutable budget source");
        sources.put(id,new Source(id,s.batchId(),json,s.resultJson()));
    }
    public synchronized void result(UUID id,String json) {
        var s=source(id).orElseThrow();sources.put(id,new Source(id,s.batchId(),s.factsJson(),json));
    }
}
