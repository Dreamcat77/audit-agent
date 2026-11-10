package com.fuquan.audit.procurement.autocase;

import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Explicit local/test seam only. Production state belongs to DM8. */
@Repository @Profile("local")
public class InMemoryAuditCaseRepository implements AuditCaseRepository {
    private final Map<UUID,AuditCase> values=new HashMap<>();
    public synchronized Optional<AuditCase> find(UUID id){return Optional.ofNullable(values.get(id));}
    public synchronized List<AuditCase> recent(String owner){return values.values().stream().filter(v->owner==null||v.owner().equals(owner)).sorted(Comparator.comparing(AuditCase::createdAt).reversed()).limit(100).toList();}
    public synchronized List<AuditCaseHistoryEntry> history(String owner,UUID beforeId,int limit){
        var cursor=beforeId==null?null:values.get(beforeId);
        if(beforeId!=null&&(cursor==null||owner!=null&&!cursor.owner().equals(owner)))return List.of();
        var before=cursor==null?null:cursor.createdAt();
        return values.values().stream().filter(v->owner==null||v.owner().equals(owner))
                .filter(v->before==null||v.createdAt().isBefore(before)||v.createdAt().equals(before)&&v.id().toString().compareTo(beforeId.toString())>0)
                .sorted(Comparator.comparing(AuditCase::createdAt).reversed().thenComparing(v->v.id().toString()))
                .limit(limit).map(AuditCaseHistoryEntry::from).toList();
    }
    public synchronized List<AuditCase> pending(Instant now){return values.values().stream().filter(v->v.status().equals("QUEUED") || Set.of("RUNNING","UPLOADING").contains(v.status()) && !v.leaseUntil().isAfter(now)).limit(8).toList();}
    public synchronized void insert(AuditCase v){if(values.putIfAbsent(v.id(),v)!=null)throw new org.springframework.dao.DuplicateKeyException("case exists");}
    public synchronized boolean replace(AuditCase p,AuditCase n){var v=values.get(p.id());if(v==null||v.version()!=p.version()||!Objects.equals(v.leaseToken(),p.leaseToken()))return false;values.put(n.id(),n);return true;}
}
