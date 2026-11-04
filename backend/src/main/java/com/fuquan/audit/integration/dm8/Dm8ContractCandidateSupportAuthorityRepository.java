package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"local-dev", "dev29", "customer"})
public class Dm8ContractCandidateSupportAuthorityRepository
        implements ContractCandidateSupportAuthorityRepository {
    private final ContractCandidateSupportAuthorityMapper mapper;

    public Dm8ContractCandidateSupportAuthorityRepository(ContractCandidateSupportAuthorityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ContractCandidateSupportAuthority> findCurrentByProjectIdAndCandidateId(
            UUID projectId, UUID candidateId) {
        return mapper.findCurrentByProjectAndCandidate(projectId.toString(), candidateId.toString()).stream()
                .map(this::domain).toList();
    }

    @Override
    public List<ContractCandidateSupportAuthority> findCurrentByProjectIdAndCandidateIdForUpdate(
            UUID projectId, UUID candidateId) {
        return mapper.findCurrentByProjectAndCandidateForUpdate(projectId.toString(), candidateId.toString()).stream()
                .map(this::domain).toList();
    }

    @Override
    public Optional<ContractCandidateSupportAuthority> findByProjectIdAndCandidateIdAndSourceFileId(
            UUID projectId, UUID candidateId, UUID sourceFileId) {
        return Optional.ofNullable(mapper.findByProjectAndCandidateAndSourceFile(projectId.toString(),
                candidateId.toString(), sourceFileId.toString())).map(this::domain);
    }

    @Override
    @Transactional
    public void replaceCurrentCohort(UUID projectId, UUID candidateId,
            List<ContractCandidateSupportAuthority> cohort) {
        List<ContractCandidateSupportAuthority> copy = List.copyOf(cohort);
        for (ContractCandidateSupportAuthority authority : copy) {
            if (!projectId.equals(authority.projectId()) || !candidateId.equals(authority.candidateId())) {
                throw new IllegalArgumentException("support authority cohort ownership mismatch");
            }
        }
        deleteCurrentByProjectIdAndCandidateId(projectId, candidateId);
        insertAll(copy);
    }

    @Override
    public void deleteCurrentByProjectIdAndCandidateId(UUID projectId, UUID candidateId) {
        mapper.deleteCurrentByProjectAndCandidate(projectId.toString(), candidateId.toString());
    }

    @Override
    public void insertAll(List<ContractCandidateSupportAuthority> cohort) {
        List<ContractCandidateSupportAuthorityPersistenceRow> rows = List.copyOf(cohort).stream()
                .sorted(Comparator.comparing(value -> value.sourceFileId().toString()))
                .map(Dm8ContractCandidateSupportAuthorityRepository::row).toList();
        if (!rows.isEmpty()) mapper.insertAll(rows);
    }

    private static ContractCandidateSupportAuthorityPersistenceRow row(ContractCandidateSupportAuthority value) {
        return new ContractCandidateSupportAuthorityPersistenceRow(value.supportAuthorityId().toString(),
                value.projectId().toString(), value.candidateId().toString(), value.sourceFileId().toString(),
                value.fileSha256(), value.parseResultId().toString(), value.parseVersion(), value.projectVersionNo(),
                value.effectiveRole().name(), value.roleDecisionId().toString(), value.identityResolution().name(),
                value.identityDecisionId().toString(), value.sourceFactFingerprint(), value.authorityVersion(),
                value.createdBy(), LocalDateTime.ofInstant(value.createdAt(), ZoneOffset.UTC));
    }

    private ContractCandidateSupportAuthority domain(ContractCandidateSupportAuthorityPersistenceRow value) {
        return new ContractCandidateSupportAuthority(UUID.fromString(value.supportAuthorityId()),
                UUID.fromString(value.projectId()), UUID.fromString(value.candidateId()),
                UUID.fromString(value.sourceFileId()), value.fileSha256(), UUID.fromString(value.parseResultId()),
                value.parseVersion(), value.projectVersionNo(), DocumentRole.valueOf(value.effectiveRole()),
                UUID.fromString(value.roleDecisionId()), ProjectIdentityResolution.valueOf(value.identityResolution()),
                UUID.fromString(value.identityDecisionId()), value.sourceFactFingerprint(), value.authorityVersion(),
                value.createdBy(), value.createdAt().toInstant(ZoneOffset.UTC));
    }
}
