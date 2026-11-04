package com.fuquan.audit.contract.application.clause;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import org.junit.jupiter.api.Test;

class BreachClauseAnalyzerTest {

    private static final UUID FILE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void calculatesContractRateAmountsButNeverInventsAnActualPenalty() {
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("67000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", """
                        乙方逾期交付，每延期一周，按合同金额1%支付违约金。乙方延期交付超过一个月，甲方有权单方解除合同。
                        软件或服务不符合合同标准，甲方有权单方解除，并支付合同金额20%违约金。
                        """)));

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).rate()).isEqualByComparingTo("1");
        assertThat(result.items().get(0).frequency()).isEqualTo("WEEK");
        assertThat(result.items().get(0).correspondingAmount()).isEqualByComparingTo("670.00");
        assertThat(result.items().get(0).actualPenaltyAmount()).isNull();
        assertThat(result.items().get(0).terminationCondition()).contains("超过一个月");
        assertThat(result.items().get(0).warning()).contains("累计违约金上限未明确");
        assertThat(result.items().get(1).rate()).isEqualByComparingTo("20");
        assertThat(result.items().get(1).correspondingAmount()).isEqualByComparingTo("13400.00");
        assertThat(result.items().get(1).scenario()).isEqualTo("软件/服务不符合约定");
    }

    @Test
    void keepsSemicolonRateAndCapInOneBreachContext() {
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("67000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx",
                        "逾期交付，每延期一周，按合同金额1%支付违约金；累计违约金最高不超过合同金额20%。")));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.scenario()).isEqualTo("逾期交付");
            assertThat(item.rate()).isEqualByComparingTo("1");
            assertThat(item.frequency()).isEqualTo("WEEK");
            assertThat(item.correspondingAmount()).isEqualByComparingTo("670.00");
            assertThat(item.capRate()).isEqualByComparingTo("20");
            assertThat(item.capAmount()).isEqualByComparingTo("13400.00");
            assertThat(item.warning()).isNull();
            assertThat(item.excerpt()).contains("1%", "20%");
        });
    }

    @Test
    void attachesOnlyCapContinuationWithoutMergingIndependentSemicolonScenario() {
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("67000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx",
                        "逾期交付，每延期一周，按合同金额1%支付违约金；累计违约金最高不超过合同金额20%；"
                                + "软件或服务不符合约定，按合同金额20%支付违约金。")));

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0)).satisfies(item -> {
            assertThat(item.scenario()).isEqualTo("逾期交付");
            assertThat(item.rate()).isEqualByComparingTo("1");
            assertThat(item.capRate()).isEqualByComparingTo("20");
            assertThat(item.excerpt()).contains("1%", "累计违约金", "20%");
        });
        assertThat(result.items().get(1)).satisfies(item -> {
            assertThat(item.scenario()).isEqualTo("软件/服务不符合约定");
            assertThat(item.rate()).isEqualByComparingTo("20");
            assertThat(item.capRate()).isNull();
            assertThat(item.excerpt()).doesNotContain("每延期一周", "累计违约金");
        });
    }

    @Test
    void supportsPerMilleDailyFrequencyAndOnlyUsesAnExplicitContractualCap() {
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("100000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx",
                        "逾期交付的，每天按合同金额5‰支付违约金，累计不超过合同金额10%。")));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.rate()).isEqualByComparingTo("5");
            assertThat(item.rateUnit()).isEqualTo("PER_MILLE");
            assertThat(item.frequency()).isEqualTo("DAY");
            assertThat(item.correspondingAmount()).isEqualByComparingTo("500.00");
            assertThat(item.capRate()).isEqualByComparingTo("10");
            assertThat(item.capAmount()).isEqualByComparingTo("10000.00");
            assertThat(item.warning()).isNull();
        });
    }

    @Test
    void recognizesSlashFrequencyAndPreservesAPerMilleCapUnit() {
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("100000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx",
                        "逾期交付的，按合同金额5‰/日支付违约金，累计不超过合同金额8‰。")));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.rate()).isEqualByComparingTo("5");
            assertThat(item.rateUnit()).isEqualTo("PER_MILLE");
            assertThat(item.frequency()).isEqualTo("DAY");
            assertThat(item.capRate()).isEqualByComparingTo("8");
            assertThat(item.capRateUnit()).isEqualTo("PER_MILLE");
            assertThat(item.capAmount()).isEqualByComparingTo("800.00");
        });
    }

    @Test
    void unsupportedExplicitFrequencyFailsClosedInsteadOfClaimingOnce() {
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("100000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx",
                        "逾期交付的，按合同金额1%/季度支付违约金，累计不超过合同金额20%。")));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.frequency()).isNull();
            assertThat(item.correspondingAmount()).isNull();
            assertThat(item.capAmount()).isNull();
            assertThat(item.status()).isEqualTo("REVIEW");
            assertThat(item.warning()).contains("频次");
        });
    }

    @Test
    void keepsARealSemicolonQualityScenarioWithItsRectificationTerminationAndRate() {
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("67000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx",
                        "软件/服务不符合约定；限期整改；达到解除条件；甲方可单方解除并按合同金额20%支付违约金。")));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.scenario()).isEqualTo("软件/服务不符合约定");
            assertThat(item.rate()).isEqualByComparingTo("20");
            assertThat(item.correspondingAmount()).isEqualByComparingTo("13400.00");
            assertThat(item.terminationCondition()).contains("达到解除条件", "单方解除");
            assertThat(item.excerpt()).contains("软件/服务不符合", "限期整改", "20%");
        });
    }

    @Test
    void comparesTheContractBreachCapAgainstSelectedTenderMaterial() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("67000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "逾期交付，每延期一周，按合同金额1%支付违约金；累计违约金最高不超过合同金额20%。"),
                new ContractClauseMaterial(tenderFileId, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "逾期交付累计违约金上限为合同金额10%。")));

        assertThat(result.items()).singleElement().satisfies(item ->
                assertThat(item.sourceFileId()).isEqualTo(FILE_ID));
        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo("REVIEW");
            assertThat(comparison.message()).contains("20%", "10%");
            assertThat(comparison.sources()).extracting(ClauseComparison.Source::fileId)
                    .containsExactly(FILE_ID, tenderFileId);
            assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::state)
                    .containsExactly("KNOWN", "KNOWN");
        });
    }

    @Test
    void treatsAMissingContractCapAsSemanticallyIncomparableWithTenderAndBidCaps() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        UUID bidFileId = UUID.fromString("10000000-0000-0000-0000-000000000012");
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("67000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "contract.docx", ProjectFileCategory.CONTRACT_BODY,
                        "逾期交付，每逾期一周，按合同金额1%支付违约金。"),
                new ContractClauseMaterial(tenderFileId, "tender.pdf", ProjectFileCategory.TENDER_DOCUMENT,
                        "逾期交付累计违约金上限为合同金额10%。"),
                new ContractClauseMaterial(bidFileId, "bid.pdf", ProjectFileCategory.BID_RESPONSE,
                        "逾期交付累计违约金上限为合同金额10%。")));

        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo("INSUFFICIENT");
            assertThat(comparison.message()).contains("未取得语义可比", "10%");
            assertThat(comparison.sources()).extracting(ClauseComparison.Source::fileId)
                    .containsExactly(FILE_ID, tenderFileId, bidFileId);
            assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::state)
                    .containsExactly("KNOWN", "KNOWN", "KNOWN");
        });
    }

    @Test
    void sameFrozenSideWithDifferentTypedValuesIsAConflictInsteadOfKeepingTheFirstClause() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000014");
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("67000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "每逾期一日，按合同金额1%支付违约金；累计违约金最高不超过合同金额20%。\n"
                                + "每逾期一日，按合同金额2%支付违约金；累计违约金最高不超过合同金额30%。"),
                new ContractClauseMaterial(tenderFileId, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "每逾期一日，按合同金额1%支付违约金；累计违约金最高不超过合同金额20%。")));

        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo("REVIEW");
            assertThat(comparison.sources()).filteredOn(source -> source.fileId().equals(FILE_ID)).hasSize(2);
            assertThat(comparison.typedSides()).first().satisfies(side -> {
                assertThat(side.state()).isEqualTo("CONFLICT");
                assertThat(side.rate()).isNull();
                assertThat(side.capRate()).isNull();
            });
            assertThat(comparison.typedSides()).element(1).satisfies(side -> {
                assertThat(side.state()).isEqualTo("KNOWN");
                assertThat(side.rate()).isEqualByComparingTo("1");
                assertThat(side.rateUnit()).isEqualTo("PERCENT");
                assertThat(side.frequency()).isEqualTo("DAY");
                assertThat(side.capRate()).isEqualByComparingTo("20");
            });
        });
    }

    @Test
    void keepsEachTerminationConditionWithItsOwnBreachScenario() {
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("67000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", """
                        每延期一周，按合同金额1%支付违约金；延期超过一个月，甲方可解除合同。
                        软件或服务不符合约定，限期整改；整改未通过时甲方可单方解除，并按合同金额20%支付违约金。
                        """)));

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).terminationCondition()).contains("延期超过一个月")
                .doesNotContain("整改未通过");
        assertThat(result.items().get(1).terminationCondition()).contains("整改未通过")
                .doesNotContain("延期超过一个月");
    }

    @Test
    void semicolonStartsANewExplicitScenarioBeforeItsRate() {
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("67000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx",
                        "软件或服务不符合约定；限期整改；达到解除条件时甲方可单方解除合同；"
                                + "逾期交付的，每周按合同金额1%支付违约金；"
                                + "累计违约金最高不超过合同金额20%。")));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.scenario()).isEqualTo("逾期交付");
            assertThat(item.rate()).isEqualByComparingTo("1");
            assertThat(item.capRate()).isEqualByComparingTo("20");
            assertThat(item.terminationCondition()).isNull();
            assertThat(item.excerpt()).doesNotContain("软件或服务不符合", "单方解除");
        });
    }

    @Test
    void typedBreachSideKeepsExplicitResponsiblePartyPeriodAndThresholdSeparately() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000015");
        String clause = "乙方每逾期一日，应按合同金额1%支付违约金；逾期超过十个自然日，甲方有权解除合同。";
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("67000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY, clause),
                new ContractClauseMaterial(tenderFileId, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        clause)));

        assertThat(result.comparisons()).singleElement().satisfies(comparison ->
                assertThat(comparison.typedSides()).allSatisfy(side -> {
                    assertThat(side.responsibleParty()).isEqualTo("乙方");
                    assertThat(side.period()).isEqualTo("十个自然日");
                    assertThat(side.terminationThreshold()).isEqualTo("逾期超过十个自然日");
                    assertThat(side.terminationCondition()).contains("甲方有权解除合同");
                }));
    }

    @Test
    void explicitBreachNotApplicableProducesTypedNotApplicableSides() {
        UUID tenderFileId = UUID.fromString("10000000-0000-0000-0000-000000000016");
        BreachClauseReview result = new BreachClauseAnalyzer().analyze(new BigDecimal("67000.00"), List.of(
                new ContractClauseMaterial(FILE_ID, "合同.docx", ProjectFileCategory.CONTRACT_BODY,
                        "违约责任：不适用。"),
                new ContractClauseMaterial(tenderFileId, "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT,
                        "违约责任：不适用。")));

        assertThat(result.comparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.status()).isEqualTo("INSUFFICIENT");
            assertThat(comparison.typedSides()).extracting(ClauseComparison.TypedSide::state)
                    .containsExactly("NOT_APPLICABLE", "NOT_APPLICABLE");
        });
    }
}
