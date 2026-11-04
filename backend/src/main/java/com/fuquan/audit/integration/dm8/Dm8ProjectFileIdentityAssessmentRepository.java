package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProjectFileIdentityAssessmentRepository implements ProjectFileIdentityAssessmentRepository {
    private final ProjectFileIdentityAssessmentMapper mapper;
    public Dm8ProjectFileIdentityAssessmentRepository(ProjectFileIdentityAssessmentMapper mapper) {
        this.mapper = mapper;
    }
    @Override public ProjectFileIdentityAssessment insertIfAbsent(ProjectFileIdentityAssessment value) {
        try {
            mapper.insert(row(value));
            return value;
        } catch (DuplicateKeyException collision) {
            var existing = mapper.findById(value.assessmentId().toString());
            if (existing == null) throw collision;
            var winner = domain(existing);
            if (!winner.equals(value)) throw new IllegalStateException("identity assessment ID conflict");
            return winner;
        }
    }
    @Override public Optional<ProjectFileIdentityAssessment> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id.toString()))
                .map(Dm8ProjectFileIdentityAssessmentRepository::domain);
    }
    @Override public Optional<ProjectFileIdentityAssessment> findLatestByFileId(UUID fileId) {
        return Optional.ofNullable(mapper.findLatestByFileId(fileId.toString()))
                .map(Dm8ProjectFileIdentityAssessmentRepository::domain);
    }
    static ProjectFileIdentityAssessmentPersistenceRow row(ProjectFileIdentityAssessment value) {
        return new ProjectFileIdentityAssessmentPersistenceRow(value.assessmentId().toString(),
                value.projectId().toString(), value.projectVersionNo(), value.fileId().toString(),
                value.fileSha256(), value.parseResultId().toString(), value.parseVersion(),
                value.assessmentSource().name(), value.assessmentVersion(), value.identityPolicyVersion(),
                value.status().name(), value.normalizedInputFingerprint(), value.safeReasonCode(),
                time(value.createdAt()), time(value.completedAt()), value.versionNo());
    }
    static ProjectFileIdentityAssessment domain(ProjectFileIdentityAssessmentPersistenceRow value) {
        return new ProjectFileIdentityAssessment(UUID.fromString(value.assessmentId()),
                UUID.fromString(value.projectId()), value.projectVersionNo(), UUID.fromString(value.fileId()),
                value.fileSha256(), UUID.fromString(value.parseResultId()), value.parseVersion(),
                ProjectIdentityAssessmentSource.valueOf(value.assessmentSource()), value.assessmentVersion(),
                value.identityPolicyVersion(), ProjectIdentityAssessmentStatus.valueOf(value.status()),
                value.normalizedInputFingerprint(), value.safeReasonCode(), instant(value.createdAt()),
                instant(value.completedAt()), value.versionNo());
    }
    static LocalDateTime time(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
}
