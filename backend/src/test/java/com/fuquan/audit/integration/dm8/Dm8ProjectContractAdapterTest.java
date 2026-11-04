package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;

class Dm8ProjectContractAdapterTest {

    @Test
    void historicalCandidateRowWithNullSupportMetadataRetainsPrimaryAwardFacts() throws Exception {
        ContractCandidatePersistenceRow row = new ContractCandidatePersistenceRow(
                "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004",
                "00000000-0000-0000-0000-000000000005", "00000000-0000-0000-0000-000000000006",
                "HUMAN_CONFIRMATION", "f".repeat(64),
                "C-LEGACY", "Legacy Contract", "GOODS", "Buyer", "Supplier", null,
                new java.math.BigDecimal("100.00"), null, new java.math.BigDecimal("98.00"),
                "Award Supplier", "91350000LEGACY", "DRAFT", 4L, null, null, null, null,
                LocalDateTime.of(2026, 9, 20, 0, 0), LocalDateTime.of(2026, 9, 20, 0, 0), null, null);
        ContractCandidateMapper candidateMapper = mock(ContractCandidateMapper.class);
        when(candidateMapper.findByProjectAndId(row.projectId(), row.id())).thenReturn(row);
        when(candidateMapper.findLinesByCandidate(row.projectId(), row.id())).thenReturn(List.of());
        Dm8ProjectRepositories repositories = new Dm8ProjectRepositories(mock(ProjectMapper.class),
                mock(ProjectFileMapper.class), mock(ProjectExtractionResultMapper.class), candidateMapper);

        ContractCandidate candidate = repositories.contractCandidateRepository()
                .findByProjectIdAndId(UUID.fromString(row.projectId()), UUID.fromString(row.id()))
                .orElseThrow();

        assertThat(candidate.id()).isEqualTo(UUID.fromString(row.id()));
        assertThat(candidate.projectId()).isEqualTo(UUID.fromString(row.projectId()));
        assertThat(candidate.sourceContractFileId()).isEqualTo(UUID.fromString(row.sourceContractFileId()));
        assertThat(candidate.sourceParseResultId()).isEqualTo(UUID.fromString(row.sourceParseResultId()));
        assertThat(candidate.sourceRoleDecisionId()).isEqualTo(UUID.fromString(row.sourceRoleDecisionId()));
        assertThat(candidate.sourceIdentityDecisionId()).isEqualTo(UUID.fromString(row.sourceIdentityDecisionId()));
        assertThat(candidate.sourceFactFingerprint()).isEqualTo(row.sourceFactFingerprint());
        assertThat(candidate.versionNo()).isEqualTo(4L);
        assertThat(candidate.awardAmount()).isEqualByComparingTo("98.00");
        assertThat(candidate.awardSupplier()).isEqualTo("Award Supplier");
        assertThat(candidate.supportAuthorityVersionNo()).isNull();
        assertThat(candidate.supportAuthorityFingerprint()).isNull();
    }

    @Test
    void roundTripsExplicitSupportMetadataAndMapsItInTheCandidateMapper() throws Exception {
        ContractCandidatePersistenceRow row = new ContractCandidatePersistenceRow(
                "00000000-0000-0000-0000-000000000011", "00000000-0000-0000-0000-000000000012",
                "00000000-0000-0000-0000-000000000013", "00000000-0000-0000-0000-000000000014",
                "00000000-0000-0000-0000-000000000015", "00000000-0000-0000-0000-000000000016",
                "HUMAN_CONFIRMATION", "a".repeat(64), "C-SUPPORT", "Support Contract", "GOODS",
                "Buyer", "Supplier", null, new java.math.BigDecimal("100.00"), null,
                new java.math.BigDecimal("98.00"), "Award Supplier", "91350000SUPPORT", "DRAFT", 4L,
                null, null, null, null, LocalDateTime.of(2026, 9, 20, 0, 0),
                LocalDateTime.of(2026, 9, 20, 0, 0), 3L, "c".repeat(64));
        ContractCandidateMapper candidateMapper = mock(ContractCandidateMapper.class);
        when(candidateMapper.findByProjectAndId(row.projectId(), row.id())).thenReturn(row);
        when(candidateMapper.findLinesByCandidate(row.projectId(), row.id())).thenReturn(List.of());
        Dm8ProjectRepositories repositories = new Dm8ProjectRepositories(mock(ProjectMapper.class),
                mock(ProjectFileMapper.class), mock(ProjectExtractionResultMapper.class), candidateMapper);

        ContractCandidate candidate = repositories.contractCandidateRepository()
                .findByProjectIdAndId(UUID.fromString(row.projectId()), UUID.fromString(row.id()))
                .orElseThrow();

        assertThat(candidate.supportAuthorityVersionNo()).isEqualTo(3L);
        assertThat(candidate.supportAuthorityFingerprint()).isEqualTo("c".repeat(64));
        when(candidateMapper.findBySource(row.projectId(), row.sourceContractFileId())).thenReturn(null);
        repositories.contractCandidateRepository().insertIfAbsent(candidate);
        org.mockito.ArgumentCaptor<ContractCandidatePersistenceRow> inserted =
                org.mockito.ArgumentCaptor.forClass(ContractCandidatePersistenceRow.class);
        verify(candidateMapper, times(1)).insert(inserted.capture());
        assertThat(inserted.getValue().supportAuthorityVersionNo()).isEqualTo(3L);
        assertThat(inserted.getValue().supportAuthorityFingerprint()).isEqualTo("c".repeat(64));

        String mapperXml = new String(getClass().getClassLoader()
                .getResourceAsStream("mappers/dm8/ContractCandidateMapper.xml").readAllBytes());
        assertThat(mapperXml).contains("SUPPORT_AUTHORITY_VERSION_NO", "SUPPORT_AUTHORITY_FINGERPRINT",
                "#{row.supportAuthorityVersionNo}", "#{row.supportAuthorityFingerprint}");
    }

    @Test
    void mapsInsertAndAllThreeOwnershipQueries() {
        ProjectContractMapper mapper = mock(ProjectContractMapper.class);
        Dm8ProjectContractAdapter adapter = new Dm8ProjectContractAdapter(mapper);
        UUID projectId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        ProjectContractPersistenceRow row = new ProjectContractPersistenceRow(projectId.toString(),
                contractId.toString(), candidateId.toString(), "actor", LocalDateTime.of(2026, 8, 26, 0, 0));
        when(mapper.findByProjectId(projectId.toString())).thenReturn(List.of(row));
        when(mapper.findByContractId(contractId.toString())).thenReturn(row);
        when(mapper.findByCandidateId(candidateId.toString())).thenReturn(row);

        ProjectContract association = new ProjectContract(projectId, contractId, candidateId, "actor",
                Instant.parse("2026-08-26T00:00:00Z"));
        adapter.insert(association);

        verify(mapper).insert(row);
        assertThat(adapter.findByProjectId(projectId)).containsExactly(association);
        assertThat(adapter.findByContractId(contractId)).contains(association);
        assertThat(adapter.findByCandidateId(candidateId)).contains(association);
    }

    @Test
    void provenUniqueConflictIsTerminalButConnectivityFailureIsNotReclassified() {
        ProjectContractMapper mapper = mock(ProjectContractMapper.class);
        Dm8ProjectContractAdapter adapter = new Dm8ProjectContractAdapter(mapper);
        ProjectContract value = new ProjectContract(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "actor", Instant.parse("2026-08-26T00:00:00Z"));
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenThrow(new DuplicateKeyException("duplicate"));
        assertThatThrownBy(() -> adapter.insert(value)).isInstanceOf(BusinessException.class);

        reset(mapper);
        org.springframework.dao.DataAccessResourceFailureException outage =
                new org.springframework.dao.DataAccessResourceFailureException("outage");
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenThrow(outage);
        assertThatThrownBy(() -> adapter.insert(value)).isSameAs(outage);
    }
}
