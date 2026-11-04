package com.fuquan.audit.integration.dm8;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.analysis.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Profile({"local-dev","dev29","customer"})
public class Dm8ContractAnalysisRepository implements ContractAnalysisRepository {
    private final ContractAnalysisMapper mapper;private final ObjectMapper json;
    public Dm8ContractAnalysisRepository(ContractAnalysisMapper mapper,ObjectMapper json){this.mapper=mapper;this.json=json;}
    public Optional<ContractAnalysisCase> find(UUID id){return Optional.ofNullable(mapper.find(id.toString())).map(this::decode);}
    public List<ContractAnalysisCase> recent(String owner,int offset,int limit){return mapper.recent(owner,offset,Math.addExact(offset,limit)).stream().map(UUID::fromString).map(id->find(id).orElseThrow()).toList();}
    public List<ContractAnalysisCase> recent(String owner,int offset,int limit,String projectName,Instant from,Instant to){
        if(projectName==null||projectName.isBlank())return mapper.recentFiltered(owner,time(from),time(to),offset,Math.addExact(offset,limit))
                .stream().map(row->decode(row.payload())).toList();
        // Display names may come from grounded facts while PROJECT.NAME is still
        // generated. Read bounded batches, match the shared display policy, then
        // paginate matches. Never search arbitrary snapshot text or just one page.
        final int batchSize=100;
        var result=new ArrayList<ContractAnalysisCase>();
        int cursor=0,matched=0;
        while(true){
            var batch=mapper.recentFiltered(owner,time(from),time(to),cursor,Math.addExact(cursor,batchSize));
            for(var row:batch){
                var value=decode(row.payload());
                if(ContractAnalysisProjectNames.matches(value,row.projectName(),projectName)&&matched++>=offset){
                    result.add(value);
                    if(result.size()==limit)return List.copyOf(result);
                }
            }
            if(batch.size()<batchSize)return List.copyOf(result);
            cursor=Math.addExact(cursor,batch.size());
        }
    }
    public List<UUID> pending(Instant now,int limit){return mapper.pending(time(now),limit).stream().map(UUID::fromString).toList();}
    @Transactional(propagation=Propagation.MANDATORY)
    public void insert(ContractAnalysisCase c){if(mapper.insert(row(c))!=1)throw new IllegalStateException("CASE_INSERT_UNPROVEN");}
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean replace(ContractAnalysisCase p,ContractAnalysisCase n,Instant now){
        ContractAnalysisCase.verifyTransition(p,n);
        if(!p.id().equals(n.id())||n.version()!=p.version()+1||!p.projectId().equals(n.projectId())||!p.owner().equals(n.owner())||!p.inputDigest().equals(n.inputDigest()))
            throw new IllegalArgumentException("CASE_IDENTITY_MUTATION");
        boolean claim=n.status()==ContractAnalysisCase.State.RUNNING&&!Objects.equals(p.leaseToken(),n.leaseToken());
        return mapper.replace(row(n),p.version(),p.leaseToken(),claim,time(now))==1;
    }
    private ContractAnalysisCase decode(String value){try{return json.readValue(value,ContractAnalysisCase.class);}catch(Exception failure){throw new IllegalStateException("CASE_SNAPSHOT_INVALID",failure);}}
    private ContractAnalysisMapper.Row row(ContractAnalysisCase c){
        try{return new ContractAnalysisMapper.Row(c.id().toString(),c.projectId().toString(),c.owner(),c.inputDigest(),c.status().name(),c.version(),c.leaseToken(),time(c.leaseUntil()),time(c.createdAt()),time(c.deadline()),json.writeValueAsString(c));}
        catch(Exception failure){throw new IllegalStateException("CASE_SNAPSHOT_ENCODING_FAILED",failure);}
    }
    private static LocalDateTime time(Instant v){return v==null?null:LocalDateTime.ofInstant(v,ZoneOffset.UTC);}
}
