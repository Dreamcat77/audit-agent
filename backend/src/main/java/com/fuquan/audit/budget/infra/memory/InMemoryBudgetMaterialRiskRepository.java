package com.fuquan.audit.budget.infra.memory;
import com.fuquan.audit.budget.application.BudgetMaterialRisk.Result;
import com.fuquan.audit.budget.application.BudgetMaterialRisk.Source;
import com.fuquan.audit.budget.application.BudgetMaterialRisk.Position;
import com.fuquan.audit.budget.application.port.BudgetMaterialRiskRepository;
import com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository;
import com.fuquan.audit.budget.application.port.BudgetMaterialJobRepository;
import com.fuquan.audit.budget.application.port.BudgetMaterialBatchRepository;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DuplicateKeyException;
@Repository @Profile("local")
public class InMemoryBudgetMaterialRiskRepository implements BudgetMaterialRiskRepository {
    private final Map<UUID,Result> values=new LinkedHashMap<>();
    private final BudgetReviewTaskRepository tasks;
    private final BudgetMaterialJobRepository jobs;
    private final BudgetMaterialBatchRepository batches;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public InMemoryBudgetMaterialRiskRepository(BudgetReviewTaskRepository tasks,BudgetMaterialJobRepository jobs,BudgetMaterialBatchRepository batches,com.fasterxml.jackson.databind.ObjectMapper json){this.tasks=tasks;this.jobs=jobs;this.batches=batches;this.json=json;}
    public synchronized Optional<Result> find(UUID id,String actor){return Optional.ofNullable(values.get(id)).filter(r->r.actorId().equals(actor));}
    public synchronized void insert(Result r){if(values.putIfAbsent(r.id(),r)!=null)throw new DuplicateKeyException("risk operation exists");}
    public synchronized List<Result> history(String actor,int offset,int limit){return values.values().stream().filter(r->r.actorId().equals(actor)).sorted(Comparator.comparing(Result::createdAt).reversed().thenComparing(r->r.id().toString())).skip(offset).limit(limit).toList();}
    public List<Source> sources(String actor,Position position,String keyword,int limit) {
        return tasks.findPage(null,null,null,0,Integer.MAX_VALUE).stream()
                .filter(t->jobs.find(t.id()).filter(j->j.actorId().equals(actor)&&j.status().equals("PARSED")).isPresent())
                .map(t->new Source(t.id(),t.fileName(),t.createdTime(),batches.source(t.id()).map(s->s.resultJson()).orElse(null)))
                .filter(s->s.resultJson()!=null)
                .filter(s->position==null||s.createdAt().isBefore(position.createdAt())||s.createdAt().equals(position.createdAt())&&s.taskId().toString().compareTo(position.taskId().toString())<=0)
                .filter(s->keyword==null||s.fileName().contains(keyword)||s.resultJson().contains(keyword))
                .sorted(Comparator.comparing(Source::createdAt).reversed().thenComparing(s->s.taskId().toString(),Comparator.reverseOrder()))
                .limit(limit).toList();
    }
    private java.util.stream.Stream<Result> matching(String actor,String keyword,java.time.Instant from,java.time.Instant before) {
        return values.values().stream().filter(r->r.actorId().equals(actor))
                .filter(r->from==null||!java.time.Instant.parse(r.createdAt()).isBefore(from))
                .filter(r->before==null||java.time.Instant.parse(r.createdAt()).isBefore(before))
                .filter(r->{if(keyword==null)return true;try{return json.writeValueAsString(r).contains(keyword);}catch(Exception e){throw new IllegalStateException(e);}});
    }
    public synchronized List<Result> searchHistory(String actor,String keyword,java.time.Instant from,java.time.Instant before,int offset,int limit) {
        return matching(actor,keyword,from,before).sorted(Comparator.comparing(Result::createdAt).reversed().thenComparing(r->r.id().toString())).skip(offset).limit(limit).toList();
    }
    public synchronized long countHistory(String actor,String keyword,java.time.Instant from,java.time.Instant before){return matching(actor,keyword,from,before).count();}
}
