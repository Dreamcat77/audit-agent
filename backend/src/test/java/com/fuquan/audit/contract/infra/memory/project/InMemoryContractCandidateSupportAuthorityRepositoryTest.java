package com.fuquan.audit.contract.infra.memory.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

class InMemoryContractCandidateSupportAuthorityRepositoryTest {
    private static final UUID PROJECT_A = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID PROJECT_B = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID CANDIDATE_A = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID CANDIDATE_B = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID CANDIDATE_C = UUID.fromString("00000000-0000-0000-0000-000000000050");

    @Test
    void scopesAndSortsCurrentRowsByCanonicalSourceFileIdText() {
        InMemoryContractCandidateSupportAuthorityRepository repository = new InMemoryContractCandidateSupportAuthorityRepository();
        ContractCandidateSupportAuthority later = authority(PROJECT_A, CANDIDATE_A, UUID.fromString("00000000-0000-0000-0000-0000000000ff"), 1);
        ContractCandidateSupportAuthority first = authority(PROJECT_A, CANDIDATE_A, UUID.fromString("00000000-0000-0000-0000-000000000001"), 1);
        ContractCandidateSupportAuthority otherCandidate = authority(PROJECT_A, CANDIDATE_B, first.sourceFileId(), 1);
        ContractCandidateSupportAuthority otherProject = authority(PROJECT_B, CANDIDATE_C, UUID.fromString("00000000-0000-0000-0000-000000000003"), 1);
        repository.insertAll(List.of(later, first, otherCandidate, otherProject));
        assertThat(repository.findCurrentByProjectIdAndCandidateId(PROJECT_A, CANDIDATE_A)).extracting(ContractCandidateSupportAuthority::sourceFileId).containsExactly(first.sourceFileId(), later.sourceFileId());
        assertThat(repository.findCurrentByProjectIdAndCandidateIdForUpdate(PROJECT_A, CANDIDATE_A)).extracting(ContractCandidateSupportAuthority::sourceFileId).containsExactly(first.sourceFileId(), later.sourceFileId());
        assertThat(repository.findByProjectIdAndCandidateIdAndSourceFileId(PROJECT_A, CANDIDATE_A, first.sourceFileId())).contains(first);
        assertThat(repository.findByProjectIdAndCandidateIdAndSourceFileId(PROJECT_B, CANDIDATE_A, first.sourceFileId())).isEmpty();
    }

    @Test
    void rejectsDuplicateCandidateFileButAllowsSharedSupportFileAcrossCandidates() {
        InMemoryContractCandidateSupportAuthorityRepository repository = new InMemoryContractCandidateSupportAuthorityRepository();
        UUID sharedFile = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ContractCandidateSupportAuthority first = authority(PROJECT_A, CANDIDATE_A, sharedFile, 1);
        ContractCandidateSupportAuthority duplicate = authority(PROJECT_A, CANDIDATE_A, sharedFile, 2);
        ContractCandidateSupportAuthority otherCandidate = authority(PROJECT_A, CANDIDATE_B, sharedFile, 1);
        repository.insertAll(List.of(first));
        assertThatIllegalStateException().isThrownBy(() -> repository.insertAll(List.of(duplicate)));
        repository.insertAll(List.of(otherCandidate));
        assertThat(repository.findCurrentByProjectIdAndCandidateId(PROJECT_A, CANDIDATE_B)).containsExactly(otherCandidate);
    }

    @Test
    void rejectsBatchLocalCandidateProjectConflictWithoutMutation() {
        InMemoryContractCandidateSupportAuthorityRepository repository = new InMemoryContractCandidateSupportAuthorityRepository();
        ContractCandidateSupportAuthority projectA = authority(PROJECT_A, CANDIDATE_A,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1);
        ContractCandidateSupportAuthority projectB = authority(PROJECT_B, CANDIDATE_A,
                UUID.fromString("00000000-0000-0000-0000-000000000002"), 1);

        assertThatIllegalStateException().isThrownBy(() -> repository.insertAll(List.of(projectA, projectB)));
        assertThat(repository.findCurrentByProjectIdAndCandidateId(PROJECT_A, CANDIDATE_A)).isEmpty();
        assertThat(repository.findCurrentByProjectIdAndCandidateId(PROJECT_B, CANDIDATE_A)).isEmpty();
    }

    @Test
    void rejectsBatchLocalSourceFileProjectConflictWithoutMutation() {
        InMemoryContractCandidateSupportAuthorityRepository repository = new InMemoryContractCandidateSupportAuthorityRepository();
        UUID sharedFile = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ContractCandidateSupportAuthority projectA = authority(PROJECT_A, CANDIDATE_A, sharedFile, 1);
        ContractCandidateSupportAuthority projectB = authority(PROJECT_B, CANDIDATE_B, sharedFile, 1);

        assertThatIllegalStateException().isThrownBy(() -> repository.insertAll(List.of(projectA, projectB)));
        assertThat(repository.findCurrentByProjectIdAndCandidateId(PROJECT_A, CANDIDATE_A)).isEmpty();
        assertThat(repository.findCurrentByProjectIdAndCandidateId(PROJECT_B, CANDIDATE_B)).isEmpty();
    }

    @Test
    void replacesOrDeletesAndInsertsOneDeterministicCurrentCohort() {
        InMemoryContractCandidateSupportAuthorityRepository repository = new InMemoryContractCandidateSupportAuthorityRepository();
        ContractCandidateSupportAuthority old = authority(PROJECT_A, CANDIDATE_A, UUID.fromString("00000000-0000-0000-0000-000000000099"), 1);
        ContractCandidateSupportAuthority first = authority(PROJECT_A, CANDIDATE_A, UUID.fromString("00000000-0000-0000-0000-000000000002"), 2);
        ContractCandidateSupportAuthority second = authority(PROJECT_A, CANDIDATE_A, UUID.fromString("00000000-0000-0000-0000-000000000001"), 2);
        repository.insertAll(List.of(old));
        repository.replaceCurrentCohort(PROJECT_A, CANDIDATE_A, List.of(first, second));
        assertThat(repository.findCurrentByProjectIdAndCandidateId(PROJECT_A, CANDIDATE_A)).extracting(ContractCandidateSupportAuthority::authorityVersion, ContractCandidateSupportAuthority::sourceFileId).containsExactly(Tuple.tuple(2L, second.sourceFileId()), Tuple.tuple(2L, first.sourceFileId()));
        repository.deleteCurrentByProjectIdAndCandidateId(PROJECT_A, CANDIDATE_A);
        repository.insertAll(List.of(old));
        assertThat(repository.findCurrentByProjectIdAndCandidateId(PROJECT_A, CANDIDATE_A)).containsExactly(old);
    }

    @Test
    void failsClosedWhenCohortOrExistingCandidateOwnershipCrossesProjects() {
        InMemoryContractCandidateSupportAuthorityRepository repository = new InMemoryContractCandidateSupportAuthorityRepository();
        ContractCandidateSupportAuthority owned = authority(PROJECT_A, CANDIDATE_A, UUID.fromString("00000000-0000-0000-0000-000000000001"), 1);
        ContractCandidateSupportAuthority foreign = authority(PROJECT_B, CANDIDATE_A, UUID.fromString("00000000-0000-0000-0000-000000000002"), 1);
        repository.insertAll(List.of(owned));
        assertThatIllegalArgumentException().isThrownBy(() -> repository.replaceCurrentCohort(PROJECT_A, CANDIDATE_A, List.of(foreign)));
        assertThatIllegalStateException().isThrownBy(() -> repository.insertAll(List.of(foreign)));
        assertThat(repository.findCurrentByProjectIdAndCandidateId(PROJECT_A, CANDIDATE_A)).containsExactly(owned);
    }

    @Test
    void rejectsSupportAuthorityIdentityReusedByAnotherCandidateDuringReplacement() {
        InMemoryContractCandidateSupportAuthorityRepository repository = new InMemoryContractCandidateSupportAuthorityRepository();
        ContractCandidateSupportAuthority owned = authority(PROJECT_A, CANDIDATE_A,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1);
        ContractCandidateSupportAuthority collision = new ContractCandidateSupportAuthority(owned.supportAuthorityId(),
                PROJECT_A, CANDIDATE_B, UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "a".repeat(64), UUID.randomUUID(), 1, 1, DocumentRole.AWARD_NOTICE, UUID.randomUUID(),
                ProjectIdentityResolution.CONFIRM_SAME_PROJECT, UUID.randomUUID(), "b".repeat(64),
                1, "auditor-1", Instant.parse("2026-09-20T00:00:00Z"));
        repository.insertAll(List.of(owned));

        assertThatIllegalStateException().isThrownBy(() -> repository.replaceCurrentCohort(PROJECT_A,
                CANDIDATE_B, List.of(collision)));
        assertThat(repository.findCurrentByProjectIdAndCandidateId(PROJECT_A, CANDIDATE_A)).containsExactly(owned);
    }

    @Test
    void rejectsStableSupportAuthorityIdentityReboundToAnotherSourceFileDuringReplacement() {
        InMemoryContractCandidateSupportAuthorityRepository repository = new InMemoryContractCandidateSupportAuthorityRepository();
        ContractCandidateSupportAuthority owned = authority(PROJECT_A, CANDIDATE_A,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1);
        ContractCandidateSupportAuthority rebound = new ContractCandidateSupportAuthority(owned.supportAuthorityId(),
                PROJECT_A, CANDIDATE_A, UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "a".repeat(64), UUID.randomUUID(), 1, 1, DocumentRole.AWARD_NOTICE, UUID.randomUUID(),
                ProjectIdentityResolution.CONFIRM_SAME_PROJECT, UUID.randomUUID(), "b".repeat(64),
                2, "auditor-1", Instant.parse("2026-09-20T00:00:00Z"));
        repository.insertAll(List.of(owned));

        assertThatIllegalStateException().isThrownBy(() -> repository.replaceCurrentCohort(PROJECT_A,
                CANDIDATE_A, List.of(rebound)));
        assertThat(repository.findCurrentByProjectIdAndCandidateId(PROJECT_A, CANDIDATE_A)).containsExactly(owned);
    }

    private static ContractCandidateSupportAuthority authority(UUID projectId, UUID candidateId, UUID sourceFileId, long authorityVersion) {
        return new ContractCandidateSupportAuthority(UUID.randomUUID(), projectId, candidateId, sourceFileId, "a".repeat(64), UUID.randomUUID(), 1, 1, DocumentRole.AWARD_NOTICE, UUID.randomUUID(), ProjectIdentityResolution.CONFIRM_SAME_PROJECT, UUID.randomUUID(), "b".repeat(64), authorityVersion, "auditor-1", Instant.parse("2026-09-20T00:00:00Z"));
    }
}
