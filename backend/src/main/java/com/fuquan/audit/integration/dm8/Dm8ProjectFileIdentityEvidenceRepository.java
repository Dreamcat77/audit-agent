package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityEvidenceRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityEvidence;
import com.fuquan.audit.contract.domain.project.ProjectIdentityComparisonOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProjectFileIdentityEvidenceRepository implements ProjectFileIdentityEvidenceRepository {
    private final ProjectFileIdentityEvidenceMapper mapper;
    public Dm8ProjectFileIdentityEvidenceRepository(ProjectFileIdentityEvidenceMapper mapper) {
        this.mapper = mapper;
    }
    @Override public void insertAll(UUID id, List<ProjectFileIdentityEvidence> values) {
        for (var value : values) {
            if (!id.equals(value.assessmentId())) throw new IllegalArgumentException("identity evidence assessment mismatch");
            mapper.insert(row(value));
        }
    }
    @Override public List<ProjectFileIdentityEvidence> findByAssessmentId(UUID id) {
        return mapper.findByAssessmentId(id.toString()).stream().map(Dm8ProjectFileIdentityEvidenceRepository::domain)
                .toList();
    }
    static ProjectFileIdentityEvidencePersistenceRow row(ProjectFileIdentityEvidence value) {
        return new ProjectFileIdentityEvidencePersistenceRow(value.evidenceId().toString(),
                value.assessmentId().toString(), value.projectId().toString(), value.projectVersionNo(),
                value.fileId().toString(), value.fileSha256(), value.parseResultId().toString(),
                value.parseVersion(), value.factType(), text(value.extractionResultId()), value.leftSource(),
                value.rightSource(), value.leftNormalizedHash(), value.rightNormalizedHash(),
                value.leftBoundedValue(), value.rightBoundedValue(), value.comparisonOutcome().name(),
                value.significanceCode(), value.sourceLocator(),
                Dm8ProjectFileIdentityAssessmentRepository.time(value.createdAt()));
    }
    static ProjectFileIdentityEvidence domain(ProjectFileIdentityEvidencePersistenceRow value) {
        return new ProjectFileIdentityEvidence(UUID.fromString(value.evidenceId()),
                UUID.fromString(value.assessmentId()), UUID.fromString(value.projectId()), value.projectVersionNo(),
                UUID.fromString(value.fileId()), value.fileSha256(), UUID.fromString(value.parseResultId()),
                value.parseVersion(), value.factType(), uuid(value.extractionResultId()), value.leftSource(),
                value.rightSource(), value.leftNormalizedHash(), value.rightNormalizedHash(),
                value.leftBoundedValue(), value.rightBoundedValue(),
                ProjectIdentityComparisonOutcome.valueOf(value.comparisonOutcome()),
                value.significanceCode(), value.sourceLocator(),
                Dm8ProjectFileIdentityAssessmentRepository.instant(value.createdAt()));
    }
    private static String text(UUID value) { return value == null ? null : value.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
