package com.fuquan.audit.contract.application.reviewrun.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.clause.BreachClauseAnalyzer;
import com.fuquan.audit.contract.application.clause.BreachClauseReview;
import com.fuquan.audit.contract.application.reviewrun.FrozenEvidenceGrounder;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrozenBreachReviewStepExecutorTest {
    private static final UUID CONTRACT_FILE =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TENDER_FILE =
            UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Test
    void independentlyGroundsTheSeparateRemedyForBothTheFindingAndTypedComparison() {
        String rate = "乙方逾期交付，每周按合同金额2%支付违约金；累计上限为合同金额10%。";
        String remedy = "乙方延期交付超过4周且经书面催告仍未履行的，甲方可解除合同。";
        var contract = FrozenClauseExecutorTestSupport.material(1, CONTRACT_FILE,
                DocumentRole.CONTRACT_BODY, "😀合同\n" + rate + "\n" + remedy);
        var tender = FrozenClauseExecutorTestSupport.material(2, TENDER_FILE,
                DocumentRole.TENDER_DOCUMENT, rate + "\n" + remedy);
        var execution = FrozenClauseExecutorTestSupport.execution(ContractReviewStepType.BREACH,
                new BigDecimal("73000"), List.of(contract, tender));

        var result = executor().execute(execution.input(), execution.context());

        assertThat(result.evidence()).hasSize(6);
        assertThat(result.evidence()).extracting(e -> e.evidenceId()).doesNotHaveDuplicates();
        assertThat(result.findings()).allSatisfy(finding -> {
            if (finding.findingType().equals("BREACH_CLAUSE")) {
                assertThat(finding.summary()).contains("4周");
            }
            assertThat(result.evidence()).filteredOn(e -> e.findingId().equals(finding.findingId()))
                    .extracting(e -> e.boundedExcerpt()).contains(rate, remedy);
        });
        assertThat(result.evidence()).allSatisfy(evidence -> {
            var material = evidence.fileId().equals(CONTRACT_FILE) ? contract : tender;
            assertThat(evidence.parseResultId()).isEqualTo(material.parseResultId());
            assertThat(evidence.canonicalTextSha256()).isEqualTo(material.canonicalTextSha256());
            String text = material.canonicalText();
            assertThat(text.substring(text.offsetByCodePoints(0, evidence.startOffset()),
                    text.offsetByCodePoints(0, evidence.endOffset()))).isEqualTo(evidence.boundedExcerpt());
        });
        assertThat(result.typedComparisons()).singleElement().satisfies(comparison ->
                assertThat(comparison.values()).allSatisfy(value -> {
                    assertThat(value.terminationCondition()).isEqualTo(remedy);
                    assertThat(value.evidenceIds()).hasSize(2);
                }));
    }

    @Test
    void cannotPublishAnUngroundedSeparateRemedyFromTheAnalyzer() {
        String rate = "乙方逾期交付，每周按合同金额2%支付违约金。";
        var material = FrozenClauseExecutorTestSupport.material(1, CONTRACT_FILE,
                DocumentRole.CONTRACT_BODY, rate);
        var execution = FrozenClauseExecutorTestSupport.execution(ContractReviewStepType.BREACH,
                new BigDecimal("73000"), List.of(material));
        var item = new BreachClauseReview.Item("逾期交付", new BigDecimal("2"), "PERCENT", "WEEK",
                "CONTRACT_AMOUNT", new BigDecimal("1460.00"), null, null, null, null,
                "乙方延期超过4周，甲方可解除合同。", "REVIEW", null, CONTRACT_FILE, rate);
        assertThatThrownBy(() -> mapper().mapBreach(new BreachClauseReview(List.of(item), false),
                execution.input(), execution.context())).isInstanceOf(BusinessException.class);
    }

    @Test
    void matchingCapsWithAnUnresolvedAppliedFrequencyCannotPublishTypedMatch() {
        String text = "乙方逾期交付，每延期满两周按合同金额3%支付违约金；累计上限为合同金额15%。";
        var execution = FrozenClauseExecutorTestSupport.execution(ContractReviewStepType.BREACH,
                new BigDecimal("10000"), List.of(
                        FrozenClauseExecutorTestSupport.material(1, CONTRACT_FILE, DocumentRole.CONTRACT_BODY, text),
                        FrozenClauseExecutorTestSupport.material(2, TENDER_FILE, DocumentRole.TENDER_DOCUMENT, text)));
        var result = executor().execute(execution.input(), execution.context());
        assertThat(result.findings()).extracting(finding -> finding.statusCode())
                .containsExactly("REVIEW", "INSUFFICIENT");
        assertThat(result.findings().get(0).recommendation()).contains("频次");
        assertThat(result.typedComparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.result()).isEqualTo("NOT_EVALUATED");
            assertThat(comparison.values()).allSatisfy(side -> {
                assertThat(side.state()).isEqualTo("KNOWN");
                assertThat(side.rate()).isEqualByComparingTo("3");
                assertThat(side.capRate()).isEqualByComparingTo("15");
                assertThat(side.frequency()).isNull();
                assertThat(side.evidenceIds()).isNotEmpty();
            });
        });
        assertThat(result.evidence()).hasSize(3).allSatisfy(evidence -> {
            assertThat(evidence.locatorAvailable()).isTrue();
            assertThat(evidence.boundedExcerpt()).isEqualTo(text);
        });
    }

    @Test
    void unresolvedSummaryAndDetailPublishNotEvaluatedWithoutLosingEitherSource() {
        String partial = "逾期交付，每延期满两周按3%支付违约金；累计上限15%。";
        String detail = "逾期交付，每周按合同金额3%支付违约金；累计上限为合同金额15%。";
        var execution = FrozenClauseExecutorTestSupport.execution(ContractReviewStepType.BREACH,
                new BigDecimal("10000"), List.of(
                        FrozenClauseExecutorTestSupport.material(1, CONTRACT_FILE, DocumentRole.CONTRACT_BODY, detail),
                        FrozenClauseExecutorTestSupport.material(2, TENDER_FILE, DocumentRole.TENDER_DOCUMENT,
                                partial + "\n" + detail)));
        var result = executor().execute(execution.input(), execution.context());
        assertThat(result.findings()).extracting(finding -> finding.statusCode())
                .containsExactly("NORMAL", "INSUFFICIENT");
        assertThat(result.typedComparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.result()).isEqualTo("NOT_EVALUATED");
            var tenderSide = comparison.values().stream().filter(side -> side.sourceFileId().equals(TENDER_FILE))
                    .findFirst().orElseThrow();
            assertThat(tenderSide.state()).isEqualTo("KNOWN");
            assertThat(tenderSide.rate()).isEqualByComparingTo("3");
            assertThat(tenderSide.capRate()).isEqualByComparingTo("15");
            assertThat(tenderSide.frequency()).isNull();
            assertThat(tenderSide.evidenceIds()).hasSize(2);
        });
        assertThat(result.evidence()).filteredOn(evidence -> evidence.fileId().equals(TENDER_FILE))
                .extracting(evidence -> evidence.boundedExcerpt()).containsExactly(partial, detail);
    }

    @Test
    void mapsFrozenContractAuthorityStatusesDecimalTextAndExactComparisonEvidence() {
        String contractText = "逾期交付，每逾期一日，按合同金额1.250%支付违约金；"
                + "累计违约金最高不超过合同金额8.50%。";
        String tenderText = "逾期交付累计违约金上限为合同金额10%。";
        var contractMaterial = FrozenClauseExecutorTestSupport.material(
                1, CONTRACT_FILE, DocumentRole.CONTRACT_BODY, contractText);
        var tenderMaterial = FrozenClauseExecutorTestSupport.material(
                2, TENDER_FILE, DocumentRole.TENDER_DOCUMENT, tenderText);
        var execution = FrozenClauseExecutorTestSupport.execution(
                ContractReviewStepType.BREACH, new BigDecimal("100000.00"),
                List.of(contractMaterial, tenderMaterial));

        var result = executor().execute(execution.input(), execution.context());

        assertThat(result.outputResourceType()).isEqualTo("REVIEW_FINDING_SET");
        assertThat(result.outputResourceId()).isEqualTo(FrozenClauseExecutorTestSupport.ATTEMPT_ID);
        assertThat(result.providerRequestId()).isNull();
        assertThat(result.provider()).isNull();
        assertThat(result.model()).isNull();
        assertThat(result.findings()).hasSize(2);
        assertThat(result.findings().get(0).statusCode()).isEqualTo("NORMAL");
        assertThat(result.findings().get(0).riskLevel()).isNull();
        assertThat(result.findings().get(0).summary())
                .contains("逾期交付", "1.250%", "每日", "1250.00", "8.50%", "8500.00")
                .doesNotContain("scenario=", "rate=", "frequency=");
        assertThat(result.findings().get(1).statusCode()).isEqualTo("REVIEW");
        assertThat(result.findings().get(1).riskLevel()).isNull();
        assertThat(result.evidence()).hasSize(3);
        assertThat(result.evidence().get(0).fileId()).isEqualTo(CONTRACT_FILE);
        assertThat(result.evidence().get(0).parseResultId())
                .isEqualTo(contractMaterial.parseResultId());
        assertThat(result.evidence().get(0).canonicalTextSha256())
                .isEqualTo(contractMaterial.canonicalTextSha256());
        assertThat(result.evidence().get(0).evidenceRole()).isEqualTo("PROBLEM");
        assertThat(result.evidence().get(0).locatorAvailable()).isTrue();
        assertThat(result.evidence().subList(1, 3))
                .extracting(value -> value.fileId())
                .containsExactly(CONTRACT_FILE, TENDER_FILE);
        assertThat(result.evidence().subList(1, 3))
                .extracting(value -> value.evidenceRole())
                .containsOnly("COMPARISON");
        assertThat(result.evidence().get(2).parseResultId())
                .isEqualTo(tenderMaterial.parseResultId());
        assertThat(result.typedComparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.result()).isEqualTo("DIFFERENT");
            assertThat(comparison.schemaVersion()).isEqualTo(2);
            assertThat(comparison.values()).extracting(value -> value.state())
                    .containsExactly("KNOWN", "KNOWN");
            assertThat(comparison.values()).allSatisfy(value ->
                    assertThat(value.evidenceIds()).isNotEmpty());
        });
    }

    @Test
    void repeatedDeterministicExcerptFailsClosedInsteadOfChoosingFirstMatch() {
        String repeated = "每逾期一日，按合同金额1%支付违约金。"
                + "每逾期一日，按合同金额1%支付违约金。";
        var execution = FrozenClauseExecutorTestSupport.execution(
                ContractReviewStepType.BREACH, new BigDecimal("100.00"), List.of(
                        FrozenClauseExecutorTestSupport.material(
                                1, CONTRACT_FILE, DocumentRole.CONTRACT_BODY, repeated)));

        assertThatThrownBy(() -> executor().execute(execution.input(), execution.context()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
    }

    @Test
    void semanticallyIncomparableRateAndCapRemainNotEvaluated() {
        var execution = FrozenClauseExecutorTestSupport.execution(
                ContractReviewStepType.BREACH, new BigDecimal("100000.00"), List.of(
                        FrozenClauseExecutorTestSupport.material(1, CONTRACT_FILE,
                                DocumentRole.CONTRACT_BODY,
                                "逾期交付，每逾期一日，按合同金额1%支付违约金。"),
                        FrozenClauseExecutorTestSupport.material(2, TENDER_FILE,
                                DocumentRole.TENDER_DOCUMENT,
                                "逾期交付累计违约金上限为合同金额10%。")));

        var result = executor().execute(execution.input(), execution.context());

        assertThat(result.typedComparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.result()).isEqualTo("NOT_EVALUATED");
            assertThat(comparison.values()).extracting(value -> value.state())
                    .containsExactly("KNOWN", "KNOWN");
            assertThat(comparison.values().get(0).evidenceIds()).isNotEmpty();
            assertThat(comparison.values().get(1).evidenceIds()).isNotEmpty();
        });
    }

    @Test
    void mapperRejectsAnAnalyzerExcerptThatDoesNotExistInItsDeclaredFrozenSource() {
        var material = FrozenClauseExecutorTestSupport.material(
                1, CONTRACT_FILE, DocumentRole.CONTRACT_BODY, "合同正文没有该摘录");
        var execution = FrozenClauseExecutorTestSupport.execution(
                ContractReviewStepType.BREACH, new BigDecimal("100.00"), List.of(material));
        var item = new BreachClauseReview.Item("逾期交付", new BigDecimal("1.00"),
                "PERCENT", "DAY", "CONTRACT_AMOUNT", new BigDecimal("1.00"),
                null, null, null, null, null, "NORMAL", null, CONTRACT_FILE, "不存在摘录");

        assertThatThrownBy(() -> mapper().mapBreach(
                new BreachClauseReview(List.of(item), false), execution.input(), execution.context()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
    }

    @Test
    void mapsExplicitNotApplicableWithoutInventingTypedValueEvidence() {
        var execution = FrozenClauseExecutorTestSupport.execution(
                ContractReviewStepType.BREACH, new BigDecimal("100.00"), List.of(
                        FrozenClauseExecutorTestSupport.material(1, CONTRACT_FILE,
                                DocumentRole.CONTRACT_BODY, "违约责任：不适用。"),
                        FrozenClauseExecutorTestSupport.material(2, TENDER_FILE,
                                DocumentRole.TENDER_DOCUMENT, "违约责任：不适用。")));

        var result = executor().execute(execution.input(), execution.context());

        assertThat(result.typedComparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.result()).isEqualTo("NOT_EVALUATED");
            assertThat(comparison.values()).extracting(value -> value.state())
                    .containsExactly("NOT_APPLICABLE", "NOT_APPLICABLE");
            assertThat(comparison.values()).allSatisfy(value ->
                    assertThat(value.evidenceIds()).isEmpty());
        });
        assertThat(result.evidence()).hasSize(2).allSatisfy(value ->
                assertThat(value.locatorAvailable()).isTrue());
    }

    @Test
    void unsupportedFrozenAnalyzerPolicyFailsClosedBeforeRunningCurrentEngine() {
        var execution = FrozenClauseExecutorTestSupport.execution(
                ContractReviewStepType.BREACH, new BigDecimal("100.00"), List.of(
                        FrozenClauseExecutorTestSupport.material(
                                1, CONTRACT_FILE, DocumentRole.CONTRACT_BODY, "frozen clause")),
                "breach-analyzer-future-v2");

        assertThatThrownBy(() -> executor().execute(execution.input(), execution.context()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
    }

    private static FrozenBreachReviewStepExecutor executor() {
        return new FrozenBreachReviewStepExecutor(
                new BreachClauseAnalyzer(), new FrozenClauseMaterialAdapter(), mapper());
    }

    private static FrozenClauseResultMapper mapper() {
        return new FrozenClauseResultMapper(
                new FrozenEvidenceGrounder(), FrozenClauseExecutorTestSupport.CLOCK);
    }
}
