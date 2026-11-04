package com.fuquan.audit.contract.application.closeout;

import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.contract.application.closeout.port.ContractReviewCloseoutRepository;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.port.StoredFileInfo;
import com.fuquan.audit.contract.domain.closeout.ContractReviewReport;
import com.fuquan.audit.contract.domain.closeout.ContractReviewReport.Status;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Short transactions only. The caller performs all rendering and filesystem work outside these methods. */
@Component
public class ReportTransactionCoordinator {
    public static final String SCOPE = "CONTRACT_REVIEW_REPORT_GENERATE";
    public static final String RESOURCE = "CONTRACT_REVIEW_REPORT";
    private final ContractReviewRunRepository runs;
    private final ContractReviewCloseoutRepository repository;
    private final IdempotencyPort idempotency;
    private final ReportSnapshotFactory snapshots;
    private final OperationAuditPort audit;
    private final Clock clock;
    public ReportTransactionCoordinator(ContractReviewRunRepository runs, ContractReviewCloseoutRepository repository,
            IdempotencyPort idempotency, ReportSnapshotFactory snapshots, OperationAuditPort audit, Clock clock) {
        this.runs = runs; this.repository = repository; this.idempotency = idempotency;
        this.snapshots = snapshots; this.audit = audit; this.clock = clock;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractReviewReport start(UUID project, UUID contract, UUID runId, String key, String actor, ProjectRequestMetadata metadata) {
        var owned = runs.findById(runId).filter(r -> r.projectId().equals(project) && r.contractId().equals(contract))
                .orElseThrow(CloseoutOwnership::notFound);
        CloseoutOwnership.requireActor(owned.requestedBy(), actor);
        var prior = idempotency.findByScopeAndKey(SCOPE, key);
        if (prior.isPresent()) return replay(prior.get(), project, contract, runId, actor);
        var snapshot = snapshots.freeze(project, contract, runId);
        var now = clock.instant();
        var report = new ContractReviewReport(UUID.randomUUID(), runId, repository.nextReportVersion(runId),
                ReportSnapshotFactory.SCHEMA, Status.PENDING, snapshot.inputIdentity(), snapshot.findingIdentity(),
                snapshot.acknowledgementIdentity(), snapshot.json(), null, null, null, null,
                actor, now, null, null, null, null, metadata.requestId(), metadata.traceId(), 0);
        var claim = idempotency.claim(new IdempotencyRequest(SCOPE, key, actor, "CONTRACT_REVIEW_RUN", runId.toString(),
                fingerprint(project, contract, report), RESOURCE, report.reportId().toString(), metadata.requestId(), metadata.traceId(), now));
        if (!claim.acquired()) return replay(claim.record(), project, contract, runId, actor);
        try {
            repository.insertReport(report);
            audit(report, "GENERATE_START", actor, metadata);
            return report;
        } catch (RuntimeException failure) {
            repository.rollbackUncommittedReport(null, report);
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractReviewReport prepare(UUID reportId, String mediaType, long size, String sha256) {
        var report = required(reportId);
        if (report.status() == Status.AVAILABLE || report.status() == Status.FAILED) return report;
        if (report.artifactStorageKey() != null) {
            if (!Objects.equals(report.artifactSize(), size) || !Objects.equals(report.sha256(), sha256)
                    || !Objects.equals(report.artifactMediaType(), mediaType)) throw CloseoutOwnership.conflict();
            return report;
        }
        String key = CloseoutCanonical.sha256("contract-review-report-v1:" + reportId);
        var next = state(report, Status.GENERATING, key, mediaType, size, sha256, null, null);
        if (!repository.compareAndSetReport(report, next)) throw CloseoutOwnership.conflict();
        return next;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractReviewReport complete(UUID reportId, StoredFileInfo inspected) {
        var report = required(reportId);
        if (report.status() == Status.AVAILABLE) return report;
        if (report.status() == Status.FAILED || report.artifactStorageKey() == null
                || !Objects.equals(report.artifactSize(), inspected.size()) || !Objects.equals(report.sha256(), inspected.sha256()))
            throw CloseoutOwnership.conflict();
        var operation = operation(report);
        var next = state(report, Status.AVAILABLE, report.artifactStorageKey(), report.artifactMediaType(), report.artifactSize(),
                report.sha256(), clock.instant(), null);
        if (!repository.compareAndSetReport(report, next)) throw CloseoutOwnership.conflict();
        try {
            audit(next, "GENERATE_COMPLETE", report.generatedBy(), metadata(report));
            idempotency.markSucceeded(operation, RESOURCE, reportId.toString(), clock.instant());
            return next;
        } catch (RuntimeException failure) {
            repository.rollbackUncommittedReport(report, next); throw failure;
        }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractReviewReport unknown(UUID reportId) { return terminal(reportId, Status.UNKNOWN, "ARTIFACT_OUTCOME_UNPROVEN"); }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractReviewReport fail(UUID reportId) { return terminal(reportId, Status.FAILED, "RENDER_REJECTED"); }
    private ContractReviewReport terminal(UUID id, Status status, String reason) {
        var report = required(id);
        if (report.status() == Status.AVAILABLE || report.status() == Status.FAILED || report.status() == status) return report;
        var next = state(report, status, report.artifactStorageKey(), report.artifactMediaType(), report.artifactSize(), report.sha256(), clock.instant(), reason);
        if (!repository.compareAndSetReport(report, next)) throw CloseoutOwnership.conflict();
        try {
            audit(next, status == Status.UNKNOWN ? "GENERATE_UNKNOWN" : "GENERATE_FAILED", report.generatedBy(), metadata(report));
            if (status == Status.FAILED) idempotency.markFailed(operation(report), clock.instant());
            return next;
        } catch (RuntimeException failure) { repository.rollbackUncommittedReport(report, next); throw failure; }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditDownload(ContractReviewReport report, String actor, ProjectRequestMetadata metadata) {
        if (required(report.reportId()).status() != Status.AVAILABLE) throw CloseoutOwnership.conflict();
        audit(report, "DOWNLOAD", actor, metadata);
    }
    private ContractReviewReport replay(IdempotencyRecord operation, UUID project, UUID contract, UUID run, String actor) {
        if (!SCOPE.equals(operation.scope()) || !actor.equals(operation.actorId())
                || !"CONTRACT_REVIEW_RUN".equals(operation.resourceType()) || !run.toString().equals(operation.resourceId())
                || !RESOURCE.equals(operation.resultResourceType())) throw CloseoutOwnership.conflict();
        var report = required(UUID.fromString(operation.resultResourceId()));
        if (!report.reviewRunId().equals(run) || !report.generatedBy().equals(actor)
                || !fingerprint(project, contract, report).equals(operation.requestFingerprint())
                || operation.status() == IdempotencyStatus.SUCCEEDED && report.status() != Status.AVAILABLE
                || operation.status() == IdempotencyStatus.FAILED && report.status() != Status.FAILED) throw CloseoutOwnership.conflict();
        return report;
    }
    private static String fingerprint(UUID project, UUID contract, ContractReviewReport r) {
        return CloseoutCanonical.sha256(CloseoutCanonical.json(List.of(SCOPE, r.generatedBy(), project, contract,
                r.reviewRunId(), r.reportSchemaVersion(), r.inputSnapshotIdentity(), r.findingSnapshotIdentity(), r.acknowledgementSnapshotIdentity())));
    }
    private IdempotencyRecord operation(ContractReviewReport report) {
        return idempotency.findByResultResource(RESOURCE, report.reportId().toString()).filter(i -> SCOPE.equals(i.scope())
                && i.resourceId().equals(report.reviewRunId().toString()) && i.actorId().equals(report.generatedBy())
                && i.status() == IdempotencyStatus.IN_PROGRESS).orElseThrow(CloseoutOwnership::conflict);
    }
    private ContractReviewReport required(UUID id) { return repository.findReport(id).orElseThrow(CloseoutOwnership::conflict); }
    private static ProjectRequestMetadata metadata(ContractReviewReport r) { return new ProjectRequestMetadata(r.requestId(), r.traceId(), null); }
    private void audit(ContractReviewReport r, String action, String actor, ProjectRequestMetadata metadata) {
        audit.append(new OperationAuditEvent("CONTRACT_REVIEW_REPORT", "CONTRACT", action, actor, null, RESOURCE,
                r.reportId().toString(), null, r.status() == Status.FAILED ? OperationAuditResultStatus.FAILURE : OperationAuditResultStatus.SUCCESS,
                r.status().name(), "Contract report operation", null, metadata.requestId(), metadata.traceId(), metadata.sourceIp(), clock.instant()));
    }
    private static ContractReviewReport state(ContractReviewReport r, Status status, String key, String media, Long size,
            String sha, Instant timestamp, String reason) {
        return new ContractReviewReport(r.reportId(), r.reviewRunId(), r.reportVersion(), r.reportSchemaVersion(), status,
                r.inputSnapshotIdentity(), r.findingSnapshotIdentity(), r.acknowledgementSnapshotIdentity(), r.snapshotJson(), key, media, size, sha,
                r.generatedBy(), r.requestedAt(), status == Status.AVAILABLE ? timestamp : r.generatedAt(),
                status == Status.FAILED ? timestamp : r.failedAt(), status == Status.UNKNOWN ? timestamp : r.unknownAt(),
                reason, r.requestId(), r.traceId(), r.versionNo() + 1);
    }
}
