package com.fuquan.audit.integration.material.memory;

import com.fuquan.audit.common.material.application.port.MaterialIntakeRepository;
import com.fuquan.audit.common.material.domain.MaterialIntake;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryMaterialIntakeRepository implements MaterialIntakeRepository {
    private final Map<UUID, MaterialIntake> records = new HashMap<>();
    @Override public synchronized Optional<MaterialIntake> find(UUID id) {
        return Optional.ofNullable(records.get(id));
    }
    @Override public synchronized Optional<MaterialIntake> findOperation(String actor, String key) {
        return records.values().stream().filter(r -> r.actorId().equals(actor) && r.operationKey().equals(key)).findFirst();
    }
    @Override public synchronized void insert(MaterialIntake r) {
        if (records.containsKey(r.id()) || findOperation(r.actorId(), r.operationKey()).isPresent())
            throw new DuplicateKeyException("procurement material operation already exists");
        records.put(r.id(), r);
    }
    @Override public synchronized boolean claim(MaterialIntake r, Instant now) {
        var previous = records.get(r.id());
        if (previous == null || previous.timedOut() || "PARSED".equals(previous.status()) || previous.active(now)) return false;
        records.put(r.id(), r); return true;
    }
    @Override public synchronized boolean complete(MaterialIntake r) {
        var previous = records.get(r.id());
        if (previous == null || !"PROCESSING".equals(previous.status())
                || !previous.leaseToken().equals(r.leaseToken())) return false;
        records.put(r.id(), r); return true;
    }
    @Override public synchronized boolean claimPending(MaterialIntake r, Instant now) {
        var previous=records.get(r.id());
        if(previous==null || !("QUEUED".equals(previous.status())
                || ("PROCESSING".equals(previous.status()) && !previous.active(now)))) return false;
        records.put(r.id(),r); return true;
    }
    @Override public synchronized List<MaterialIntake> pending(Instant now, int limit) {
        return records.values().stream().filter(r -> "QUEUED".equals(r.status())
                || ("PROCESSING".equals(r.status()) && !r.active(now)))
                .sorted(Comparator.comparing(MaterialIntake::createdAt).thenComparing(MaterialIntake::id))
                .limit(limit).toList();
    }
    @Override public synchronized boolean renew(UUID id, String token, Instant until) {
        var r = records.get(id);
        if (r == null || !"PROCESSING".equals(r.status()) || !r.leaseToken().equals(token)) return false;
        records.put(id, new MaterialIntake(r.id(), r.actorId(), r.operationKey(), r.fileName(), r.mediaType(),
                r.fileSize(), r.sha256(), r.storageKey(), r.status(), token, until, r.fields(), r.parseMethod(),
                r.errorCode(), r.createdAt(), r.updatedAt()));
        return true;
    }
    @Override public synchronized List<MaterialIntake> eligible(String keyword, int limit) {
        return records.values().stream().filter(r -> "PARSED".equals(r.status()))
                .filter(r -> keyword == null || keyword.isBlank() || r.fileName().contains(keyword)
                        || (r.fields().projectName() != null && r.fields().projectName().contains(keyword)))
                .sorted(Comparator.comparing(MaterialIntake::updatedAt).reversed()).limit(limit).toList();
    }
    @Override public synchronized List<MaterialIntake> recent(String actor, int limit) {
        return records.values().stream().filter(r -> actor.equals(r.actorId())&&!r.timedOut())
                .sorted(Comparator.comparing(MaterialIntake::createdAt).reversed()).limit(limit).toList();
    }
    public synchronized List<MaterialIntake> timedOutForCleanup(int limit){return records.values().stream().filter(r->r.status().equals("FAILED")&&r.fields()==null&&r.timedOut()&&!r.errorCode().endsWith("_CLEANED")).limit(limit).toList();}
    public synchronized void cleaned(UUID id,String reason){var r=records.get(id);if(r!=null&&r.status().equals("FAILED")&&r.fields()==null&&Objects.equals(r.errorCode(),reason))records.put(id,r.finish("FAILED",null,null,reason+"_CLEANED",r.updatedAt()));}
}
