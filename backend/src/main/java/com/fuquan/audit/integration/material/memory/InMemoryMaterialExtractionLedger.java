package com.fuquan.audit.integration.material.memory;

import com.fuquan.audit.common.material.application.port.MaterialExtractionLedger;
import java.time.Instant;
import java.util.*;

/** Test seam. Production profiles always use the DM8 adapter. */
public class InMemoryMaterialExtractionLedger implements MaterialExtractionLedger {
    private final Map<UUID,Entry> entries=new HashMap<>();
    public synchronized Optional<Entry> find(UUID id){return Optional.ofNullable(entries.get(id));}
    public synchronized void insert(Entry e){if(entries.putIfAbsent(e.id(),e)!=null)throw new org.springframework.dao.DuplicateKeyException("extraction exists");}
    public synchronized boolean begin(UUID id,int next,String token,Instant now){var e=entries.get(id);if(e==null||!e.status().equals("READY")||e.nextUnit()!=next||e.calls()>=512||e.usedMillis()>=900000)return false;
        entries.put(id,new Entry(id,e.sourceHash(),e.policyHash(),e.unitCount(),next,e.calls(),e.usedMillis(),"CALLING",token,now,e.resultsJson(),null,e.deadlineAt()));return true;}
    public synchronized boolean acknowledge(UUID id,int next,String token,int calls,long elapsed,String results,Instant now){var e=entries.get(id);if(e==null||!e.status().equals("CALLING")||!Objects.equals(token,e.token())||next!=e.nextUnit()||e.calls()+calls>512||e.usedMillis()+elapsed>900000)return false;
        if(e.deadlineAt()!=null&&!now.isBefore(e.deadlineAt()))return false;
        entries.put(id,new Entry(id,e.sourceHash(),e.policyHash(),e.unitCount(),next+1,e.calls()+calls,e.usedMillis()+elapsed,next+1==e.unitCount()?"COMPLETE":"READY",null,now,results,null,e.deadlineAt()));return true;}
    public synchronized void unknown(UUID id,String token,String reason,Instant now){var e=entries.get(id);if(e!=null&&e.status().equals("CALLING")&&Objects.equals(e.token(),token))entries.put(id,new Entry(id,e.sourceHash(),e.policyHash(),e.unitCount(),e.nextUnit(),e.calls(),e.usedMillis(),"UNKNOWN",token,now,e.resultsJson(),reason,e.deadlineAt()));}
    public synchronized void startDeadline(UUID id,Instant deadline){var e=entries.get(id);if(e!=null&&e.status().equals("READY")&&e.deadlineAt()==null)entries.put(id,new Entry(id,e.sourceHash(),e.policyHash(),e.unitCount(),e.nextUnit(),e.calls(),e.usedMillis(),e.status(),e.token(),e.updatedAt(),e.resultsJson(),e.reasonCode(),deadline));}
    public synchronized boolean terminate(UUID id,String token,String reason,Instant now){var e=entries.get(id);
        if(e==null||!(e.status().equals("READY")&&token==null||e.status().equals("CALLING")&&Objects.equals(token,e.token())))return false;
        entries.put(id,new Entry(id,e.sourceHash(),e.policyHash(),e.unitCount(),e.nextUnit(),e.calls(),e.usedMillis(),"UNKNOWN",null,now,"[]",reason,e.deadlineAt()));return true;}
}
