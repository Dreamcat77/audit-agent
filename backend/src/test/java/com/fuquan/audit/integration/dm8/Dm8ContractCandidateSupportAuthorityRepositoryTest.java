package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class Dm8ContractCandidateSupportAuthorityRepositoryTest {

    @Test
    void mapsTheEntireExactAuthorityTupleAndBindsEveryReadToItsOwnershipScope() {
        ContractCandidateSupportAuthorityMapper mapper = mock(ContractCandidateSupportAuthorityMapper.class);
        Dm8ContractCandidateSupportAuthorityRepository repository =
                new Dm8ContractCandidateSupportAuthorityRepository(mapper);
        ContractCandidateSupportAuthorityPersistenceRow row = row("00000000-0000-0000-0000-000000000001");
        UUID projectId = UUID.fromString(row.projectId());
        UUID candidateId = UUID.fromString(row.candidateId());
        UUID sourceFileId = UUID.fromString(row.sourceFileId());
        when(mapper.findCurrentByProjectAndCandidate(projectId.toString(), candidateId.toString()))
                .thenReturn(List.of(row));
        when(mapper.findCurrentByProjectAndCandidateForUpdate(projectId.toString(), candidateId.toString()))
                .thenReturn(List.of(row));
        when(mapper.findByProjectAndCandidateAndSourceFile(projectId.toString(), candidateId.toString(),
                sourceFileId.toString())).thenReturn(row);

        ContractCandidateSupportAuthority expected = authority(row);
        assertThat(repository.findCurrentByProjectIdAndCandidateId(projectId, candidateId))
                .containsExactly(expected);
        assertThat(repository.findCurrentByProjectIdAndCandidateIdForUpdate(projectId, candidateId))
                .containsExactly(expected);
        assertThat(repository.findByProjectIdAndCandidateIdAndSourceFileId(projectId, candidateId, sourceFileId))
                .contains(expected);

        verify(mapper).findCurrentByProjectAndCandidate(projectId.toString(), candidateId.toString());
        verify(mapper).findCurrentByProjectAndCandidateForUpdate(projectId.toString(), candidateId.toString());
        verify(mapper).findByProjectAndCandidateAndSourceFile(projectId.toString(), candidateId.toString(),
                sourceFileId.toString());
    }

    @Test
    void deletesOnlyTheRequestedCandidateCohortAndInsertsSuppliedImmutableRowsUnchanged() {
        ContractCandidateSupportAuthorityMapper mapper = mock(ContractCandidateSupportAuthorityMapper.class);
        Dm8ContractCandidateSupportAuthorityRepository repository =
                new Dm8ContractCandidateSupportAuthorityRepository(mapper);
        ContractCandidateSupportAuthority first = authority(row("00000000-0000-0000-0000-000000000011",
                "00000000-0000-0000-0000-000000000111"));
        ContractCandidateSupportAuthority second = authority(row("00000000-0000-0000-0000-000000000012",
                "00000000-0000-0000-0000-000000000110"));

        repository.deleteCurrentByProjectIdAndCandidateId(first.projectId(), first.candidateId());
        repository.insertAll(List.of(second, first));

        verify(mapper).deleteCurrentByProjectAndCandidate(first.projectId().toString(), first.candidateId().toString());
        ArgumentCaptor<List<ContractCandidateSupportAuthorityPersistenceRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertAll(rows.capture());
        assertThat(rows.getValue()).containsExactly(row(second), row(first));
    }

    @Test
    void replacementRejectsForeignCandidateRowsBeforeDeletingTheRequestedCohort() {
        ContractCandidateSupportAuthorityMapper mapper = mock(ContractCandidateSupportAuthorityMapper.class);
        Dm8ContractCandidateSupportAuthorityRepository repository =
                new Dm8ContractCandidateSupportAuthorityRepository(mapper);
        UUID projectId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        ContractCandidateSupportAuthority foreignCandidate = authority(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> repository.replaceCurrentCohort(projectId, candidateId, List.of(foreignCandidate)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(mapper);
    }

    @Test
    void replacementRejectsNullCohortRowsBeforeDeletingTheRequestedCohort() {
        ContractCandidateSupportAuthorityMapper mapper = mock(ContractCandidateSupportAuthorityMapper.class);
        Dm8ContractCandidateSupportAuthorityRepository repository =
                new Dm8ContractCandidateSupportAuthorityRepository(mapper);

        assertThatThrownBy(() -> repository.replaceCurrentCohort(UUID.randomUUID(), UUID.randomUUID(),
                Arrays.asList((ContractCandidateSupportAuthority) null)))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(mapper);
    }

    @Test
    void replacementIsRequiredTransactionalAndDeletesThenInsertsOrOnlyDeletesForAnEmptyCohort() throws Exception {
        Transactional transactional = Dm8ContractCandidateSupportAuthorityRepository.class.getMethod(
                "replaceCurrentCohort", UUID.class, UUID.class, List.class).getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);

        ContractCandidateSupportAuthorityMapper mapper = mock(ContractCandidateSupportAuthorityMapper.class);
        Dm8ContractCandidateSupportAuthorityRepository repository =
                new Dm8ContractCandidateSupportAuthorityRepository(mapper);
        ContractCandidateSupportAuthority authority = authority(UUID.randomUUID(), UUID.randomUUID());
        repository.replaceCurrentCohort(authority.projectId(), authority.candidateId(), List.of(authority));

        InOrder ordered = inOrder(mapper);
        ordered.verify(mapper).deleteCurrentByProjectAndCandidate(authority.projectId().toString(),
                authority.candidateId().toString());
        ordered.verify(mapper).insertAll(org.mockito.ArgumentMatchers.anyList());

        ContractCandidateSupportAuthorityMapper emptyMapper = mock(ContractCandidateSupportAuthorityMapper.class);
        Dm8ContractCandidateSupportAuthorityRepository emptyRepository =
                new Dm8ContractCandidateSupportAuthorityRepository(emptyMapper);
        emptyRepository.replaceCurrentCohort(authority.projectId(), authority.candidateId(), List.of());
        verify(emptyMapper).deleteCurrentByProjectAndCandidate(authority.projectId().toString(),
                authority.candidateId().toString());
        verifyNoMoreInteractions(emptyMapper);
    }

    @Test
    void emptyCurrentAndExactReadsRemainEmptyWithoutApplicationExceptions() {
        ContractCandidateSupportAuthorityMapper mapper = mock(ContractCandidateSupportAuthorityMapper.class);
        Dm8ContractCandidateSupportAuthorityRepository repository =
                new Dm8ContractCandidateSupportAuthorityRepository(mapper);
        UUID projectId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID sourceFileId = UUID.randomUUID();
        when(mapper.findCurrentByProjectAndCandidate(projectId.toString(), candidateId.toString())).thenReturn(List.of());
        when(mapper.findCurrentByProjectAndCandidateForUpdate(projectId.toString(), candidateId.toString()))
                .thenReturn(List.of());
        when(mapper.findByProjectAndCandidateAndSourceFile(projectId.toString(), candidateId.toString(),
                sourceFileId.toString())).thenReturn(null);

        assertThat(repository.findCurrentByProjectIdAndCandidateId(projectId, candidateId)).isEmpty();
        assertThat(repository.findCurrentByProjectIdAndCandidateIdForUpdate(projectId, candidateId)).isEmpty();
        assertThat(repository.findByProjectIdAndCandidateIdAndSourceFileId(projectId, candidateId, sourceFileId))
                .isEmpty();
    }

    private static ContractCandidateSupportAuthorityPersistenceRow row(String supportAuthorityId) {
        return row(supportAuthorityId, "00000000-0000-0000-0000-000000000103");
    }

    private static ContractCandidateSupportAuthorityPersistenceRow row(String supportAuthorityId, String sourceFileId) {
        return new ContractCandidateSupportAuthorityPersistenceRow(supportAuthorityId,
                "00000000-0000-0000-0000-000000000101", "00000000-0000-0000-0000-000000000102",
                sourceFileId, "a".repeat(64),
                "00000000-0000-0000-0000-000000000104", 2, 3L, "AWARD_NOTICE",
                "00000000-0000-0000-0000-000000000105", "CONFIRM_SAME_PROJECT",
                "00000000-0000-0000-0000-000000000106", "b".repeat(64), 4L, "actor-1",
                LocalDateTime.of(2026, 9, 20, 1, 2, 3));
    }

    private static ContractCandidateSupportAuthority authority(ContractCandidateSupportAuthorityPersistenceRow row) {
        return new ContractCandidateSupportAuthority(UUID.fromString(row.supportAuthorityId()),
                UUID.fromString(row.projectId()), UUID.fromString(row.candidateId()),
                UUID.fromString(row.sourceFileId()), row.fileSha256(), UUID.fromString(row.parseResultId()),
                row.parseVersion(), row.projectVersionNo(), DocumentRole.valueOf(row.effectiveRole()),
                UUID.fromString(row.roleDecisionId()), ProjectIdentityResolution.valueOf(row.identityResolution()),
                UUID.fromString(row.identityDecisionId()), row.sourceFactFingerprint(), row.authorityVersion(),
                row.createdBy(), row.createdAt().toInstant(java.time.ZoneOffset.UTC));
    }

    private static ContractCandidateSupportAuthority authority(UUID projectId, UUID candidateId) {
        ContractCandidateSupportAuthority original = authority(row("00000000-0000-0000-0000-000000000021"));
        return new ContractCandidateSupportAuthority(original.supportAuthorityId(), projectId, candidateId,
                original.sourceFileId(), original.fileSha256(), original.parseResultId(), original.parseVersion(),
                original.projectVersionNo(), original.effectiveRole(), original.roleDecisionId(),
                original.identityResolution(), original.identityDecisionId(), original.sourceFactFingerprint(),
                original.authorityVersion(), original.createdBy(), original.createdAt());
    }

    private static ContractCandidateSupportAuthorityPersistenceRow row(ContractCandidateSupportAuthority value) {
        return new ContractCandidateSupportAuthorityPersistenceRow(value.supportAuthorityId().toString(),
                value.projectId().toString(), value.candidateId().toString(), value.sourceFileId().toString(),
                value.fileSha256(), value.parseResultId().toString(), value.parseVersion(), value.projectVersionNo(),
                value.effectiveRole().name(), value.roleDecisionId().toString(), value.identityResolution().name(),
                value.identityDecisionId().toString(), value.sourceFactFingerprint(), value.authorityVersion(),
                value.createdBy(), LocalDateTime.ofInstant(value.createdAt(), java.time.ZoneOffset.UTC));
    }
}
