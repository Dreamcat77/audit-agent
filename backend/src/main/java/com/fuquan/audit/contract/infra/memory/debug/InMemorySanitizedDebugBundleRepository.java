package com.fuquan.audit.contract.infra.memory.debug;
import com.fuquan.audit.contract.application.debug.*;
import com.fuquan.audit.common.exception.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

@Repository @Profile("local")
public class InMemorySanitizedDebugBundleRepository implements SanitizedDebugBundleRepository {
    private final ConcurrentMap<UUID,SanitizedDebugBundle> rows=new ConcurrentHashMap<>();
    public void insert(SanitizedDebugBundle bundle) {
        if(rows.putIfAbsent(bundle.bundleId(),bundle)!=null) throw conflict();
    }
    public Optional<SanitizedDebugBundle> findById(UUID id) { return Optional.ofNullable(rows.get(id)); }
    public List<SanitizedDebugBundle> findByRunId(UUID runId) {
        return rows.values().stream().filter(row -> row.scope().runId().equals(runId))
                .sorted(Comparator.comparing(SanitizedDebugBundle::requestedAt).thenComparing(b -> b.bundleId().toString())).toList();
    }
    public SanitizedDebugBundle transition(SanitizedDebugBundle expected,SanitizedDebugBundle next) {
        SanitizedDebugBundle.requireTransition(expected,next);
        if(!rows.replace(expected.bundleId(),expected,next)) throw conflict();
        return next;
    }
    public void rollbackUncommittedInsert(SanitizedDebugBundle bundle) { rows.remove(bundle.bundleId(),bundle); }
    private static BusinessException conflict() { return new BusinessException(BusinessErrorCode.CONFLICT,"debug artifact state conflict"); }
}
