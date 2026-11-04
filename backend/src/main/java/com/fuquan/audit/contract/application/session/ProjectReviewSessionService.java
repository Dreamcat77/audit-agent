package com.fuquan.audit.contract.application.session;

import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.contract.application.ReviewRequestMetadata;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.domain.*;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Owns only references and material preparation. It cannot upload, parse, or execute a ReviewRun. */
@Service
public class ProjectReviewSessionService {
    public static final String CREATE_SCOPE="PROJECT_REVIEW_SESSION_CREATE";
    public static final String BIND_SCOPE="PROJECT_REVIEW_SESSION_BIND";
    public static final String CONFIRM_SCOPE="PROJECT_REVIEW_SESSION_CONFIRM";
    private static final String RESOURCE="CONTRACT_REVIEW_SESSION";
    private static final Set<String> ROLES=Set.of("SYSTEM_ADMIN","PROCUREMENT_MANAGER","AUDITOR","LEGAL");
    public record View(ContractReviewSession session,List<ReviewSessionProjectFileBinding> bindings) {
        public View { bindings=List.copyOf(bindings); }
    }
    private final ContractReviewSessionRepository sessions;
    private final ProjectSessionBindingRepository bindings;
    private final MaterialBindingRepository legacy;
    private final ProjectSessionReadiness readiness;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final AuthContext auth;
    private final Clock clock;
    public ProjectReviewSessionService(ContractReviewSessionRepository sessions,ProjectSessionBindingRepository bindings,
            MaterialBindingRepository legacy,ProjectSessionReadiness readiness,IdempotencyPort idempotency,
            OperationAuditPort audit,AuthContext auth,Clock clock) {
        this.sessions=sessions; this.bindings=bindings; this.legacy=legacy; this.readiness=readiness;
        this.idempotency=idempotency; this.audit=audit; this.auth=auth; this.clock=clock;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public View create(UUID projectId,UUID contractId,String name,String remark,List<UUID> fileIds,String key,ReviewRequestMetadata metadata) {
        CurrentUser user=user(); ProjectSessionReadiness.validateSelection(fileIds);
        String normalizedName=ContractReviewSession.normalizeName(name), normalizedRemark=ContractReviewSession.normalizeRemark(remark);
        UUID id=UUID.randomUUID();
        return bindings.withSessionFence(id,() -> command(CREATE_SCOPE,key,projectId,contractId,id,user,metadata,
                fingerprint(user.userId(),projectId,contractId,normalizedName,normalizedRemark,sorted(fileIds)),0L,() -> {
            var ready=readiness.resolve(projectId,contractId,fileIds);
            var session=ContractReviewSession.createProjectBacked(id,projectId,contractId,ready.contract().versionNo(),normalizedName,normalizedRemark,user.userId(),clock.instant());
            sessions.create(session); insert(session,ready.materials(),user,0); return view(session,null);
        }));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public View replace(UUID projectId,UUID contractId,UUID sessionId,long expectedVersion,List<UUID> fileIds,String key,ReviewRequestMetadata metadata) {
        CurrentUser user=user(); ProjectSessionReadiness.validateSelection(fileIds);
        return bindings.withSessionFence(sessionId,() -> command(BIND_SCOPE,key,projectId,contractId,sessionId,user,metadata,
                fingerprint(user.userId(),projectId,contractId,sessionId,expectedVersion,sorted(fileIds)),expectedVersion+1,() -> {
            var session=require(projectId,contractId,sessionId); preparing(session,expectedVersion);
            var ready=readiness.resolve(projectId,contractId,fileIds);
            var changed=sessions.advancePreparingVersion(sessionId,expectedVersion,clock.instant());
            bindings.deactivateBySessionId(sessionId); insert(changed,ready.materials(),user,changed.versionNo());
            return view(changed,null);
        }));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public View confirm(UUID projectId,UUID contractId,UUID sessionId,long expectedVersion,String key,ReviewRequestMetadata metadata) {
        CurrentUser user=user();
        return bindings.withSessionFence(sessionId,() -> command(CONFIRM_SCOPE,key,projectId,contractId,sessionId,user,metadata,
                fingerprint(user.userId(),projectId,contractId,sessionId,expectedVersion),null,() -> {
            var session=require(projectId,contractId,sessionId); preparing(session,expectedVersion);
            var active=view(session,null).bindings();
            Set<DocumentRole> roles=new HashSet<>(); active.forEach(b -> roles.add(b.effectiveRole()));
            if (!roles.contains(DocumentRole.CONTRACT_BODY) || !roles.contains(DocumentRole.AWARD_NOTICE))
                throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,"Contract body and award notice are required");
            var ready=readiness.resolve(projectId,contractId,active.stream().map(ReviewSessionProjectFileBinding::projectFileId).toList());
            if (ready.contract().versionNo()!=session.contractVersion() || ready.materials().size()!=active.size()
                    || ready.materials().stream().anyMatch(m -> active.stream().noneMatch(m::matches)))
                throw conflict("Bound authority changed; select materials explicitly again");
            return view(sessions.confirmMaterials(sessionId,expectedVersion,clock.instant()),null);
        }));
    }
    @Transactional(readOnly=true)
    public View get(UUID projectId,UUID contractId,UUID sessionId) { user(); return bindings.withSessionFence(sessionId,() -> view(require(projectId,contractId,sessionId),null)); }
    @Transactional(readOnly=true)
    public List<ReviewSessionProjectFileBinding> history(UUID projectId,UUID contractId,UUID sessionId) {
        user(); return bindings.withSessionFence(sessionId,() -> {
            require(projectId,contractId,sessionId); return bindings.findBySessionId(sessionId);
        });
    }
    @Transactional(readOnly=true)
    public List<View> list(UUID projectId,UUID contractId) {
        user(); return sessions.findByContractId(contractId).stream().filter(s -> projectId.equals(s.projectId())
                && s.sourceKind()==ReviewSessionSourceKind.PROJECT_FILE_REFERENCE).map(s -> view(s,null)).toList();
    }
    private View command(String scope,String key,UUID projectId,UUID contractId,UUID proposedId,CurrentUser user,
            ReviewRequestMetadata metadata,String fingerprint,Long selectionVersion,Supplier<View> action) {
        if (key==null || !key.matches("[A-Za-z0-9._:-]{1,128}") || metadata==null
                || metadata.requestId()==null || metadata.traceId()==null) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        var claim=idempotency.claim(new IdempotencyRequest(scope,key,user.userId(),"CONTRACT",contractId.toString(),fingerprint,
                RESOURCE,proposedId.toString(),metadata.requestId(),metadata.traceId(),clock.instant()));
        if (!claim.acquired()) {
            var old=claim.record();
            if (!old.actorId().equals(user.userId()) || !old.requestFingerprint().equals(fingerprint)
                    || !old.resourceId().equals(contractId.toString())) throw conflict("Idempotency fingerprint conflict");
            if (old.status()!=IdempotencyStatus.SUCCEEDED) throw conflict("Operation outcome is pending; retain the same key");
            var session=require(projectId,contractId,UUID.fromString(old.resultResourceId()));
            return selectionVersion==null ? view(session,null) : selectionResult(session,selectionVersion);
        }
        try {
            View result=action.get();
            audit.append(new OperationAuditEvent(scope,"CONTRACT",scope,user.userId(),user.username(),RESOURCE,
                    result.session().id().toString(),null,OperationAuditResultStatus.SUCCESS,"SUCCESS",
                    "Project review session material command",null,metadata.requestId(),metadata.traceId(),metadata.sourceIp(),clock.instant()));
            idempotency.markSucceeded(claim.record(),RESOURCE,result.session().id().toString(),clock.instant());
            return result;
        } catch (RuntimeException failure) {
            idempotency.rollbackUncommittedClaim(claim.record());
            if (failure instanceof ContractReviewConcurrencyException) throw conflict("Session status or version changed");
            throw failure;
        }
    }
    private void insert(ContractReviewSession session,List<ProjectSessionReadiness.Material> materials,CurrentUser user,long selection) {
        for (int i=0;i<materials.size();i++) bindings.insert(materials.get(i).bind(session.id(),i+1,user.userId(),session.updatedAt(),selection));
    }
    /** Reconstruct the operation-time view, never pair old materials with a newer CAS version. */
    private View selectionResult(ContractReviewSession current,long selection) {
        var historical=bindings.findBySessionId(current.id()).stream().filter(b -> b.selectionVersion()==selection)
                .map(b -> new ReviewSessionProjectFileBinding(b.id(),b.sessionId(),b.projectId(),b.projectFileId(),
                        b.fileSha256(),b.parseResultId(),b.parseVersion(),b.canonicalTextSha256(),b.effectiveRole(),
                        b.roleDecisionId(),b.identityDecisionId(),b.slotKey(),b.ordinal(),true,b.boundBy(),b.boundAt(),selection,0))
                .toList();
        if (historical.isEmpty()) throw conflict("Persisted operation selection cannot be proven");
        var frozen=new ContractReviewSession(current.id(),current.contractId(),current.contractVersion(),current.name(),
                current.reviewType(),ReviewSessionStatus.PREPARING_MATERIALS,current.remark(),current.createdBy(),
                current.createdAt(),historical.get(0).boundAt(),null,selection,null,null,current.sourceKind(),current.projectId());
        return new View(frozen,historical);
    }
    private View view(ContractReviewSession session,Long selection) {
        return new View(session,bindings.findBySessionId(session.id()).stream()
                .filter(b -> selection==null ? b.activeFlag() : b.selectionVersion()==selection).toList());
    }
    private ContractReviewSession require(UUID projectId,UUID contractId,UUID id) {
        var session=sessions.findById(id).filter(s -> !s.deleted() && s.contractId().equals(contractId)
                && projectId.equals(s.projectId()) && s.sourceKind()==ReviewSessionSourceKind.PROJECT_FILE_REFERENCE)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        if (legacy.findBySessionId(id).stream().anyMatch(MaterialBinding::active)) throw conflict("Mixed material authorities are forbidden");
        return session;
    }
    private void preparing(ContractReviewSession s,long version) {
        if (s.status()!=ReviewSessionStatus.PREPARING_MATERIALS || s.versionNo()!=version) throw conflict("Session status or version changed");
    }
    private CurrentUser user() {
        var user=auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        if (user.roles().stream().noneMatch(ROLES::contains)) throw new BusinessException(BusinessErrorCode.FORBIDDEN);
        return user;
    }
    private static String sorted(List<UUID> ids) { return ids.stream().map(UUID::toString).sorted().toList().toString(); }
    private static String fingerprint(Object... values) {
        StringBuilder canonical=new StringBuilder("project-session-v1");
        for (Object value:values) { String text=value==null ? "<null>" : value.toString(); canonical.append(':').append(text.length()).append(':').append(text); }
        return ContractSnapshotCanonicalizer.sha256(canonical.toString());
    }
    private static BusinessException conflict(String message) { return new BusinessException(BusinessErrorCode.CONFLICT,message); }
}
