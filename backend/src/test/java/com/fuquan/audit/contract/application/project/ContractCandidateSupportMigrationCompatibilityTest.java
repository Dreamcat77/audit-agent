package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.FindingSourceProjection;
import com.fuquan.audit.contract.application.FindingSourceProjectionService;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.infra.memory.PromotionContractFixtures;
import com.fuquan.audit.integration.dm8.ContractCandidatePersistenceRow;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Compatibility contract for rows persisted before candidate support authority existed.
 *
 * <p>This test intentionally uses reflection for the additive read fields: it must compile on
 * the pre-Task-2 tree, while still requiring the later durable read model to expose them.</p>
 */
class ContractCandidateSupportMigrationCompatibilityTest {

    @Test
    void historicalNullMetadataAndNoSupportRowsRequirePrimaryOnlyCompatibilityMode() {
        List<String> rowComponents = Arrays.stream(ContractCandidatePersistenceRow.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(rowComponents)
                .as("historical Candidate rows need nullable durable support metadata")
                .contains("supportAuthorityVersionNo", "supportAuthorityFingerprint");
    }

    @Test
    void primaryOnlyPromotedCandidateKeepsLegacyCrossFileAwardEvidenceWithoutMutation() {
        Instant now = Instant.parse("2026-09-20T00:00:00Z");
        UUID projectId = UUID.randomUUID();
        UUID sourceFileId = UUID.randomUUID();
        UUID awardFileId = UUID.randomUUID();
        Contract contract = PromotionContractFixtures.contract(UUID.randomUUID(), "C-LEGACY");
        ContractCandidate candidate = ContractCandidate.draft(UUID.randomUUID(), projectId, sourceFileId,
                contract.contractNo(), contract.contractName(), contract.contractType(), contract.partyA(),
                contract.partyB(), contract.partyBCreditCode(), contract.contractAmount().amount(),
                contract.amountInWords(), contract.awardNotice().winningAmount().amount(),
                contract.awardNotice().winningBidder(), contract.awardNotice().winningBidderCreditCode(),
                List.of(), now).promote(contract.id(), "actor", now);
        ProjectContractQueryPort associations = mock(ProjectContractQueryPort.class);
        ContractCandidateRepository candidates = mock(ContractCandidateRepository.class);
        ProjectFileRepository files = mock(ProjectFileRepository.class);
        ExtractionResultRepository extractions = mock(ExtractionResultRepository.class);
        when(associations.findByContractId(contract.id())).thenReturn(java.util.Optional.of(
                new ProjectContract(projectId, contract.id(), candidate.id(), "actor", now)));
        when(candidates.findByProjectIdAndId(projectId, candidate.id())).thenReturn(java.util.Optional.of(candidate));
        ProjectFile sourceFile = file(sourceFileId, projectId, "contract.pdf", ProjectFileCategory.CONTRACT_BODY,
                "a".repeat(64), now);
        ProjectFile awardFile = file(awardFileId, projectId, "award.pdf", ProjectFileCategory.AWARD_NOTICE,
                "b".repeat(64), now);
        when(files.findByProjectIdAndId(projectId, sourceFileId)).thenReturn(java.util.Optional.of(sourceFile));
        when(files.findByProjectIdAndId(projectId, awardFileId)).thenReturn(java.util.Optional.of(awardFile));
        ExtractionResult contractAmount = extraction(projectId, sourceFileId, candidate.id(), "AMOUNT_NUMERIC",
                contract.contractAmount().amount(), now);
        ExtractionResult unassignedAward = extraction(projectId, awardFileId, null, "AWARD_AMOUNT",
                contract.awardNotice().winningAmount().amount(), now);
        when(extractions.findByProjectId(projectId)).thenReturn(List.of(contractAmount, unassignedAward));

        FindingSourceProjection projection = new FindingSourceProjectionService(associations, candidates, files,
                extractions).project(contract, finding(now));

        assertThat(projection.available()).isTrue();
        assertThat(projection.comparisonMode()).isEqualTo("CROSS_FILE");
        assertThat(projection.sources()).extracting(FindingSourceProjection.Source::fileId)
                .containsExactly(sourceFileId, awardFileId);
        assertThat(projection.sources()).extracting(FindingSourceProjection.Source::role)
                .containsExactly("CONTRACT", "AWARD_NOTICE");
        assertThat(candidate.sourceContractFileId()).isEqualTo(sourceFileId);
        assertThat(candidate.awardAmount()).isEqualByComparingTo(contract.awardNotice().winningAmount().amount());
        verify(candidates).findByProjectIdAndId(projectId, candidate.id());
    }

    @Test
    void applicationConfigurationNeverStartsTheControlledSupportAuthoritySql() throws Exception {
        for (String relativePath : List.of(
                "backend/pom.xml",
                "backend/src/main/resources/application.yml",
                "backend/src/main/resources/application-local-dev.yml",
                "backend/src/main/resources/application-dev29.yml",
                "backend/src/main/resources/application-customer.yml")) {
            String source = Files.readString(repositoryRoot().resolve(relativePath)).toLowerCase();
            assertThat(source).doesNotContain("flyway", "liquibase",
                    "contract__contract_candidate_support_authority.sql");
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        return Files.isDirectory(current.resolve("backend")) ? current : current.getParent();
    }

    private static ProjectFile file(UUID id, UUID projectId, String name, ProjectFileCategory category,
                                    String sha, Instant now) {
        return new ProjectFile(id, projectId, null, null, name, "application/pdf", category,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED, sha, sha, 1L,
                "synthetic", null, now, now);
    }

    private static ExtractionResult extraction(UUID projectId, UUID sourceFileId, UUID candidateId,
                                                String factType, BigDecimal amount, Instant now) {
        return new ExtractionResult(UUID.randomUUID(), projectId, sourceFileId, candidateId,
                "c".repeat(64), "v1", factType, null, amount.toPlainString(), amount.toPlainString(), amount,
                "synthetic " + factType, 1, null, "pdf:page:1", now);
    }

    private static AuditCheckResult finding(Instant now) {
        return new AuditCheckResult(UUID.randomUUID(), UUID.randomUUID(), F02RuleCodes.AWARD_AMOUNT_MATCH, null,
                CheckStatus.FAIL, RiskLevel.RED, true, "actual", "expected", "detail", 1, now);
    }
}
