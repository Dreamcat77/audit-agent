package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFactIdentityConflictException;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectFactReplayIntegrityTest {

    @Test
    void parseBoundFactsCanBeQueriedAndBoundToOnlyOneCandidate() {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        UUID projectId = UUID.randomUUID();
        UUID sourceFileId = UUID.randomUUID();
        UUID parseResultId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        String key = "c".repeat(64);
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        ExtractionResult fact = new ExtractionResult(UUID.randomUUID(), projectId, sourceFileId,
                parseResultId, null, key, "project-extraction-v2", "CONTRACT_NO", null,
                "C-001", "C-001", null, "合同编号：C-001", 1, 1,
                "pdf/page:1/paragraph:1/fact:CONTRACT_NO/occurrence:1", now);
        repositories.extractionResultRepository().insertIfAbsent(fact);

        assertThat(repositories.extractionResultRepository()
                .findBySourceFileIdAndParseResultId(projectId, sourceFileId, parseResultId))
                .containsExactly(fact);

        ExtractionResult bound = repositories.extractionResultRepository().bindCandidateIfUnbound(
                projectId, sourceFileId, parseResultId, key, candidateId);
        assertThat(bound.candidateId()).isEqualTo(candidateId);
        assertThat(bound.parseResultId()).isEqualTo(parseResultId);
        assertThat(repositories.extractionResultRepository().bindCandidateIfUnbound(
                projectId, sourceFileId, parseResultId, key, candidateId)).isEqualTo(bound);
        assertThatThrownBy(() -> repositories.extractionResultRepository().bindCandidateIfUnbound(
                projectId, sourceFileId, parseResultId, key, UUID.randomUUID()))
                .isInstanceOfSatisfying(ProjectFactIdentityConflictException.class, conflict ->
                        assertThat(conflict.reasonCode()).isEqualTo("EXTRACTION_CANDIDATE_BINDING_CONFLICT"));
    }

    @Test
    void currentCandidateCarriesExactAuthorityWhileLegacyCandidateKeepsNullProvenance() {
        UUID projectId = UUID.randomUUID();
        UUID sourceFileId = UUID.randomUUID();
        UUID parseResultId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-15T00:00:00Z");

        ContractCandidate current = ContractCandidate.draftWithAuthority(UUID.randomUUID(), projectId,
                sourceFileId, parseResultId, decisionId, ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                "d".repeat(64), "C-1", "Contract", "GOODS", "Buyer", "Supplier",
                "123456789012345678", new BigDecimal("12.00"), "UPPER", null,
                "Supplier", "123456789012345678", java.util.List.of(), now);
        ContractCandidate legacy = candidate(projectId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("12.00"), null, now);

        assertThat(current.sourceParseResultId()).isEqualTo(parseResultId);
        assertThat(current.sourceRoleDecisionId()).isEqualTo(decisionId);
        assertThat(current.sourceAuthoritySource()).isEqualTo(ContractCandidateAuthoritySource.HUMAN_CONFIRMATION);
        assertThat(current.sourceFactFingerprint()).isEqualTo("d".repeat(64));
        assertThat(legacy.sourceParseResultId()).isNull();
        assertThat(legacy.sourceRoleDecisionId()).isNull();
        assertThat(legacy.sourceAuthoritySource()).isNull();
        assertThat(legacy.sourceFactFingerprint()).isNull();
    }

    @Test
    void v1AndV2FactsCoexistWhileIdenticalV2ReplayKeepsItsKey() {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        UUID projectId = UUID.randomUUID();
        UUID sourceFileId = UUID.randomUUID();
        UUID parseResultId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        ExtractionKeyFactory keys = new ExtractionKeyFactory();
        ExtractionKeyFactoryV2 keysV2 = new ExtractionKeyFactoryV2();
        String locator = "docx/body/paragraph:1/fact:PROJECT_NO/occurrence:1";
        String v1Key = keys.create(sourceFileId, "project-extraction-v1", "PROJECT_NO", null, locator);
        String v2Key = keysV2.create(sourceFileId, parseResultId, "project-extraction-v2", "PROJECT_NO", null, locator);
        ExtractionResult v1 = new ExtractionResult(UUID.randomUUID(), projectId, sourceFileId, null,
                v1Key, "project-extraction-v1", "PROJECT_NO", null, "P-1", "P-1", null,
                "项目编号：P-1", null, 1, locator, now);
        ExtractionResult v2 = new ExtractionResult(UUID.randomUUID(), projectId, sourceFileId, null,
                v2Key, "project-extraction-v2", "PROJECT_NO", null, "P-1", "P-1", null,
                "项目编号：P-1", null, 1, locator, now.plusSeconds(1));
        ExtractionResult v2Replay = new ExtractionResult(UUID.randomUUID(), projectId, sourceFileId, null,
                keysV2.create(sourceFileId, parseResultId, "project-extraction-v2", "PROJECT_NO", null, locator),
                "project-extraction-v2", "PROJECT_NO", null, "P-1", "P-1", null,
                "项目编号：P-1", null, 1, locator, now.plusSeconds(2));

        assertThat(v1Key).isNotEqualTo(v2Key);
        repositories.extractionResultRepository().insertIfAbsent(v1);
        ExtractionResult storedV2 = repositories.extractionResultRepository().insertIfAbsent(v2);

        assertThat(repositories.extractionResultRepository().insertIfAbsent(v2Replay)).isEqualTo(storedV2);
        assertThat(repositories.extractionResultRepository().findByProjectId(projectId))
                .containsExactlyInAnyOrder(v1, v2);
    }

    @Test
    void replayReusesSemanticallyIdenticalFactsDespiteGeneratedIdsTimesAndDecimalScale() {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        UUID projectId = UUID.randomUUID();
        UUID sourceFileId = UUID.randomUUID();
        Instant firstTime = Instant.parse("2026-08-26T00:00:00Z");
        Instant replayTime = firstTime.plusSeconds(30);

        ContractCandidate firstCandidate = candidate(projectId, sourceFileId, UUID.randomUUID(),
                new BigDecimal("12.0"), new BigDecimal("11.00"), firstTime);
        ContractCandidate replayCandidate = candidate(projectId, sourceFileId, UUID.randomUUID(),
                new BigDecimal("12.00"), new BigDecimal("11.0"), replayTime);

        ContractCandidate storedCandidate = repositories.contractCandidateRepository().insertIfAbsent(firstCandidate);
        assertThat(repositories.contractCandidateRepository().insertIfAbsent(replayCandidate))
                .isEqualTo(storedCandidate);

        String extractionKey = "a".repeat(64);
        ExtractionResult first = new ExtractionResult(UUID.randomUUID(), projectId, sourceFileId,
                storedCandidate.id(), extractionKey, "v1", "AMOUNT_NUMERIC", null, "12.0", "12.0",
                new BigDecimal("12.0"), "Contract Amount: 12.0", 1, null, "page:1/fact:1", firstTime);
        ExtractionResult replay = new ExtractionResult(UUID.randomUUID(), projectId, sourceFileId,
                storedCandidate.id(), extractionKey, "v1", "AMOUNT_NUMERIC", null, "12.0", "12.0",
                new BigDecimal("12.00"), "Contract Amount: 12.0", 1, null, "page:1/fact:1", replayTime);

        ExtractionResult storedExtraction = repositories.extractionResultRepository().insertIfAbsent(first);
        assertThat(repositories.extractionResultRepository().insertIfAbsent(replay))
                .isEqualTo(storedExtraction);
    }

    @Test
    void concurrentCandidateReplayPublishesOneStableIdentity() throws Exception {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        UUID projectId = UUID.randomUUID();
        UUID sourceFileId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<ContractCandidate> insertion = () -> {
                start.await();
                return repositories.contractCandidateRepository().insertIfAbsent(candidate(
                        projectId, sourceFileId, UUID.randomUUID(), new BigDecimal("12.00"), null, now));
            };
            var first = workers.submit(insertion);
            var second = workers.submit(insertion);
            start.countDown();
            assertThat(first.get().id()).isEqualTo(second.get().id());
            assertThat(repositories.contractCandidateRepository().findByProjectId(projectId)).hasSize(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void replayAfterPromotionReturnsExistingLifecycleWithoutConflictOrDuplicate() {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        UUID projectId = UUID.randomUUID();
        UUID sourceFileId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-26T00:00:00Z");
        ContractCandidate promoted = candidate(projectId, sourceFileId, UUID.randomUUID(),
                new BigDecimal("12.00"), null, createdAt)
                .promote(UUID.randomUUID(), "actor", createdAt.plusSeconds(1));
        ContractCandidate replayedExtraction = candidate(projectId, sourceFileId, UUID.randomUUID(),
                new BigDecimal("12.0"), null, createdAt.plusSeconds(30));

        repositories.contractCandidateRepository().insertIfAbsent(promoted);
        assertThat(repositories.contractCandidateRepository().insertIfAbsent(replayedExtraction))
                .isEqualTo(promoted);
        assertThat(repositories.contractCandidateRepository().findByProjectId(projectId)).containsExactly(promoted);

        ContractCandidate changedFact = ContractCandidate.draft(UUID.randomUUID(), projectId, sourceFileId,
                "C-CHANGED", "Contract", "GOODS", "Buyer", "Supplier", "123456789012345678",
                new BigDecimal("12.00"), "UPPER", null, "Supplier", "123456789012345678",
                java.util.List.of(), createdAt.plusSeconds(60));
        assertThatThrownBy(() -> repositories.contractCandidateRepository().insertIfAbsent(changedFact))
                .isInstanceOfSatisfying(ProjectFactIdentityConflictException.class, conflict ->
                        assertThat(conflict.reasonCode()).isEqualTo("CANDIDATE_IDENTITY_CONFLICT"));
        assertThat(repositories.contractCandidateRepository().findByProjectId(projectId)).containsExactly(promoted);
    }

    @Test
    void extractionCollisionUsesStableTypedIntegrityReason() {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        UUID projectId = UUID.randomUUID();
        UUID sourceFileId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        String key = "b".repeat(64);
        ExtractionResult first = new ExtractionResult(UUID.randomUUID(), projectId, sourceFileId,
                null, key, "v1", "CONTRACT_NO", null, "C-1", "C-1", null,
                "Contract No: C-1", 1, null, "page:1/fact:1", now);
        ExtractionResult conflicting = new ExtractionResult(UUID.randomUUID(), projectId, sourceFileId,
                null, key, "v1", "CONTRACT_NO", null, "C-2", "C-2", null,
                "Contract No: C-2", 1, null, "page:1/fact:1", now.plusSeconds(1));

        repositories.extractionResultRepository().insertIfAbsent(first);
        assertThatThrownBy(() -> repositories.extractionResultRepository().insertIfAbsent(conflicting))
                .isInstanceOfSatisfying(ProjectFactIdentityConflictException.class, conflict ->
                        assertThat(conflict.reasonCode()).isEqualTo("EXTRACTION_IDENTITY_CONFLICT"));
        assertThat(repositories.extractionResultRepository().findByProjectId(projectId)).containsExactly(first);
    }

    private static ContractCandidate candidate(UUID projectId, UUID sourceFileId, UUID id,
                                               BigDecimal amount, BigDecimal awardAmount, Instant now) {
        return ContractCandidate.draft(id, projectId, sourceFileId,
                "C-1", "Contract", "GOODS", "Buyer", "Supplier", "123456789012345678",
                amount, "UPPER", awardAmount, "Supplier", "123456789012345678", java.util.List.of(), now);
    }
}
