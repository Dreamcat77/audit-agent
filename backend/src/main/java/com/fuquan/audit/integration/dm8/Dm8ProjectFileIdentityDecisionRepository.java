package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProjectFileIdentityDecisionRepository implements ProjectFileIdentityDecisionRepository {
    private final ProjectFileIdentityDecisionMapper mapper;
    public Dm8ProjectFileIdentityDecisionRepository(ProjectFileIdentityDecisionMapper mapper) { this.mapper = mapper; }
    @Override public ProjectFileIdentityDecision insertIfAbsent(ProjectFileIdentityDecision value) {
        try {
            mapper.insert(row(value));
            return value;
        } catch (DuplicateKeyException collision) {
            var byId = mapper.findById(value.decisionId().toString());
            if (byId != null) {
                var winner = domain(byId);
                if (!winner.equals(value)) throw new IllegalStateException("identity decision ID conflict");
                return winner;
            }
            var stream = mapper.findByFileIdAndVersion(value.fileId().toString(), value.versionNo());
            if (stream == null) throw collision;
            var winner = domain(stream);
            if (!winner.equals(value)) throw new IllegalStateException("identity decision stream conflict");
            return winner;
        }
    }
    @Override public Optional<ProjectFileIdentityDecision> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id.toString())).map(Dm8ProjectFileIdentityDecisionRepository::domain);
    }
    @Override public Optional<ProjectFileIdentityDecision> findByFileIdAndVersion(UUID fileId, long version) {
        return Optional.ofNullable(mapper.findByFileIdAndVersion(fileId.toString(), version))
                .map(Dm8ProjectFileIdentityDecisionRepository::domain);
    }
    @Override public Optional<ProjectFileIdentityDecision> findLatestByFileId(UUID fileId) {
        return Optional.ofNullable(mapper.findLatestByFileId(fileId.toString()))
                .map(Dm8ProjectFileIdentityDecisionRepository::domain);
    }
    static ProjectFileIdentityDecisionPersistenceRow row(ProjectFileIdentityDecision value) {
        return new ProjectFileIdentityDecisionPersistenceRow(value.decisionId().toString(),
                value.projectId().toString(), value.projectVersionNo(), value.fileId().toString(),
                value.fileSha256(), value.parseResultId().toString(), value.parseVersion(),
                value.assessmentId().toString(), value.resolution().name(), value.decidedBy(),
                Dm8ProjectFileIdentityAssessmentRepository.time(value.decidedAt()),
                text(value.supersedesDecisionId()), value.reason(), value.versionNo());
    }
    static ProjectFileIdentityDecision domain(ProjectFileIdentityDecisionPersistenceRow value) {
        return new ProjectFileIdentityDecision(UUID.fromString(value.decisionId()),
                UUID.fromString(value.projectId()), value.projectVersionNo(), UUID.fromString(value.fileId()),
                value.fileSha256(), UUID.fromString(value.parseResultId()), value.parseVersion(),
                UUID.fromString(value.assessmentId()), ProjectIdentityResolution.valueOf(value.resolution()),
                value.decidedBy(), Dm8ProjectFileIdentityAssessmentRepository.instant(value.decidedAt()),
                uuid(value.supersedesDecisionId()), value.reason(), value.versionNo());
    }
    private static String text(UUID value) { return value == null ? null : value.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
