package com.fuquan.audit.integration.dm8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleAssessmentRepository;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleCandidateProjection;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleModelProvenance;
import com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProjectFileRoleAssessmentRepository implements ProjectFileRoleAssessmentRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<ProjectFileRoleCandidateProjection>> CANDIDATES = new TypeReference<>() { };
    private final ProjectFileRoleAssessmentMapper mapper;

    public Dm8ProjectFileRoleAssessmentRepository(ProjectFileRoleAssessmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProjectFileRoleAssessment insertIfAbsent(ProjectFileRoleAssessment assessment) {
        try {
            mapper.insert(row(assessment));
            return assessment;
        } catch (DuplicateKeyException conflict) {
            ProjectFileRoleAssessmentPersistenceRow raced = mapper.findById(assessment.assessmentId().toString());
            if (raced != null) {
                ProjectFileRoleAssessment winner = domain(raced);
                if (!winner.assessmentId().equals(assessment.assessmentId())
                        || !winner.sameInputIdentity(assessment)) {
                    throw new IllegalStateException("role assessment input conflict");
                }
                return winner;
            }
            ProjectFileRoleAssessmentPersistenceRow active = mapper.findActiveByFileIdAndInputFingerprint(
                    assessment.fileId().toString(), assessment.inputFingerprint());
            if (active == null) throw conflict;
            ProjectFileRoleAssessment winner = domain(active);
            if (!isActive(winner) || !winner.sameInputIdentity(assessment)) {
                throw new IllegalStateException("role assessment active fence conflict");
            }
            return winner;
        }
    }

    @Override
    public boolean compareAndSet(ProjectFileRoleAssessment expected, ProjectFileRoleAssessment updated) {
        expected.requireLegalSuccessor(updated);
        return mapper.compareAndSet(row(updated), row(expected)) == 1;
    }

    @Override public Optional<ProjectFileRoleAssessment> findById(UUID assessmentId) {
        return Optional.ofNullable(mapper.findById(assessmentId.toString())).map(Dm8ProjectFileRoleAssessmentRepository::domain);
    }

    @Override public Optional<ProjectFileRoleAssessment> findLatestByFileId(UUID fileId) {
        return Optional.ofNullable(mapper.findLatestByFileId(fileId.toString())).map(Dm8ProjectFileRoleAssessmentRepository::domain);
    }

    private static ProjectFileRoleAssessmentPersistenceRow row(ProjectFileRoleAssessment value) {
        ProjectFileRoleModelProvenance model = value.modelProvenance();
        return new ProjectFileRoleAssessmentPersistenceRow(value.assessmentId().toString(), value.projectId().toString(),
                value.fileId().toString(), value.fileSha256(), value.parseResultId().toString(), value.parseVersion(),
                value.executionStatus().name(), name(value.proposedRole()), value.confidence(), value.conflict(),
                value.assessmentSource().name(), value.assessmentVersion(), value.rolePolicyVersion(), candidates(value),
                text(value.precedingDeterministicAssessmentId()), precedingSource(value), model == null ? null : model.provider(),
                model == null ? null : model.modelName(), model == null ? null : model.modelRevision(),
                model == null ? null : model.promptVersion(), model == null ? null : model.schemaVersion(),
                value.inputFingerprint(), fenceKey(value), value.safeReasonCode(),
                time(value.createdAt()), time(value.startedAt()),
                time(value.completedAt()), value.attemptNo(), value.versionNo());
    }

    private static ProjectFileRoleAssessment domain(ProjectFileRoleAssessmentPersistenceRow value) {
        requirePrecedingSource(value);
        ProjectFileRoleModelProvenance model = value.modelProvider() == null ? null
                : new ProjectFileRoleModelProvenance(value.modelProvider(), value.modelName(), value.modelRevision(),
                value.promptVersion(), value.schemaVersion());
        ProjectFileRoleAssessment assessment = new ProjectFileRoleAssessment(
                UUID.fromString(value.assessmentId()), UUID.fromString(value.projectId()),
                UUID.fromString(value.fileId()), value.fileSha256(), UUID.fromString(value.parseResultId()),
                value.parseVersion(), RoleAssessmentExecutionStatus.valueOf(value.executionStatus()),
                role(value.proposedRole()), value.confidence(), value.conflictFlag(),
                ProjectFileRoleAssessmentSource.valueOf(value.assessmentSource()), value.assessmentVersion(),
                value.rolePolicyVersion(), candidates(value.candidateRolesJson()), uuid(value.precedingDeterministicAssessmentId()),
                model, value.inputFingerprint(), value.safeReasonCode(), instant(value.createdAt()),
                instant(value.startedAt()), instant(value.completedAt()), value.attemptNo(), value.versionNo());
        if (!fenceKey(assessment).equals(value.assessmentFenceKey())) {
            throw new IllegalStateException("invalid role assessment fence key");
        }
        return assessment;
    }

    private static String candidates(ProjectFileRoleAssessment value) {
        if (value.executionStatus() != RoleAssessmentExecutionStatus.COMPLETED) return null;
        try { return JSON.writeValueAsString(value.candidateRoles()); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot encode candidate role projection", e); }
    }

    private static List<ProjectFileRoleCandidateProjection> candidates(String value) {
        if (value == null) return List.of();
        try { return JSON.readValue(value, CANDIDATES); }
        catch (JsonProcessingException e) { throw new IllegalStateException("invalid candidate role projection", e); }
    }

    private static String name(Enum<?> value) { return value == null ? null : value.name(); }
    private static DocumentRole role(String value) { return value == null ? null : DocumentRole.valueOf(value); }
    private static String text(UUID value) { return value == null ? null : value.toString(); }
    private static String precedingSource(ProjectFileRoleAssessment value) {
        return value.precedingDeterministicAssessmentId() == null ? null
                : ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC.name();
    }
    private static boolean isActive(ProjectFileRoleAssessment value) {
        return value.executionStatus() == RoleAssessmentExecutionStatus.PENDING
                || value.executionStatus() == RoleAssessmentExecutionStatus.RUNNING
                || value.executionStatus() == RoleAssessmentExecutionStatus.UNKNOWN;
    }
    private static String fenceKey(ProjectFileRoleAssessment value) {
        return isActive(value) ? value.inputFingerprint() : value.assessmentId().toString();
    }
    private static void requirePrecedingSource(ProjectFileRoleAssessmentPersistenceRow value) {
        boolean deterministic = ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC.name().equals(value.assessmentSource());
        if ((deterministic && value.precedingAssessmentSource() != null)
                || (!deterministic && !ProjectFileRoleAssessmentSource.MACHINE_DETERMINISTIC.name()
                .equals(value.precedingAssessmentSource()))) {
            throw new IllegalStateException("invalid preceding role assessment source");
        }
    }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static LocalDateTime time(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
}
