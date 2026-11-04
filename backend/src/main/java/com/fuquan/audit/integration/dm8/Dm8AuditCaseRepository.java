package com.fuquan.audit.integration.dm8;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.procurement.autocase.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository @Profile({"dev29","customer","local-dev"})
public class Dm8AuditCaseRepository implements AuditCaseRepository {
    private final AutoCaseMapper mapper;private final ObjectMapper json;
    public Dm8AuditCaseRepository(AutoCaseMapper mapper,ObjectMapper json){this.mapper=mapper;this.json=json;}
    public record StoredDocument(AuditCase.Document document,int factCount,String factDigest) {}
    public Optional<AuditCase> find(UUID id){
        for(int attempt=0;;attempt++) {
            try{return readSnapshot(id);}
            catch(org.springframework.dao.ConcurrencyFailureException changed){if(attempt>=3)throw changed;}
        }
    }
    private Optional<AuditCase> readSnapshot(UUID id){return Optional.ofNullable(mapper.find(id.toString())).map(value->{
        var c=decode(value,AuditCase.class);
        var records=mapper.files(id.toString()).stream().map(f->decode(f,StoredDocument.class)).toList();
        var facts=mapper.facts(id.toString());
        // A worker may commit between the independent reads. Retry a changed version before
        // diagnosing missing/mismatched children; stable corruption still fails closed.
        if(!value.equals(mapper.find(id.toString())))throw new org.springframework.dao.ConcurrencyFailureException("case changed while reading snapshot");
        var docs=new ArrayList<AuditCase.Document>();
        var consumed=new HashSet<String>();
        for(var record:records) {
            var d=record.document();
            if(!d.facts().isEmpty())throw new IllegalStateException("duplicated file facts are forbidden");
            var rows=facts.stream().filter(f->f.fileId().equals(d.id().toString())).toList();
            for(int i=0;i<rows.size();i++)if(rows.get(i).position()!=i)throw new IllegalStateException("fact order corrupt");
            var values=rows.stream().map(f->decode(f.payload(),AuditCase.Fact.class)).toList();
            if(values.size()!=record.factCount()||!digest(values).equals(record.factDigest()))throw new IllegalStateException("case fact manifest corrupt");
            consumed.add(d.id().toString());
            docs.add(new AuditCase.Document(d.id(),d.name(),d.size(),d.sha256(),d.storageKey(),d.status(),d.segments(),values,d.parseMethod(),d.errorCode(),d.calls(),d.readCheckpoint()));
        }
        if(facts.stream().anyMatch(f->!consumed.contains(f.fileId())))throw new IllegalStateException("orphan fact corrupt");
        if(!c.documents().stream().map(AuditCase.Document::id).toList().equals(docs.stream().map(AuditCase.Document::id).toList()))throw new IllegalStateException("case file manifest corrupt");
        return new AuditCase(c.id(),c.owner(),c.operationKey(),c.fingerprint(),c.status(),c.version(),c.leaseToken(),c.leaseUntil(),c.attempts(),c.createdAt(),docs,c.report());
    });}
    public List<AuditCase> recent(String owner){return mapper.recent(owner).stream().map(UUID::fromString).map(id->find(id).orElseThrow()).toList();}
    public List<AuditCaseHistoryEntry> history(String owner,UUID beforeId,int limit){
        return mapper.history(owner,beforeId==null?null:beforeId.toString(),limit)
                .stream().map(v->AuditCaseHistoryEntry.from(decode(v,AuditCase.class))).toList();
    }
    public List<AuditCase> pending(Instant now){return mapper.pending(LocalDateTime.ofInstant(now,ZoneOffset.UTC)).stream().map(UUID::fromString).map(id->find(id).orElseThrow()).toList();}
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void insert(AuditCase c){mapper.insert(row(c));files(c);facts(c);}
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean replace(AuditCase previous,AuditCase next){
        if(mapper.replace(row(next),previous.version(),previous.leaseToken())!=1)return false;
        mapper.deleteFacts(next.id().toString());
        mapper.deleteLines(next.id().toString());
        mapper.deleteFiles(next.id().toString());files(next);facts(next);
        if(next.report()!=null)for(var offer:next.report().offers())for(int i=0;i<offer.lines().size();i++) {
            var line=offer.lines().get(i);String id=UUID.nameUUIDFromBytes((offer.documentId()+"/line/"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            mapper.insertLine(id,next.id().toString(),offer.documentId().toString(),line.item(),line.quantity(),line.unitPrice(),line.amount(),line.locator());
        }
        return true;
    }
    private void files(AuditCase c){int i=0;for(var d:c.documents()) {
        var withoutFacts=new AuditCase.Document(d.id(),d.name(),d.size(),d.sha256(),d.storageKey(),d.status(),d.segments(),List.of(),d.parseMethod(),d.errorCode(),d.calls(),d.readCheckpoint());
        mapper.insertFile(d.id().toString(),c.id().toString(),i++,d.sha256(),d.storageKey(),d.status(),encode(new StoredDocument(withoutFacts,d.facts().size(),digest(d.facts()))));
    }}
    private void facts(AuditCase c){for(var doc:c.documents())for(int i=0;i<doc.facts().size();i++) {
        var f=doc.facts().get(i);String id=UUID.nameUUIDFromBytes((doc.id()+"/fact/"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        mapper.insertFact(id,c.id().toString(),doc.id().toString(),i,f.type(),f.dataType(),f.verification(),encode(f));
    }}
    private String digest(Object v){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encode(v).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private AutoCaseMapper.Row row(AuditCase c){
        var manifest=c.documents().stream().map(d->new AuditCase.Document(d.id(),d.name(),d.size(),d.sha256(),d.storageKey(),d.status(),List.of(),List.of(),d.parseMethod(),d.errorCode(),d.calls())).toList();
        var metadata=new AuditCase(c.id(),c.owner(),c.operationKey(),c.fingerprint(),c.status(),c.version(),c.leaseToken(),c.leaseUntil(),c.attempts(),c.createdAt(),manifest,c.report());
        return new AutoCaseMapper.Row(c.id().toString(),c.owner(),c.operationKey().toString(),c.fingerprint(),c.status(),c.version(),c.leaseToken(),LocalDateTime.ofInstant(c.leaseUntil(),ZoneOffset.UTC),LocalDateTime.ofInstant(c.createdAt(),ZoneOffset.UTC),encode(metadata));
    }
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("case serialization failed",e);}}
    private <T>T decode(String v,Class<T> type){try{return json.readValue(v,type);}catch(Exception e){throw new IllegalStateException("case snapshot corrupt",e);}}
}
