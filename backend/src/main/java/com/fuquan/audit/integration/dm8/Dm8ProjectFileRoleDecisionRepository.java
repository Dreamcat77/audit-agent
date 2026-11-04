package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
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
public class Dm8ProjectFileRoleDecisionRepository implements ProjectFileRoleDecisionRepository {
    private final ProjectFileRoleDecisionMapper mapper;

    public Dm8ProjectFileRoleDecisionRepository(ProjectFileRoleDecisionMapper mapper) { this.mapper = mapper; }

    @Override
    public ProjectFileRoleDecision insertIfAbsent(ProjectFileRoleDecision decision) {
        try {
            mapper.insert(row(decision));
            return decision;
        } catch (DuplicateKeyException conflict) {
            ProjectFileRoleDecisionPersistenceRow raced = mapper.findById(decision.decisionId().toString());
            if (raced != null) {
                ProjectFileRoleDecision byId = domain(raced);
                if (!byId.equals(decision)) throw new IllegalStateException("role decision identity conflict");
                return byId;
            }
            raced = mapper.findByFileIdAndVersion(decision.fileId().toString(), decision.versionNo());
            if (raced == null) throw conflict;
            ProjectFileRoleDecision winner = domain(raced);
            if (!winner.sameStreamFact(decision)) throw new IllegalStateException("role decision stream conflict");
            return winner;
        }
    }

    @Override public Optional<ProjectFileRoleDecision> findById(UUID decisionId) {
        return Optional.ofNullable(mapper.findById(decisionId.toString())).map(Dm8ProjectFileRoleDecisionRepository::domain);
    }

    @Override public Optional<ProjectFileRoleDecision> findByFileIdAndVersion(UUID fileId, long versionNo) {
        return Optional.ofNullable(mapper.findByFileIdAndVersion(fileId.toString(), versionNo))
                .map(Dm8ProjectFileRoleDecisionRepository::domain);
    }

    @Override public Optional<ProjectFileRoleDecision> findLatestByFileId(UUID fileId) {
        return Optional.ofNullable(mapper.findLatestByFileId(fileId.toString())).map(Dm8ProjectFileRoleDecisionRepository::domain);
    }

    private static ProjectFileRoleDecisionPersistenceRow row(ProjectFileRoleDecision value) {
        return new ProjectFileRoleDecisionPersistenceRow(value.decisionId().toString(), value.projectId().toString(),
                value.fileId().toString(), value.fileSha256(), value.parseResultId().toString(), value.parseVersion(),
                value.assessmentId().toString(), value.selectedRole().name(), value.decisionStatus().name(),
                value.decidedBy(), time(value.decidedAt()), text(value.supersedesDecisionId()), value.reason(), value.versionNo());
    }

    private static ProjectFileRoleDecision domain(ProjectFileRoleDecisionPersistenceRow value) {
        return new ProjectFileRoleDecision(UUID.fromString(value.decisionId()), UUID.fromString(value.projectId()),
                UUID.fromString(value.fileId()), value.fileSha256(), UUID.fromString(value.parseResultId()),
                value.parseVersion(), UUID.fromString(value.assessmentId()), DocumentRole.valueOf(value.selectedRole()),
                RoleDecisionStatus.valueOf(value.decisionStatus()), value.decidedBy(),
                value.decidedAt().toInstant(ZoneOffset.UTC), uuid(value.supersedesDecisionId()), value.reason(), value.versionNo());
    }

    private static String text(UUID value) { return value == null ? null : value.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static LocalDateTime time(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
