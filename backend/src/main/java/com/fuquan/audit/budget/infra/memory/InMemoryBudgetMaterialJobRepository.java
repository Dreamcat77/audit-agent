package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.application.port.BudgetMaterialJobRepository;
import com.fuquan.audit.budget.domain.BudgetMaterialJob;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBudgetMaterialJobRepository implements BudgetMaterialJobRepository {
    private final Map<UUID,BudgetMaterialJob> jobs=new LinkedHashMap<>();
    public synchronized Optional<BudgetMaterialJob> find(UUID id) {return Optional.ofNullable(jobs.get(id));}
    public synchronized Optional<BudgetMaterialJob> operation(String actor,String key) {return jobs.values().stream().filter(j->j.actorId().equals(actor)&&j.operationKey().equals(key)).findFirst();}
    public synchronized List<BudgetMaterialJob> pending(Instant now,int limit) {return jobs.values().stream().filter(j->j.status().equals("QUEUED")||j.status().equals("PROCESSING")&&!j.leaseUntil().isAfter(now)).limit(limit).toList();}
    public synchronized void insert(BudgetMaterialJob j) {
        if(jobs.containsKey(j.id())||operation(j.actorId(),j.operationKey()).isPresent()) throw new DuplicateKeyException("duplicate budget operation");jobs.put(j.id(),j);
    }
    public synchronized boolean claim(UUID id,String token,Instant now,Instant until) {
        var j=jobs.get(id);if(j==null||!(j.status().equals("QUEUED")||j.status().equals("PROCESSING")&&!j.leaseUntil().isAfter(now))) return false;
        jobs.put(id,new BudgetMaterialJob(id,j.actorId(),j.operationKey(),j.sha256(),"PROCESSING",token,until,now));return true;
    }
    public synchronized boolean renew(UUID id,String token,Instant until) {
        var j=jobs.get(id);if(j==null||!j.status().equals("PROCESSING")||!Objects.equals(token,j.leaseToken())) return false;
        jobs.put(id,new BudgetMaterialJob(id,j.actorId(),j.operationKey(),j.sha256(),j.status(),token,until,j.updatedAt()));return true;
    }
    public synchronized boolean complete(UUID id,String token,String status,Instant now) {
        var j=jobs.get(id);if(j==null||!j.status().equals("PROCESSING")||!Objects.equals(token,j.leaseToken())) return false;
        jobs.put(id,new BudgetMaterialJob(id,j.actorId(),j.operationKey(),j.sha256(),status,token,j.leaseUntil(),now));return true;
    }
    public synchronized void requireLease(UUID id,String token) {
        var j=jobs.get(id);if(j==null||!j.status().equals("PROCESSING")||!Objects.equals(token,j.leaseToken())) throw new IllegalStateException("budget material lease lost");
    }
}
