package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Candidate;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Request;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Result;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Scope;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultContractFactExtractionTransactionCoordinator
        implements ContractFactExtractionTransactionCoordinator {
    private final ContractFactExtractionRepository attempts;
    private final ExtractionResultRepository extractions;
    private final ContractFactExtractionAuthorityResolver authority;
    private final ContractFactCandidatePort port;
    private final ContractFactSegmenter segmenter;
    private final ContractFactCandidateValidator validator;
    private final ContractFactSourceTablePlanner sourceTables;

    @Autowired
    public DefaultContractFactExtractionTransactionCoordinator(ContractFactExtractionRepository attempts,
            ExtractionResultRepository extractions, ContractFactExtractionAuthorityResolver authority,
            ContractFactCandidatePort port, ContractFactSourceTablePlanner sourceTables) {
        this(attempts, extractions, authority, port, new ContractFactSegmenter(),
                new ContractFactCandidateValidator(port.configuredProvenance()), sourceTables);
    }

    public DefaultContractFactExtractionTransactionCoordinator(ContractFactExtractionRepository attempts,
            ExtractionResultRepository extractions, ContractFactExtractionAuthorityResolver authority,
            ContractFactCandidatePort port) {
        this(attempts, extractions, authority, port, new ContractFactSegmenter(),
                new ContractFactCandidateValidator(port.configuredProvenance()));
    }

    DefaultContractFactExtractionTransactionCoordinator(ContractFactExtractionRepository attempts,
            ExtractionResultRepository extractions, ContractFactExtractionAuthorityResolver authority,
            ContractFactCandidatePort port, ContractFactSegmenter segmenter,
            ContractFactCandidateValidator validator) {
        this(attempts, extractions, authority, port, segmenter, validator, null);
    }

    private DefaultContractFactExtractionTransactionCoordinator(ContractFactExtractionRepository attempts,
            ExtractionResultRepository extractions, ContractFactExtractionAuthorityResolver authority,
            ContractFactCandidatePort port, ContractFactSegmenter segmenter,
            ContractFactCandidateValidator validator, ContractFactSourceTablePlanner sourceTables) {
        this.attempts = Objects.requireNonNull(attempts);
        this.extractions = Objects.requireNonNull(extractions);
        this.authority = Objects.requireNonNull(authority);
        this.port = Objects.requireNonNull(port);
        this.segmenter = Objects.requireNonNull(segmenter);
        this.validator = Objects.requireNonNull(validator);
        this.sourceTables = sourceTables;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Prepared prepare(UUID projectId, UUID fileId, String operationKey,
            CurrentUser actor, ProjectRequestMetadata metadata, Instant occurredAt) {
        Objects.requireNonNull(actor); Objects.requireNonNull(metadata);
        if (operationKey == null || !operationKey.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("invalid fact extraction operation key");
        }
        ContractFactExtractionAuthority resolved = authority.resolve(projectId, fileId);
        if(!resolved.canonicalRowLayoutProven()) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "canonical DOCX row layout requires a controlled reparse");
        }
        var current = resolved.current();
        List<ContractFactCandidatePort.Segment> segments = segmenter.segment(
                current.parseResultId(), current.canonicalText());
        var sourcePlan = sourceTables != null && "contract-fact-candidates-v2".equals(port.configuredProvenance().schemaVersion())
                ? sourceTables.plan(current, segments) : ContractFactSourceTablePlanner.Plan.fallback(segments);
        List<Scope> scopes = current.effectiveRole() == com.fuquan.audit.contract.domain.project.DocumentRole.AWARD_NOTICE
                ? List.of(Scope.AWARD) : List.of(Scope.CORE, Scope.LINE_ITEMS);
        List<Request> requests = new ArrayList<>();
        for (Scope scope : scopes) {
            var scopedSegments = scope == Scope.LINE_ITEMS ? sourcePlan.residual() : segments;
            if (scopedSegments.isEmpty()) continue;
            var batches = new ContractFactBatchPlanner().plan(scopedSegments, scope);
            int batchCount = batches.size();
            for (int batch = 0; batch < batchCount; batch++) {
                requests.add(new Request(projectId, fileId, current.fileSha256(), current.parseResultId(),
                        current.parseVersion(), current.canonicalTextSha256(),
                        batches.get(batch),
                        scope, batch + 1, batchCount, 900000));
            }
        }
        String fingerprint = fingerprint(resolved, requests, sourcePlan.batches(), actor.userId(), port.configuredProvenance());
        ContractFactExtractionAttempt proposed = ContractFactExtractionAttempt.start(UUID.randomUUID(),
                projectId, fileId, current.fileSha256(), current.parseResultId(), current.parseVersion(),
                current.canonicalTextSha256(), current.effectiveRole(), resolved.roleDecisionId(),
                resolved.identityDecisionId(), fingerprint, actor.userId(), operationKey,
                port.configuredProvenance(), occurredAt);
        ContractFactExtractionRepository.Claim claim = attempts.claim(proposed);
        ContractFactExtractionAttempt attempt = claim.attempt();
        requireSameInput(proposed, attempt);
        if (claim.acquired()) {
            return new Prepared(attempt.attemptId(), requests, false, result(attempt, false), sourcePlan.batches());
        }
        if (attempt.status() == ContractFactExtractionAttempt.Status.SUCCEEDED) {
            return new Prepared(attempt.attemptId(), requests, true, result(attempt, true), sourcePlan.batches());
        }
        throw new BusinessException(BusinessErrorCode.CONFLICT,
                "contract fact extraction is already in progress or requires explicit retry");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractFactExtractionResult complete(Prepared prepared, List<Result> providerResults,
            Instant occurredAt) {
        ContractFactExtractionAttempt attempt = attempts.findById(prepared.attemptId())
                .orElseThrow(DefaultContractFactExtractionTransactionCoordinator::conflict);
        if (attempt.status() != ContractFactExtractionAttempt.Status.IN_PROGRESS) throw conflict();
        ContractFactExtractionAuthority current = authority.resolve(attempt.projectId(), attempt.fileId());
        requireSameAuthority(attempt, current);
        if (!attempt.inputFingerprint().equals(fingerprint(current, prepared.requests(), prepared.sourceBatches(),
                attempt.actorId(), attempt.provenance()))) throw conflict();
        List<ContractFactExtractionCandidate> rows = new ArrayList<>();
        List<ContractFactExtractionRejection> rejectedRows = new ArrayList<>();
        List<ContractModelCallAudit> modelCalls = new ArrayList<>();
        if (providerResults == null || providerResults.size() != prepared.requests().size()) throw conflict();
        int accepted = 0;
        int javaRejected = 0;
        boolean anyUnknown = providerResults.stream()
                .anyMatch(value -> value.batchStatus() == ContractFactCandidatePort.BatchStatus.UNKNOWN);
        boolean anyFailed = providerResults.stream()
                .anyMatch(value -> value.batchStatus() == ContractFactCandidatePort.BatchStatus.FAILED);
        List<ContractFactCandidateValidator.Validation> validations = new ArrayList<>();
        for (int index = 0; index < prepared.requests().size(); index++) {
            Request request = prepared.requests().get(index);
            Result providerResult = providerResults.get(index);
            if (providerResult.scope() != request.scope()) throw conflict();
            validations.add(validator.validate(current.current(), request, providerResult, occurredAt));
        }
        var sourceValidator = new ContractFactCandidateValidator(ContractFactSourceTablePlanner.PROVENANCE);
        for (var source : prepared.sourceBatches()) {
            var validation = sourceValidator.validate(current.current(), source.request(), source.result(), occurredAt);
            if (validation.decisions().stream().anyMatch(decision -> decision.publication() == null)) anyFailed = true;
            validations.add(validation);
        }
        // Validate the entire plan before publishing its first source or provider fact.
        for (var validation : validations) {
            for (ContractFactCandidateValidator.Decision decision : validation.decisions()) {
                UUID publishedExtractionId = null;
                if (decision.publication() != null && !anyUnknown && !anyFailed) {
                    var durable = extractions.insertIfAbsent(decision.publication());
                    if (durable == null) throw conflict();
                    publishedExtractionId = durable.id();
                    accepted++;
                } else {
                    javaRejected++;
                }
                if (!anyUnknown && !anyFailed) rows.add(row(attempt.attemptId(), decision, publishedExtractionId, occurredAt));
            }
        }
        for (int index = 0; index < prepared.requests().size(); index++) {
            Request request = prepared.requests().get(index);
            Result providerResult = providerResults.get(index);
            if (providerResult.scope() != request.scope()) throw conflict();
            appendProviderAudit(attempt, request, providerResult, index, rejectedRows, modelCalls, occurredAt);
        }
        int rejected = javaRejected + rejectedRows.size();
        if ("contract-fact-candidates-v2".equals(attempt.provenance().schemaVersion())) {
            boolean allFailed = providerResults.stream()
                    .allMatch(value -> value.batchStatus() == ContractFactCandidatePort.BatchStatus.FAILED);
            if (anyUnknown || anyFailed) {
                String reason = anyUnknown ? "MODEL_OUTCOME_UNKNOWN"
                        : allFailed ? "ALL_SCOPES_FAILED" : "PARTIAL_SCOPE_FAILURE";
                attempts.failV2(attempt, rejectedRows, modelCalls, rejected,
                        reason, occurredAt);
                return new ContractFactExtractionResult(attempt.attemptId(), false,
                        anyUnknown ? "OUTCOME_UNKNOWN" : "FAILED", 0, rejected);
            }
            attempts.completeV2(attempt, rows, rejectedRows, modelCalls, accepted, rejected, occurredAt);
        } else {
            attempts.complete(attempt, rows, accepted, rejected, occurredAt);
        }
        return new ContractFactExtractionResult(attempt.attemptId(), false, "SUCCEEDED", accepted, rejected);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Prepared prepared, String reason, Instant occurredAt) {
        fail(prepared, List.of(), reason, occurredAt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Prepared prepared, List<Result> completedResults, String reason, Instant occurredAt) {
        ContractFactExtractionAttempt attempt = attempts.findById(prepared.attemptId()).orElse(null);
        if (attempt == null || attempt.status() != ContractFactExtractionAttempt.Status.IN_PROGRESS) return;
        String safeReason = reason == null ? "UNSPECIFIED" : reason;
        if (completedResults == null || completedResults.size() > prepared.requests().size()
                || !attempt.inputFingerprint().equals(fingerprint(attempt.effectiveRole(),
                        attempt.roleDecisionId(), attempt.identityDecisionId(), prepared.requests(),
                        prepared.sourceBatches(), attempt.actorId(), attempt.provenance()))) throw conflict();
        if (completedResults.isEmpty()) {
            attempts.fail(attempt, safeReason, occurredAt);
            return;
        }
        List<ContractFactExtractionRejection> rejections = new ArrayList<>();
        List<ContractModelCallAudit> calls = new ArrayList<>();
        for (int index = 0; index < completedResults.size(); index++) {
            appendProviderAudit(attempt, prepared.requests().get(index), completedResults.get(index),
                    index, rejections, calls, occurredAt);
        }
        // The captured provider prefix proves transport/rejection audit, not a
        // publishable complete business result. No candidates/extractions are inserted.
        attempts.failV2(attempt, rejections, calls, rejections.size(), safeReason, occurredAt);
    }

    private static void appendProviderAudit(ContractFactExtractionAttempt attempt, Request request,
            Result result, int requestIndex, List<ContractFactExtractionRejection> rejections,
            List<ContractModelCallAudit> calls, Instant occurredAt) {
        if (result == null || result.scope() != request.scope()
                || !attempt.provenance().equals(result.provenance())
                || result.rejections().size() > 100 || result.modelCalls().size() > 2) throw conflict();
        var rejectionOrdinals = new HashSet<Integer>();
        for (var rejection : result.rejections()) {
            if (rejection.providerOrdinal() > 100 || !rejectionOrdinals.add(rejection.providerOrdinal())) throw conflict();
            rejections.add(new ContractFactExtractionRejection(UUID.randomUUID(), attempt.attemptId(),
                    attempt.callCount(), request.scope(), requestIndex * 100 + rejection.providerOrdinal(),
                    rejection.stage(), rejection.reasonCode(), rejection.safeShapeHash(), occurredAt));
        }
        String requestFingerprint = requestFingerprint(attempt.inputFingerprint(), request);
        var callOrdinals = new HashSet<Integer>();
        for (var call : result.modelCalls()) {
            if (call.ordinal() > 2 || !callOrdinals.add(call.ordinal())) throw conflict();
            calls.add(new ContractModelCallAudit(UUID.randomUUID(), attempt.attemptId(), null,
                    attempt.callCount(), request.scope(), requestIndex * 2 + call.ordinal(), call.phase(), requestFingerprint,
                    result.provenance().provider(), result.provenance().modelName(), call.outcome(), call.safeReason(),
                    call.durationMs(), call.finishReason(), call.promptTokens(), call.completionTokens(), call.totalTokens(),
                    result.batchStatus(), occurredAt));
        }
    }

    private static ContractFactExtractionCandidate row(UUID attemptId,
            ContractFactCandidateValidator.Decision decision, UUID publishedExtractionId,
            Instant createdAt) {
        Candidate value = decision.candidate();
        return new ContractFactExtractionCandidate(UUID.randomUUID(), attemptId, value.candidateKey(),
                value.factType().name(), value.groupKey(), value.valueText(), value.valueDecimal(),
                value.segmentId(), value.sourceLocator(), value.excerpt(), value.excerptStart(),
                value.excerptEnd(), value.contextCode().name(), value.confidence(),
                decision.status().name(), decision.reason().name(),
                publishedExtractionId, createdAt);
    }

    private static ContractFactExtractionResult result(ContractFactExtractionAttempt attempt, boolean replayed) {
        return new ContractFactExtractionResult(attempt.attemptId(), replayed, attempt.status().name(),
                attempt.acceptedCount(), attempt.rejectedCount());
    }

    private static void requireSameInput(ContractFactExtractionAttempt expected,
            ContractFactExtractionAttempt actual) {
        if (!expected.projectId().equals(actual.projectId()) || !expected.fileId().equals(actual.fileId())
                || !expected.parseResultId().equals(actual.parseResultId())
                || !expected.inputFingerprint().equals(actual.inputFingerprint())
                || !expected.actorId().equals(actual.actorId())
                || !expected.operationKey().equals(actual.operationKey())) throw conflict();
    }

    private static void requireSameAuthority(ContractFactExtractionAttempt attempt,
            ContractFactExtractionAuthority current) {
        if (!attempt.roleDecisionId().equals(current.roleDecisionId())
                || !attempt.identityDecisionId().equals(current.identityDecisionId())
                || attempt.effectiveRole() != current.current().effectiveRole()) {
            throw conflict();
        }
    }

    private static String fingerprint(ContractFactExtractionAuthority authority, List<Request> requests,
            List<ContractFactSourceTablePlanner.SourceBatch> sourceBatches,
            String actorId, ContractFactCandidatePort.Provenance provenance) {
        return fingerprint(authority.current().effectiveRole(), authority.roleDecisionId(),
                authority.identityDecisionId(), requests, sourceBatches, actorId, provenance);
    }

    private static String fingerprint(com.fuquan.audit.contract.domain.project.DocumentRole role,
            UUID roleDecisionId, UUID identityDecisionId, List<Request> requests,
            List<ContractFactSourceTablePlanner.SourceBatch> sourceBatches,
            String actorId, ContractFactCandidatePort.Provenance provenance) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Request request = requests.get(0);
            for (String value : List.of("contract-fact-input-v2", ContractFactBatchPlanner.POLICY_VERSION, actorId,
                    request.projectId().toString(), request.fileId().toString(), request.fileSha256(),
                    request.parseResultId().toString(), Integer.toString(request.parseVersion()),
                    request.canonicalTextSha256(), role.name(),
                    roleDecisionId.toString(), identityDecisionId.toString(),
                    provenance.provider(), provenance.modelName(), provenance.modelRevision(),
                    provenance.promptVersion(), provenance.schemaVersion())) add(digest, value);
            for (Request scoped : requests) {
                add(digest, scoped.scope().name());
                add(digest, Integer.toString(scoped.batchOrdinal()));
                add(digest, Integer.toString(scoped.batchCount()));
                for (var segment : scoped.segments()) {
                    add(digest, segment.segmentId()); add(digest, segment.sourceLocator());
                    add(digest, segment.textSha256());
                    add(digest, Objects.toString(segment.rowIdentity(), ""));
                    add(digest, segment.lineItemColumns() == null ? "" : String.join(",", segment.lineItemColumns()));
                }
            }
            for (var source : sourceBatches) {
                add(digest, ContractFactSourceTablePlanner.EXTRACTION_VERSION);
                for (var segment : source.request().segments()) {
                    add(digest, segment.segmentId()); add(digest, segment.sourceLocator()); add(digest, segment.textSha256());
                    // Proof-only aliases have no candidate offsets: bind their physical coverage explicitly.
                    add(digest, Integer.toString(segment.startOffset()));
                    add(digest, Integer.toString(segment.endOffset()));
                    add(digest, Objects.toString(segment.rowIdentity(), ""));
                    add(digest, segment.lineItemColumns() == null ? "" : String.join(",", segment.lineItemColumns()));
                }
                for (var candidate : source.result().candidates()) {
                    add(digest, candidate.toString());
                    add(digest, source.cellLocators().getOrDefault(candidate.candidateKey(), ""));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String requestFingerprint(String inputFingerprint, Request request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "contract-fact-call-v2"); add(digest, inputFingerprint); add(digest, request.scope().name());
            add(digest, Integer.toString(request.batchOrdinal()));
            add(digest, Integer.toString(request.batchCount()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT,
                "contract fact extraction durable state cannot be verified");
    }
}
