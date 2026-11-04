package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.port.ProjectFileRoleEvidenceRepository;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.DocumentRoleSignal;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleEvidence;
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
public class Dm8ProjectFileRoleEvidenceRepository implements ProjectFileRoleEvidenceRepository {
    private final ProjectFileRoleEvidenceMapper mapper;

    public Dm8ProjectFileRoleEvidenceRepository(ProjectFileRoleEvidenceMapper mapper) { this.mapper = mapper; }

    @Override
    public ProjectFileRoleEvidence insertIfAbsent(ProjectFileRoleEvidence evidence) {
        try {
            mapper.insert(row(evidence));
            return evidence;
        } catch (DuplicateKeyException conflict) {
            ProjectFileRoleEvidencePersistenceRow raced = mapper.findById(evidence.evidenceId().toString());
            if (raced != null) {
                ProjectFileRoleEvidence byId = domain(raced);
                if (!byId.sameIdentityFact(evidence)) throw new IllegalStateException("role evidence identity conflict");
                return byId;
            }
            raced = mapper.findBySignal(evidence.assessmentId().toString(), evidence.role().name(),
                    evidence.ruleCode(), evidence.sourceLocator());
            if (raced == null) throw conflict;
            ProjectFileRoleEvidence winner = domain(raced);
            if (!winner.sameSignalFact(evidence)) throw new IllegalStateException("role evidence identity conflict");
            return winner;
        }
    }

    @Override public Optional<ProjectFileRoleEvidence> findById(UUID evidenceId) {
        return Optional.ofNullable(mapper.findById(evidenceId.toString())).map(Dm8ProjectFileRoleEvidenceRepository::domain);
    }

    @Override public List<ProjectFileRoleEvidence> findByAssessmentId(UUID assessmentId) {
        return mapper.findByAssessmentId(assessmentId.toString()).stream().map(Dm8ProjectFileRoleEvidenceRepository::domain).toList();
    }

    private static ProjectFileRoleEvidencePersistenceRow row(ProjectFileRoleEvidence value) {
        return new ProjectFileRoleEvidencePersistenceRow(value.evidenceId().toString(), value.assessmentId().toString(),
                value.projectId().toString(), value.fileId().toString(), value.fileSha256(), value.parseResultId().toString(),
                value.parseVersion(), value.role().name(), value.signalType().name(), value.normalizedValue(), value.ruleCode(),
                value.weight(), value.sourceLocator(), value.charStart(), value.charEnd(), value.textHash(), value.boundedExcerpt(),
                value.extractorVersion(), time(value.createdAt()));
    }

    private static ProjectFileRoleEvidence domain(ProjectFileRoleEvidencePersistenceRow value) {
        return new ProjectFileRoleEvidence(UUID.fromString(value.evidenceId()), UUID.fromString(value.assessmentId()),
                UUID.fromString(value.projectId()), UUID.fromString(value.fileId()), value.fileSha256(),
                UUID.fromString(value.parseResultId()), value.parseVersion(), DocumentRole.valueOf(value.role()),
                DocumentRoleSignal.SignalType.valueOf(value.signalType()), value.normalizedValue(), value.ruleCode(),
                value.weight(), value.sourceLocator(), value.charStart(), value.charEnd(), value.textHash(),
                value.boundedExcerpt(), value.extractorVersion(), value.createdAt().toInstant(ZoneOffset.UTC));
    }

    private static LocalDateTime time(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
