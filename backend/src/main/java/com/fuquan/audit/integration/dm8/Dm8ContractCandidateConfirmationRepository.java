package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"local-dev", "dev29", "customer"})
public class Dm8ContractCandidateConfirmationRepository implements ContractCandidateConfirmationRepository {
    private final ContractCandidateConfirmationMapper mapper;

    public Dm8ContractCandidateConfirmationRepository(ContractCandidateConfirmationMapper mapper) {
        this.mapper = mapper;
    }

    @Override public void insert(ContractCandidateConfirmation value) {
        if (mapper.insert(row(value)) != 1) throw new IllegalStateException("candidate confirmation insert failed");
    }

    @Override public Optional<ContractCandidateConfirmation> findByProjectAndCandidateAndId(
            UUID projectId, UUID candidateId, UUID confirmationId) {
        return Optional.ofNullable(mapper.findByProjectAndCandidateAndId(
                projectId.toString(), candidateId.toString(), confirmationId.toString())).map(this::domain);
    }

    @Override public Optional<ContractCandidateConfirmation> findLatestByProjectAndCandidate(
            UUID projectId, UUID candidateId) {
        return Optional.ofNullable(mapper.findLatestByProjectAndCandidate(
                projectId.toString(), candidateId.toString())).map(this::domain);
    }

    private static ContractCandidateConfirmationPersistenceRow row(ContractCandidateConfirmation v) {
        return new ContractCandidateConfirmationPersistenceRow(v.confirmationId().toString(),
                v.projectId().toString(), v.candidateId().toString(), v.baseCandidateVersionNo(),
                v.resultCandidateVersionNo(), v.sourceFileId().toString(), v.sourceParseResultId().toString(),
                v.sourceRoleDecisionId().toString(), v.sourceIdentityDecisionId().toString(),
                v.payloadSchemaVersion(), v.payloadFingerprint(), v.canonicalPayloadJson(), v.confirmedBy(),
                java.time.LocalDateTime.ofInstant(v.confirmedAt(), ZoneOffset.UTC),
                v.supersedesConfirmationId() == null ? null : v.supersedesConfirmationId().toString(),
                java.time.LocalDateTime.ofInstant(v.createdAt(), ZoneOffset.UTC));
    }

    private ContractCandidateConfirmation domain(ContractCandidateConfirmationPersistenceRow v) {
        return new ContractCandidateConfirmation(UUID.fromString(v.confirmationId()),
                UUID.fromString(v.projectId()), UUID.fromString(v.candidateId()),
                v.baseCandidateVersionNo(), v.resultCandidateVersionNo(), UUID.fromString(v.sourceFileId()),
                UUID.fromString(v.sourceParseResultId()), UUID.fromString(v.sourceRoleDecisionId()),
                UUID.fromString(v.sourceIdentityDecisionId()), v.payloadSchemaVersion(), v.payloadFingerprint(),
                v.canonicalPayloadJson(), v.confirmedBy(), v.confirmedAt().toInstant(ZoneOffset.UTC),
                v.supersedesConfirmationId() == null ? null : UUID.fromString(v.supersedesConfirmationId()),
                v.createdAt().toInstant(ZoneOffset.UTC));
    }
}
