package com.fuquan.audit.integration.dm8;
import com.fuquan.audit.contract.application.debug.*;
import com.fuquan.audit.common.exception.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
@Repository @Profile({"local-dev","dev29","customer"})
public class Dm8SanitizedDebugBundleRepository implements SanitizedDebugBundleRepository {
    private final SanitizedDebugBundleMapper mapper;
    public Dm8SanitizedDebugBundleRepository(SanitizedDebugBundleMapper mapper) { this.mapper=mapper; }
    public void insert(SanitizedDebugBundle b) {
        var s=b.scope();
        requireOne(mapper.insert(new SanitizedDebugBundlePersistenceRow(id(b.bundleId()),id(s.projectId()),id(s.contractId()),id(s.runId()),
                id(s.stepId()),id(s.attemptId()),b.contentLevel().name(),b.status().name(),b.manifestJson(),b.storageKey(),b.artifactSha256(),
                b.artifactSize(),b.generatedBy(),LocalDateTime.ofInstant(b.requestedAt(),ZoneOffset.UTC),b.requestId(),b.traceId(),b.versionNo())));
    }
    public Optional<SanitizedDebugBundle> findById(UUID id) { return Optional.ofNullable(mapper.findById(id.toString())).map(this::bundle); }
    public List<SanitizedDebugBundle> findByRunId(UUID id) { return mapper.findByRunId(id.toString()).stream().map(this::bundle).toList(); }
    public SanitizedDebugBundle transition(SanitizedDebugBundle expected,SanitizedDebugBundle next) {
        SanitizedDebugBundle.requireTransition(expected,next);
        requireOne(mapper.transition(id(expected.bundleId()),expected.status().name(),expected.versionNo(),next.status().name(),next.versionNo()));
        return next;
    }
    private SanitizedDebugBundle bundle(SanitizedDebugBundlePersistenceRow r) {
        return new SanitizedDebugBundle(uuid(r.bundleId()),new DebugScope(uuid(r.projectId()),uuid(r.contractId()),uuid(r.runId()),uuid(r.stepId()),uuid(r.attemptId())),
                DebugContentLevel.valueOf(r.contentLevel()),SanitizedDebugBundle.Status.valueOf(r.status()),r.manifestJson(),r.storageKey(),r.artifactSha256(),
                r.artifactSize(),r.generatedBy(),r.requestedAt().toInstant(ZoneOffset.UTC),r.requestId(),r.traceId(),r.versionNo());
    }
    private static UUID uuid(String id) { return id==null?null:UUID.fromString(id); }
    private static String id(UUID id) { return id==null?null:id.toString(); }
    private static void requireOne(int count) { if(count!=1) throw new BusinessException(BusinessErrorCode.CONFLICT,"debug metadata write conflict"); }
}
