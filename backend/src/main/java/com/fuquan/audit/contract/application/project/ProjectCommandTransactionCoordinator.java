package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProjectCommandTransactionCoordinator {
    private final ProjectApplicationService service;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final AuthContext authContext;
    private final Clock clock;
    private final FlowDebugLogger flowDebug;

    public ProjectCommandTransactionCoordinator(ProjectApplicationService service, IdempotencyPort idempotency,
                                                OperationAuditPort audit, AuthContext authContext, Clock clock,
                                                FlowDebugLogger flowDebug) {
        this.service = service;
        this.idempotency = idempotency;
        this.audit = audit;
        this.authContext = authContext;
        this.clock = clock;
        this.flowDebug = flowDebug;
    }

    @Transactional
    public Project create(String idempotencyKey, String requestId, String traceId, String sourceIp) {
        CurrentUser actor = actor();
        flowDebug.log("PROJECT_CREATE_START", "PROJECT", "APPLICATION", "create");
        UUID projectId = UUID.randomUUID();
        String fingerprint = fingerprint("PROJECT_CREATE", actor.userId());
        IdempotencyClaim claim = idempotency.claim(request("PROJECT_CREATE", idempotencyKey, actor.userId(),
                projectId, fingerprint, requestId, traceId));
        if (!claim.acquired()) {
            return replay(claim.record(), fingerprint);
        }
        try {
            Project project = service.createBlank(projectId, actor.userId());
            audit.append(event("PROJECT_CREATED", "create", actor, project.id(), requestId, traceId, sourceIp));
            idempotency.markSucceeded(claim.record(), "PROJECT", project.id().toString(), clock.instant());
            flowDebug.log("PROJECT_CREATE_COMPLETE", "PROJECT", "APPLICATION", "create",
                    java.util.Map.of("projectId", project.id(), "status", "SUCCESS"));
            return project;
        } catch (RuntimeException failure) {
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }

    @Transactional
    public Project confirm(UUID projectId, long expectedVersion, String name, String projectNo,
                           String category, String idempotencyKey, String requestId,
                           String traceId, String sourceIp) {
        CurrentUser actor = actor();
        flowDebug.log("PROJECT_METADATA_UPDATE_START", "PROJECT", "APPLICATION", "confirmMetadata",
                java.util.Map.of("projectId", projectId, "expectedVersion", expectedVersion));
        String fingerprint = fingerprint("PROJECT_METADATA_CONFIRM", actor.userId(), projectId.toString(),
                Long.toString(expectedVersion), nullable(name), nullable(projectNo), nullable(category));
        IdempotencyClaim claim = idempotency.claim(request("PROJECT_METADATA_CONFIRM", idempotencyKey,
                actor.userId(), projectId, fingerprint, requestId, traceId));
        if (!claim.acquired()) {
            return replay(claim.record(), fingerprint);
        }
        try {
            Project updated = service.confirmMetadata(projectId, expectedVersion, name, projectNo, category);
            audit.append(event("PROJECT_METADATA_CONFIRMED", "confirmMetadata", actor, projectId,
                    requestId, traceId, sourceIp));
            idempotency.markSucceeded(claim.record(), "PROJECT", projectId.toString(), clock.instant());
            flowDebug.log("PROJECT_METADATA_UPDATE_COMPLETE", "PROJECT", "APPLICATION", "confirmMetadata",
                    java.util.Map.of("projectId", projectId, "version", updated.versionNo()));
            return updated;
        } catch (RuntimeException failure) {
            flowDebug.log("PROJECT_METADATA_UPDATE_CONFLICT", "PROJECT", "APPLICATION", "confirmMetadata",
                    java.util.Map.of("projectId", projectId, "reasonCode", failure.getClass().getSimpleName()));
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }

    @Transactional(readOnly=true)
    public Project readCreateOperation(String key) {
        CurrentUser current=actor();
        IdempotencyRecord record=idempotency.findByScopeAndKey("PROJECT_CREATE",key)
                .orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));
        if(!record.actorId().equals(current.userId()))throw new BusinessException(BusinessErrorCode.NOT_FOUND);
        return replay(record,fingerprint("PROJECT_CREATE",current.userId()));
    }

    private Project replay(IdempotencyRecord record, String fingerprint) {
        if (!fingerprint.equals(record.requestFingerprint())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "idempotency key fingerprint conflict");
        }
        if (record.status() == IdempotencyStatus.SUCCEEDED && "PROJECT".equals(record.resultResourceType())) {
            return service.get(UUID.fromString(record.resultResourceId()));
        }
        throw new BusinessException(BusinessErrorCode.CONFLICT,
                record.status() == IdempotencyStatus.IN_PROGRESS ? "project operation is in progress"
                        : "idempotent project operation previously failed");
    }

    private IdempotencyRequest request(String scope, String key, String actorId, UUID projectId,
                                       String fingerprint, String requestId, String traceId) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "Idempotency-Key is required");
        }
        return new IdempotencyRequest(scope, key, actorId, "PROJECT", projectId.toString(), fingerprint,
                "PROJECT", projectId.toString(), safe(requestId), safe(traceId), clock.instant());
    }

    private OperationAuditEvent event(String eventType, String action, CurrentUser actor, UUID projectId,
                                      String requestId, String traceId, String sourceIp) {
        return new OperationAuditEvent(eventType, "CONTRACT", action, actor.userId(), actor.username(),
                "PROJECT", projectId.toString(), null, OperationAuditResultStatus.SUCCESS, "SUCCESS",
                action, "{\"status\":\"SUCCESS\"}", safe(requestId), safe(traceId), sourceIp, clock.instant());
    }

    private CurrentUser actor() {
        return authContext.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    private static String nullable(String value) {
        return value == null ? "<null>" : value.trim();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private static String fingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
