package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FindingSourceProjectionFrozenTest {
    private static final Instant NOW = Instant.parse("2026-09-23T09:30:00Z");

    @Test void usesOnlyExactFrozenParseTuplesAndNeverNeedsCurrentCandidateAuthority() {
        var contract = LocalContractFixtures.contracts().stream()
                .filter(value -> value.id().equals(LocalContractFixtures.FAIL_CONTRACT_ID)).findFirst().orElseThrow();
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        var run = new ContractReviewRun(runId, projectId, contract.id(), contract.versionNo(), null, null, 2,
                "{}", ContractSnapshotCanonicalizer.sha256("{}"), "profile", "rules", "models",
                "a".repeat(64), "actor", ContractReviewRunStatus.RUNNING, NOW, NOW,
                null, null, null, null, 1);
        var primary = material(1, DocumentRole.CONTRACT_BODY, "合同金额 849000.00");
        var award = material(2, DocumentRole.AWARD_NOTICE, "中标金额 876000.00");
        var input = new FrozenReviewInput(run, contract, List.of(primary, award));
        var extractions = mock(ExtractionResultRepository.class);
        when(extractions.findBySourceFileIdAndParseResultId(projectId, primary.fileId(), primary.parseResultId()))
                .thenReturn(List.of(fact(projectId, primary, "AMOUNT_NUMERIC", null, "849000.00",
                        contract.contractAmount().amount(), "合同金额 849000.00")));
        when(extractions.findBySourceFileIdAndParseResultId(projectId, award.fileId(), award.parseResultId()))
                .thenReturn(List.of(fact(projectId, award, "AWARD_AMOUNT", null, "876000.00",
                        contract.awardNotice().winningAmount().amount(), "中标金额 876000.00")));
        var service = new FindingSourceProjectionService(mock(ProjectContractQueryPort.class),
                mock(ContractCandidateRepository.class), mock(ProjectFileRepository.class), extractions);
        var check = new AuditCheckResult(UUID.randomUUID(), UUID.randomUUID(), F02RuleCodes.AWARD_AMOUNT_MATCH,
                null, CheckStatus.FAIL, RiskLevel.YELLOW, false, "849000.00", "876000.00",
                "mismatch", 1, NOW);

        var projected = service.projectFrozen(input, check);

        assertThat(projected.available()).isTrue();
        assertThat(projected.sources()).extracting(FindingSourceProjection.Source::fileId)
                .containsExactly(primary.fileId(), award.fileId());

        var wrongParse = fact(projectId, primary, "AMOUNT_NUMERIC", null, "849000.00",
                contract.contractAmount().amount(), "合同金额 849000.00");
        wrongParse = new ExtractionResult(wrongParse.id(), wrongParse.projectId(), wrongParse.sourceFileId(),
                UUID.randomUUID(), wrongParse.candidateId(), wrongParse.extractionKey(), wrongParse.extractionVersion(),
                wrongParse.factType(), wrongParse.groupKey(), wrongParse.valueText(), wrongParse.normalizedValue(),
                wrongParse.valueAmount(), wrongParse.sourceText(), wrongParse.pageNumber(),
                wrongParse.paragraphIndex(), wrongParse.sourceLocator(), wrongParse.createdAt());
        when(extractions.findBySourceFileIdAndParseResultId(projectId, primary.fileId(), primary.parseResultId()))
                .thenReturn(List.of(wrongParse));
        assertThat(service.projectFrozen(input, check).reason()).isEqualTo("FROZEN_PARSE_FACTS_UNVERIFIED");
    }

    @Test void collapsesEquivalentDeterministicAndAiProofsFromTheSameFrozenAnchor() {
        var contract = LocalContractFixtures.contracts().stream()
                .filter(value -> value.id().equals(LocalContractFixtures.FAIL_CONTRACT_ID)).findFirst().orElseThrow();
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        var run = new ContractReviewRun(runId, projectId, contract.id(), contract.versionNo(), null, null, 2,
                "{}", ContractSnapshotCanonicalizer.sha256("{}"), "profile", "rules", "models",
                "a".repeat(64), "actor", ContractReviewRunStatus.RUNNING, NOW, NOW,
                null, null, null, null, 1);
        var primary = material(1, DocumentRole.CONTRACT_BODY, "合同金额 849000.00，大写金额捌拾肆万玖仟元整");
        var input = new FrozenReviewInput(run, contract, List.of(primary));
        var amount = fact(projectId, primary, "AMOUNT_NUMERIC", null, "849000.00",
                contract.contractAmount().amount(), "合同金额 849000.00");
        var deterministic = fact(projectId, primary, "AMOUNT_UPPERCASE", null, contract.amountInWords(),
                null, "大写金额" + contract.amountInWords());
        var aiCandidate = new ExtractionResult(UUID.randomUUID(), deterministic.projectId(),
                deterministic.sourceFileId(), deterministic.parseResultId(), UUID.randomUUID(),
                "d".repeat(64), "ai-v1", deterministic.factType(), deterministic.groupKey(),
                deterministic.valueText(), deterministic.normalizedValue(), deterministic.valueAmount(),
                deterministic.sourceText(), deterministic.pageNumber(), deterministic.paragraphIndex(),
                deterministic.sourceLocator(), NOW.plusSeconds(1));
        var extractions = mock(ExtractionResultRepository.class);
        when(extractions.findBySourceFileIdAndParseResultId(projectId, primary.fileId(), primary.parseResultId()))
                .thenReturn(List.of(amount, deterministic, aiCandidate));
        var service = new FindingSourceProjectionService(mock(ProjectContractQueryPort.class),
                mock(ContractCandidateRepository.class), mock(ProjectFileRepository.class), extractions);
        var check = new AuditCheckResult(UUID.randomUUID(), UUID.randomUUID(), F02RuleCodes.AMOUNT_WORDS_MATCH,
                null, CheckStatus.PASS, RiskLevel.GREEN, false, "849000.00", "捌拾肆万玖仟元整",
                "match", 1, NOW);

        var projected = service.projectFrozen(input, check);

        assertThat(projected.available()).isTrue();
        assertThat(projected.sources()).hasSize(2);
        assertThat(projected.sources()).filteredOn(source -> source.excerpt().equals(deterministic.sourceText()))
                .singleElement().satisfies(source -> {
                    assertThat(source.fileId()).isEqualTo(primary.fileId());
                    assertThat(source.locator()).isEqualTo(deterministic.sourceLocator());
                });
    }

    @Test void keepsDifferentFrozenAnchorsAmbiguousEvenWhenValuesMatch() {
        var contract = LocalContractFixtures.contracts().stream()
                .filter(value -> value.id().equals(LocalContractFixtures.FAIL_CONTRACT_ID)).findFirst().orElseThrow();
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        var run = new ContractReviewRun(runId, projectId, contract.id(), contract.versionNo(), null, null, 2,
                "{}", ContractSnapshotCanonicalizer.sha256("{}"), "profile", "rules", "models",
                "a".repeat(64), "actor", ContractReviewRunStatus.RUNNING, NOW, NOW,
                null, null, null, null, 1);
        var primary = material(1, DocumentRole.CONTRACT_BODY, "合同金额 849000.00，大写金额捌拾肆万玖仟元整");
        var input = new FrozenReviewInput(run, contract, List.of(primary));
        var amount = fact(projectId, primary, "AMOUNT_NUMERIC", null, "849000.00",
                contract.contractAmount().amount(), "合同金额 849000.00");
        var first = fact(projectId, primary, "AMOUNT_UPPERCASE", null, contract.amountInWords(),
                null, "大写金额" + contract.amountInWords());
        var second = new ExtractionResult(UUID.randomUUID(), first.projectId(), first.sourceFileId(),
                first.parseResultId(), null, "d".repeat(64), "v1", first.factType(), first.groupKey(),
                first.valueText(), first.normalizedValue(), first.valueAmount(), "另一处大写金额" + contract.amountInWords(),
                2, 2, "pdf/page:2", NOW.minusSeconds(1));
        var extractions = mock(ExtractionResultRepository.class);
        when(extractions.findBySourceFileIdAndParseResultId(projectId, primary.fileId(), primary.parseResultId()))
                .thenReturn(List.of(amount, first, second));
        var service = new FindingSourceProjectionService(mock(ProjectContractQueryPort.class),
                mock(ContractCandidateRepository.class), mock(ProjectFileRepository.class), extractions);
        var check = new AuditCheckResult(UUID.randomUUID(), UUID.randomUUID(), F02RuleCodes.AMOUNT_WORDS_MATCH,
                null, CheckStatus.PASS, RiskLevel.GREEN, false, "849000.00", "捌拾肆万玖仟元整",
                "match", 1, NOW);

        assertThat(service.projectFrozen(input, check).reason()).isEqualTo("SOURCE_AMBIGUOUS");
    }

    @Test void ignoresExtractionFactsPublishedAfterTheReviewRunWasFrozen() {
        var contract = LocalContractFixtures.contracts().stream()
                .filter(value -> value.id().equals(LocalContractFixtures.FAIL_CONTRACT_ID)).findFirst().orElseThrow();
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        var run = new ContractReviewRun(runId, projectId, contract.id(), contract.versionNo(), null, null, 2,
                "{}", ContractSnapshotCanonicalizer.sha256("{}"), "profile", "rules", "models",
                "a".repeat(64), "actor", ContractReviewRunStatus.RUNNING, NOW, NOW,
                null, null, null, null, 1);
        var primary = material(1, DocumentRole.CONTRACT_BODY, "合同金额 849000.00，大写金额捌拾肆万玖仟元整");
        var input = new FrozenReviewInput(run, contract, List.of(primary));
        var amount = fact(projectId, primary, "AMOUNT_NUMERIC", null, "849000.00",
                contract.contractAmount().amount(), "合同金额 849000.00");
        var words = fact(projectId, primary, "AMOUNT_UPPERCASE", null, contract.amountInWords(),
                null, "大写金额" + contract.amountInWords());
        var late = new ExtractionResult(UUID.randomUUID(), words.projectId(), words.sourceFileId(),
                words.parseResultId(), UUID.randomUUID(), "d".repeat(64), "ai-v1", words.factType(),
                words.groupKey(), words.valueText(), words.normalizedValue(), words.valueAmount(),
                "后发布的大写金额" + contract.amountInWords(), 2, 2, "pdf/page:2", NOW.plusSeconds(1));
        var extractions = mock(ExtractionResultRepository.class);
        when(extractions.findBySourceFileIdAndParseResultId(projectId, primary.fileId(), primary.parseResultId()))
                .thenReturn(List.of(amount, words, late));
        var service = new FindingSourceProjectionService(mock(ProjectContractQueryPort.class),
                mock(ContractCandidateRepository.class), mock(ProjectFileRepository.class), extractions);
        var check = new AuditCheckResult(UUID.randomUUID(), UUID.randomUUID(), F02RuleCodes.AMOUNT_WORDS_MATCH,
                null, CheckStatus.PASS, RiskLevel.GREEN, false, "849000.00", contract.amountInWords(),
                "match", 1, NOW);

        var projected = service.projectFrozen(input, check);

        assertThat(projected.available()).isTrue();
        assertThat(projected.sources()).extracting(FindingSourceProjection.Source::excerpt)
                .contains(words.sourceText())
                .doesNotContain(late.sourceText());
    }

    private FrozenReviewMaterial material(int ordinal, DocumentRole role, String text) {
        return new FrozenReviewMaterial(UUID.randomUUID(), ordinal, UUID.randomUUID(), "b".repeat(64),
                UUID.randomUUID(), 1, ContractSnapshotCanonicalizer.sha256(text), text, role,
                UUID.randomUUID(), UUID.randomUUID(), null);
    }

    private ExtractionResult fact(UUID projectId, FrozenReviewMaterial material, String type,
            String groupKey, String value, java.math.BigDecimal amount, String excerpt) {
        return new ExtractionResult(UUID.randomUUID(), projectId, material.fileId(), material.parseResultId(),
                null, "c".repeat(64), "v1", type, groupKey, value, value, amount, excerpt,
                1, 1, "pdf/page:1", NOW);
    }
}
