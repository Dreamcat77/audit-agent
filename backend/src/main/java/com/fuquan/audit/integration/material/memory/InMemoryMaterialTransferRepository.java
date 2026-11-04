package com.fuquan.audit.integration.material.memory;
import com.fuquan.audit.common.material.transfer.*;
import static com.fuquan.audit.common.material.transfer.MaterialTransfer.*;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
@Repository @Profile("local")
public class InMemoryMaterialTransferRepository implements MaterialTransferRepository {
    private final Map<UUID,Session> sessions=new HashMap<>();private final Map<UUID,Map<Integer,Part>> parts=new HashMap<>();
    public synchronized Optional<Session> find(UUID id){return Optional.ofNullable(sessions.get(id));}
    public synchronized void insert(Session s){if(sessions.putIfAbsent(s.id(),s)!=null)throw new org.springframework.dao.DuplicateKeyException("transfer exists");}
    public synchronized List<Part> parts(UUID id){return parts.getOrDefault(id,Map.of()).values().stream().sorted(Comparator.comparingInt(Part::index)).toList();}
    public synchronized void insertPart(UUID id,Part p){if(parts.computeIfAbsent(id,k->new HashMap<>()).putIfAbsent(p.index(),p)!=null)throw new org.springframework.dao.DuplicateKeyException("part exists");}
    public synchronized boolean claim(UUID id,String token,Instant now,Instant until){var s=sessions.get(id);if(s==null||!(s.status().equals("OPEN")||s.status().equals("ASSEMBLING")&&s.leaseUntil().isBefore(now)))return false;sessions.put(id,new Session(id,s.actor(),s.kind(),s.operation(),s.spec(),"ASSEMBLING",token,until,s.resultJson(),s.createdAt()));return true;}
    public synchronized boolean renew(UUID id,String token,Instant until){var s=sessions.get(id);if(s==null||!s.status().equals("ASSEMBLING")||!Objects.equals(s.token(),token))return false;sessions.put(id,new Session(id,s.actor(),s.kind(),s.operation(),s.spec(),s.status(),token,until,s.resultJson(),s.createdAt()));return true;}
    public synchronized boolean complete(UUID id,String token,String value){var s=sessions.get(id);if(s==null||!Objects.equals(token,s.token()))return false;sessions.put(id,new Session(id,s.actor(),s.kind(),s.operation(),s.spec(),"COMPLETED",null,null,value,s.createdAt()));return true;}
    public synchronized void release(UUID id,String token){var s=sessions.get(id);if(s!=null&&Objects.equals(s.token(),token))sessions.put(id,new Session(id,s.actor(),s.kind(),s.operation(),s.spec(),"OPEN",null,null,null,s.createdAt()));}
}
