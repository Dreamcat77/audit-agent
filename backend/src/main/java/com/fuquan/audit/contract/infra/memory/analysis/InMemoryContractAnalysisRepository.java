package com.fuquan.audit.contract.infra.memory.analysis;

import com.fuquan.audit.contract.application.analysis.*;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryContractAnalysisRepository implements ContractAnalysisRepository {
    private final Map<UUID,ContractAnalysisCase> values=new HashMap<>();
    private final Function<UUID,String> projectNames;
    public InMemoryContractAnalysisRepository(){this.projectNames=id->null;}
    @Autowired public InMemoryContractAnalysisRepository(ProjectRepository projects){this.projectNames=id->projects.findById(id).map(value->value.name()).orElse(null);}
    public synchronized Optional<ContractAnalysisCase> find(UUID id){return Optional.ofNullable(values.get(id));}
    public synchronized void insert(ContractAnalysisCase value){if(values.putIfAbsent(value.id(),value)!=null)throw new org.springframework.dao.DuplicateKeyException("CASE_EXISTS");}
    public synchronized List<ContractAnalysisCase> recent(String owner,int offset,int limit){return values.values().stream().filter(c->c.owner().equals(owner))
            .sorted(Comparator.comparing(ContractAnalysisCase::createdAt).reversed().thenComparing(ContractAnalysisCase::id)).skip(offset).limit(limit).toList();}
    @Override public synchronized List<ContractAnalysisCase> recent(String owner,int offset,int limit,String projectName,Instant from,Instant to){
        return values.values().stream().filter(c->c.owner().equals(owner))
                .filter(c->projectName==null||projectName.isBlank()||ContractAnalysisProjectNames.matches(c,projectNames.apply(c.projectId()),projectName))
                .filter(c->from==null||!c.createdAt().isBefore(from)).filter(c->to==null||c.createdAt().isBefore(to))
                .sorted(Comparator.comparing(ContractAnalysisCase::createdAt).reversed().thenComparing(ContractAnalysisCase::id))
                .skip(offset).limit(limit).toList();
    }
    public synchronized List<UUID> pending(Instant now,int limit){return values.values().stream().filter(c->c.claimable(now))
            .sorted(Comparator.comparingLong(ContractAnalysisCase::version).thenComparing(ContractAnalysisCase::createdAt).thenComparing(ContractAnalysisCase::id)).limit(limit).map(ContractAnalysisCase::id).toList();}
    public synchronized boolean replace(ContractAnalysisCase previous,ContractAnalysisCase next,Instant now){
        var current=values.get(previous.id());
        if(current==null||current.version()!=previous.version()||!Objects.equals(current.leaseToken(),previous.leaseToken()))return false;
        boolean claim=next.status()==ContractAnalysisCase.State.RUNNING&&!Objects.equals(next.leaseToken(),previous.leaseToken());
        if(claim ? !current.claimable(now) : previous.leaseToken()!=null&&!previous.leaseUntil().isAfter(now))return false;
        ContractAnalysisCase.verifyTransition(previous,next);
        if(!next.id().equals(previous.id())||next.version()!=previous.version()+1||!next.projectId().equals(previous.projectId())
                ||!next.owner().equals(previous.owner())||!next.inputDigest().equals(previous.inputDigest()))throw new IllegalArgumentException("case identity mutation");
        values.put(next.id(),next);return true;
    }
}
