package com.fuquan.audit.contract.application.debug;

import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.*;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class DebugBundleTransactions {
    public static final String SCOPE="SANITIZED_DEBUG_BUNDLE_GENERATE";
    public static final String RESOURCE="SANITIZED_DEBUG_BUNDLE";
    private final SanitizedDebugBundleRepository bundles;
    private final ContractReviewRunRepository runs;
    private final ContractReviewRunService reads;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final DebugManifestBuilder manifests;
    private final Clock clock;
    public DebugBundleTransactions(SanitizedDebugBundleRepository bundles,ContractReviewRunRepository runs,
            ContractReviewRunService reads,IdempotencyPort idempotency,OperationAuditPort audit,
            DebugManifestBuilder manifests,Clock clock) {
        this.bundles=bundles;this.runs=runs;this.reads=reads;this.idempotency=idempotency;
        this.audit=audit;this.manifests=manifests;this.clock=clock;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public SanitizedDebugBundle start(DebugScope scope,DebugContentLevel level,String key,CurrentUser actor,ProjectRequestMetadata metadata) {
        level.requireAvailable();
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        String fingerprint=ContractSnapshotCanonicalizer.sha256(DebugManifestBuilder.canonical(Map.of(
                "scope",scope.metadata(),"level",level.name(),"actor",actor.userId(),"policy",DebugManifestBuilder.POLICY,"schema",1)));
        var previous=idempotency.findByScopeAndKey(SCOPE,key);
        if(previous.isPresent()) return replay(previous.get(),fingerprint,scope);
        var run=runs.findByIdForUpdate(scope.runId()).filter(r -> r.projectId().equals(scope.projectId())
                && r.contractId().equals(scope.contractId())).orElseThrow(DebugBundleTransactions::notFound);
        var source=reads.get(scope.projectId(),scope.contractId(),scope.runId());
        if(scope.stepId()!=null && source.steps().stream().noneMatch(s -> s.runId().equals(run.runId()) && s.stepId().equals(scope.stepId()))) throw notFound();
        if(scope.attemptId()!=null && source.attempts().stream().noneMatch(a -> a.runId().equals(run.runId())
                && a.stepId().equals(scope.stepId()) && a.attemptId().equals(scope.attemptId()))) throw notFound();
        UUID id=UUID.randomUUID(); Instant now=clock.instant();
        // Validate the projection before claiming; local adapters have no transaction rollback.
        String json=manifests.build(id,scope,now,actor.userId(),source);
        var claim=idempotency.claim(new IdempotencyRequest(SCOPE,key,actor.userId(),"CONTRACT_REVIEW_RUN",scope.runId().toString(),
                fingerprint,RESOURCE,id.toString(),metadata.requestId(),metadata.traceId(),now));
        if(!claim.acquired()) return replay(claim.record(),fingerprint,scope);
        var bundle=new SanitizedDebugBundle(id,scope,level,SanitizedDebugBundle.Status.GENERATING,json,
                ContractSnapshotCanonicalizer.sha256("debug-bundle-v1:"+id),ContractSnapshotCanonicalizer.sha256(json),
                json.getBytes(StandardCharsets.UTF_8).length,actor.userId(),now,metadata.requestId(),metadata.traceId(),0);
        try { bundles.insert(bundle); append(bundle,actor,metadata,"GENERATE_START"); return bundle; }
        catch(RuntimeException failure) {
            bundles.rollbackUncommittedInsert(bundle);idempotency.rollbackUncommittedClaim(claim.record());throw failure;
        }
    }
    private SanitizedDebugBundle replay(IdempotencyRecord claim,String fingerprint,DebugScope scope) {
        if(!claim.requestFingerprint().equals(fingerprint) || !RESOURCE.equals(claim.resultResourceType()))
            throw new BusinessException(BusinessErrorCode.CONFLICT,"debug operation fingerprint conflict");
        return bundles.findById(UUID.fromString(claim.resultResourceId())).filter(b -> b.scope().equals(scope))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,"debug operation outcome is not yet provable"));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public SanitizedDebugBundle complete(UUID id,CurrentUser actor,ProjectRequestMetadata metadata) {
        var current=bundles.findById(id).orElseThrow(DebugBundleTransactions::notFound);
        if(current.status()==SanitizedDebugBundle.Status.AVAILABLE) return current;
        var claim=idempotency.findByResultResource(RESOURCE,id.toString()).orElseThrow(DebugBundleTransactions::notFound);
        var next=bundles.transition(current,current.withStatus(SanitizedDebugBundle.Status.AVAILABLE));
        idempotency.markSucceeded(claim,RESOURCE,id.toString(),clock.instant());
        append(next,actor,metadata,"GENERATE_COMPLETE");return next;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public SanitizedDebugBundle unknown(UUID id,CurrentUser actor,ProjectRequestMetadata metadata) {
        var current=bundles.findById(id).orElseThrow(DebugBundleTransactions::notFound);
        if(current.status()!=SanitizedDebugBundle.Status.GENERATING) return current;
        var next=bundles.transition(current,current.withStatus(SanitizedDebugBundle.Status.UNKNOWN));
        append(next,actor,metadata,"GENERATE_UNKNOWN");return next;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void auditRead(SanitizedDebugBundle bundle,CurrentUser actor,ProjectRequestMetadata metadata,String action) {
        append(bundle,actor,metadata,action);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void auditList(DebugScope scope,CurrentUser actor,ProjectRequestMetadata metadata) {
        runs.findById(scope.runId()).filter(run -> run.projectId().equals(scope.projectId())
                && run.contractId().equals(scope.contractId())).orElseThrow(DebugBundleTransactions::notFound);
        audit.append(new OperationAuditEvent("SANITIZED_DEBUG_BUNDLE_LIST","CONTRACT","LIST",actor.userId(),actor.username(),
                "CONTRACT_REVIEW_RUN",scope.runId().toString(),null,OperationAuditResultStatus.SUCCESS,"READ",
                "sanitized debug bundle list","{}",metadata.requestId(),metadata.traceId(),metadata.sourceIp(),clock.instant()));
    }
    private void append(SanitizedDebugBundle bundle,CurrentUser actor,ProjectRequestMetadata metadata,String action) {
        audit.append(new OperationAuditEvent("SANITIZED_DEBUG_BUNDLE_"+action,"CONTRACT",action,actor.userId(),actor.username(),
                RESOURCE,bundle.bundleId().toString(),null,OperationAuditResultStatus.SUCCESS,bundle.status().name(),
                "sanitized debug bundle "+action,"{\"contentLevel\":\"METADATA_ONLY\"}",
                metadata.requestId(),metadata.traceId(),metadata.sourceIp(),clock.instant()));
    }
    static BusinessException notFound() { return new BusinessException(BusinessErrorCode.NOT_FOUND,"debug bundle not found"); }
}
