package com.fuquan.audit.contract.application.project.identity;

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
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityEvidenceRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityEvidence;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdentityAssessmentTransactionCoordinator {
    public static final String SCOPE = "PROJECT_IDENTITY_ASSESSMENT";
    public static final String RESULT_RESOURCE = "PROJECT_FILE_IDENTITY_ASSESSMENT";
    private static final String RESOURCE = "PROJECT_FILE";
    private static final String FINGERPRINT_VERSION = "project-identity-assessment-fingerprint-v1";
    private final ProjectRepository projects;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ExtractionResultRepository extractions;
    private final ProjectFileIdentityAssessmentRepository assessments;
    private final ProjectFileIdentityEvidenceRepository evidence;
    private final DeterministicProjectIdentityResolver resolver;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;

    public IdentityAssessmentTransactionCoordinator(ProjectRepository projects, ProjectFileRepository files,
            ProjectFileParseResultRepository parses, ExtractionResultRepository extractions,
            ProjectFileIdentityAssessmentRepository assessments, ProjectFileIdentityEvidenceRepository evidence,
            DeterministicProjectIdentityResolver resolver, IdempotencyPort idempotency,
            OperationAuditPort audit) {
        this.projects = projects; this.files = files; this.parses = parses; this.extractions = extractions;
        this.assessments = assessments; this.evidence = evidence; this.resolver = resolver;
        this.idempotency = idempotency; this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdentityAssessmentResult assess(IdentityAssessmentCommand command, CurrentUser actor,
            UUID assessmentId, Instant at) {
        Authority authority = authority(command.projectId(), command.fileId());
        String fingerprint = fingerprint(actor, authority);
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(SCOPE, command.idempotencyKey(),
                actor.userId(), RESOURCE, command.fileId().toString(), fingerprint, RESULT_RESOURCE,
                assessmentId.toString(), command.metadata().requestId(), command.metadata().traceId(), at));
        if (!claim.acquired()) return replay(claim.record(), command, actor, authority, fingerprint, at);
        ProjectFileIdentityAssessment proposed = null;
        boolean assessmentStaged = false, evidenceStaged = false, published = false;
        try {
            IdentityAssessmentCalculation calculation = resolver.resolve(authority.project(), authority.file(),
                    authority.parse(), authority.facts(), assessmentId, fingerprint, at);
            proposed = calculation.assessment();
            var stored = assessments.stageInsertIfAbsent(proposed);
            if (!stored.equals(proposed)) conflict("identity assessment durable identity collision");
            assessmentStaged = true;
            evidence.stageInsertAll(assessmentId, calculation.evidence());
            evidenceStaged = true;
            audit.append(event(actor, proposed, command, at, "PROJECT_FILE_IDENTITY_ASSESSED"));
            evidence.commitStagedInsert(assessmentId);
            assessments.commitStagedInsert(proposed);
            published = true;
            idempotency.markSucceeded(claim.record(), RESULT_RESOURCE, assessmentId.toString(), at);
            return new IdentityAssessmentResult(proposed, calculation.evidence(), false);
        } catch (RuntimeException failure) {
            if (!published) {
                if (evidenceStaged) compensateEvidence(assessmentId, failure);
                if (assessmentStaged) compensateAssessment(proposed, failure);
                compensateClaim(claim.record(), failure);
            }
            throw failure;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdentityAssessmentResult reconcile(IdentityAssessmentCommand command, CurrentUser actor,
            RuntimeException original, Instant at) {
        Authority authority = authority(command.projectId(), command.fileId());
        String fingerprint = fingerprint(actor, authority);
        IdempotencyRecord record = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("identity assessment outcome cannot be proven", original));
        requireRecord(record, command, actor, fingerprint);
        var result = result(record, command, authority, fingerprint, true);
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            audit.append(event(actor, result.assessment(), command, at,
                    "PROJECT_FILE_IDENTITY_ASSESSMENT_RECONCILED"));
            idempotency.markSucceeded(record, RESULT_RESOURCE, result.assessment().assessmentId().toString(), at);
        } else if (record.status() != IdempotencyStatus.SUCCEEDED) conflict("identity assessment durable status conflict");
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public IdentityAssessmentResult reopen(UUID projectId, UUID fileId, UUID assessmentId, boolean replayed) {
        var assessment = assessments.findById(assessmentId)
                .filter(value -> value.projectId().equals(projectId) && value.fileId().equals(fileId))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "identity assessment not found"));
        var stored = evidence.findByAssessmentId(assessmentId);
        requireEvidenceBinding(assessment, stored);
        return new IdentityAssessmentResult(assessment, stored, replayed);
    }

    private IdentityAssessmentResult replay(IdempotencyRecord record, IdentityAssessmentCommand command,
            CurrentUser actor, Authority authority, String fingerprint, Instant at) {
        requireRecord(record, command, actor, fingerprint);
        var result = result(record, command, authority, fingerprint, true);
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            audit.append(event(actor, result.assessment(), command, at,
                    "PROJECT_FILE_IDENTITY_ASSESSMENT_RECONCILED"));
            idempotency.markSucceeded(record, RESULT_RESOURCE, result.assessment().assessmentId().toString(), at);
        } else if (record.status() != IdempotencyStatus.SUCCEEDED) conflict("identity assessment durable status conflict");
        return result;
    }
    private IdentityAssessmentResult result(IdempotencyRecord record, IdentityAssessmentCommand command,
            Authority authority, String fingerprint, boolean replayed) {
        UUID id;
        try { id = UUID.fromString(record.resultResourceId()); }
        catch (RuntimeException invalid) { throw new BusinessException(BusinessErrorCode.CONFLICT,
                "identity assessment durable ID invalid"); }
        var assessment = assessments.findById(id)
                .filter(value -> value.projectId().equals(command.projectId()) && value.fileId().equals(command.fileId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "identity assessment durable state is unproven"));
        if (!assessment.normalizedInputFingerprint().equals(fingerprint)
                || assessment.projectVersionNo() != authority.project().versionNo()
                || !assessment.fileSha256().equals(authority.file().sha256())
                || !assessment.parseResultId().equals(authority.parse().parseResultId())
                || assessment.parseVersion() != authority.parse().parseVersion())
            conflict("identity assessment durable input drift");
        var stored = evidence.findByAssessmentId(id);
        requireEvidenceBinding(assessment, stored);
        return new IdentityAssessmentResult(assessment, stored, replayed);
    }

    private Authority authority(UUID projectId, UUID fileId) {
        ProjectFile file = files.findByProjectIdAndIdForUpdate(projectId, fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
        Project project = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
        ProjectFileParseResult parse = parses.findLatestByFileId(fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "immutable parse result is required"));
        if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE
                || file.parseStatus() != ProjectFileParseStatus.PARSED
                || parse.parseStatus() != ProjectFileParseStatus.PARSED
                || !parse.projectId().equals(projectId) || !parse.fileId().equals(fileId)
                || !parse.fileSha256().equals(file.sha256())
                || !Objects.equals(parse.canonicalText(), file.parsedText()))
            conflict("project identity parse authority is inconsistent");
        List<ExtractionResult> facts = extractions.findBySourceFileIdAndParseResultId(projectId, fileId,
                parse.parseResultId()).stream()
                .filter(value -> "PROJECT_NO".equals(value.factType()) || "PROJECT_NAME".equals(value.factType()))
                .sorted(Comparator.comparing(ExtractionResult::extractionKey)
                        .thenComparing(value -> value.id().toString())).toList();
        return new Authority(project, file, parse, facts);
    }

    static String fingerprint(CurrentUser actor, Authority authority) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, FINGERPRINT_VERSION); add(digest, actor.userId());
            add(digest, authority.project().id().toString());
            add(digest, Long.toString(authority.project().versionNo()));
            add(digest, ProjectIdentityNormalizer.projectName(authority.project().name()));
            add(digest, ProjectIdentityNormalizer.projectNo(authority.project().projectNo()));
            add(digest, authority.file().id().toString()); add(digest, authority.file().sha256());
            add(digest, authority.parse().parseResultId().toString());
            add(digest, Integer.toString(authority.parse().parseVersion()));
            add(digest, authority.parse().canonicalTextSha256());
            for (var fact : authority.facts()) {
                add(digest, fact.id().toString()); add(digest, fact.extractionKey());
                add(digest, fact.factType()); add(digest, fact.valueText());
            }
            add(digest, "fact-count:" + authority.facts().size());
            add(digest, DeterministicProjectIdentityResolver.ASSESSMENT_VERSION);
            add(digest, DeterministicProjectIdentityResolver.POLICY_VERSION);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 required", failure); }
    }
    private static void add(MessageDigest digest, String value) {
        if (value == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    private static void requireRecord(IdempotencyRecord record, IdentityAssessmentCommand command,
            CurrentUser actor, String fingerprint) {
        if (!SCOPE.equals(record.scope()) || !actor.userId().equals(record.actorId())
                || !RESOURCE.equals(record.resourceType()) || !command.fileId().toString().equals(record.resourceId())
                || !fingerprint.equals(record.requestFingerprint())
                || !RESULT_RESOURCE.equals(record.resultResourceType()))
            conflict("identity assessment idempotency fingerprint conflict");
    }
    private static void requireEvidenceBinding(ProjectFileIdentityAssessment assessment,
            List<ProjectFileIdentityEvidence> values) {
        for (var value : values) if (!assessment.assessmentId().equals(value.assessmentId())
                || !assessment.projectId().equals(value.projectId())
                || assessment.projectVersionNo() != value.projectVersionNo()
                || !assessment.fileId().equals(value.fileId())
                || !assessment.fileSha256().equals(value.fileSha256())
                || !assessment.parseResultId().equals(value.parseResultId())
                || assessment.parseVersion() != value.parseVersion())
            throw new BusinessException(BusinessErrorCode.CONFLICT, "identity evidence binding inconsistent");
    }
    private static OperationAuditEvent event(CurrentUser actor, ProjectFileIdentityAssessment assessment,
            IdentityAssessmentCommand command, Instant at, String type) {
        return new OperationAuditEvent(type, "CONTRACT", "identityAssessment", actor.userId(), actor.username(),
                RESULT_RESOURCE, assessment.assessmentId().toString(), null, OperationAuditResultStatus.SUCCESS,
                assessment.status().name(), "assess project file identity", "deterministic exact comparison",
                command.metadata().requestId(), command.metadata().traceId(), command.metadata().sourceIp(), at);
    }
    private void compensateEvidence(UUID id, RuntimeException failure) {
        try { evidence.rollbackUncommittedInsert(id); }
        catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
    }
    private void compensateAssessment(ProjectFileIdentityAssessment value, RuntimeException failure) {
        try { assessments.rollbackUncommittedInsert(value); }
        catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
    }
    private void compensateClaim(IdempotencyRecord record, RuntimeException failure) {
        try { idempotency.rollbackUncommittedClaim(record); }
        catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
    }
    private static void conflict(String message) { throw new BusinessException(BusinessErrorCode.CONFLICT, message); }
    record Authority(Project project, ProjectFile file, ProjectFileParseResult parse, List<ExtractionResult> facts) { }
}
