package com.fuquan.audit.contract.application.clause;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClauseReviewFollowupRegressionTest {
    @Test
    void separateDeliveryAndPaymentRatesKeepTheirOwnEventPartyAndFrequency() {
        var result = breach("乙方延期交付，每周按合同金额2%支付违约金；"
                + "甲方延期付款，每日按合同金额1%支付违约金。");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0)).satisfies(item -> {
            assertThat(item.scenario()).isEqualTo("逾期交付");
            assertThat(item.frequency()).isEqualTo("WEEK");
            assertThat(item.rate()).isEqualByComparingTo("2");
            assertThat(item.excerpt()).doesNotContain("甲方", "付款", "1%");
        });
        assertThat(result.items().get(1)).satisfies(item -> {
            assertThat(item.scenario()).isEqualTo("逾期付款");
            assertThat(item.frequency()).isEqualTo("DAY");
            assertThat(item.rate()).isEqualByComparingTo("1");
            assertThat(item.excerpt()).doesNotContain("乙方", "交付", "2%");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"甲方延期付款超过4周，乙方可解除合同。",
            "甲方延期交付超过4周，乙方可解除合同。", "延期超过4周，甲方可解除合同。"})
    void aSeparateRemedyRequiresProvenEventAndBreachingPartyInsteadOfUnknownWildcards(String remedy) {
        var result = breach("乙方延期交付，每周按合同金额2%支付违约金。" + remedy);
        assertThat(result.items()).singleElement().satisfies(item ->
                assertThat(item.terminationCondition()).isNull());
    }

    @Test
    void aSeparateRemedyWithTheSameExplicitEventAndBreachingPartyRemainsLinked() {
        String remedy = "乙方延期交付超过4周，甲方可解除合同。";
        assertThat(breach("乙方延期交付，每周按合同金额2%支付违约金。" + remedy).items())
                .singleElement().satisfies(item -> assertThat(item.terminationCondition()).isEqualTo(remedy));
    }

    @ParameterizedTest
    @ValueSource(strings = {"满", "达到"})
    void thresholdComparisonRetainsStrictAndInclusiveOperatorsInBothAnalyzers(String inclusive) {
        var materials = List.of(contract("乙方延期交付，每周按合同金额2%支付违约金；"
                + "乙方延期超过4周，甲方可解除合同。"),
                tender("乙方延期交付，每周按合同金额2%支付违约金；"
                        + "乙方延期" + inclusive + "4周，甲方可解除合同。"));
        assertThat(new BreachClauseAnalyzer().analyze(new BigDecimal("30000"), materials).comparisons())
                .singleElement().satisfies(value -> assertThat(value.status()).isEqualTo("REVIEW"));
        assertThat(new ContractTermClauseAnalyzer().analyze(materials).comparisons())
                .singleElement().satisfies(value -> assertThat(value.status()).isEqualTo("REVIEW"));
    }

    @Test
    void sameOperatorAndNumericOrChineseDurationStillMatchWithExplicitParties() {
        var materials = List.of(contract("乙方延期超过4周，甲方可解除合同。"),
                tender("乙方延期超过四周，甲方可解除合同。"));
        assertThat(new ContractTermClauseAnalyzer().analyze(materials).comparisons())
                .singleElement().satisfies(value -> assertThat(value.status()).isEqualTo("NORMAL"));
    }

    @Test
    void reversingTheBreachingAndEntitledPartiesCannotProduceATermMatch() {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(
                contract("乙方延期超过4周，甲方可解除合同。"),
                tender("甲方延期超过4周，乙方可解除合同。")));
        assertThat(result.comparisons()).singleElement().satisfies(value -> {
            assertThat(value.status()).isEqualTo("REVIEW");
            assertThat(value.typedSides()).extracting(ClauseComparison.TypedSide::responsibleParty)
                    .containsExactly("乙方", "甲方");
        });
    }

    @Test
    void differentTerminationRightHoldersCannotProduceATermMatch() {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(
                contract("乙方延期超过4周，甲方可解除合同。"),
                tender("乙方延期超过4周，乙方可解除合同。")));
        assertThat(result.comparisons()).singleElement().satisfies(value ->
                assertThat(value.status()).isEqualTo("REVIEW"));
    }

    @Test
    void unknownTerminationPartiesAreNotEvidenceOfAMatch() {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(
                contract("延期超过4周，可以解除合同。"), tender("延期超过4周，可以解除合同。")));
        assertThat(result.comparisons()).singleElement().satisfies(value ->
                assertThat(value.status()).isEqualTo("INSUFFICIENT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"甲方验收应于2029年8月17日前完成。", "独立验收于2029年8月17日前完成。"})
    void standaloneAcceptanceDoesNotBecomeADeliveryDeadline(String text) {
        assertThat(new ContractTermClauseAnalyzer().analyze(List.of(contract(text))).dates())
                .noneMatch(date -> date.type().equals("DELIVERY_DEADLINE"));
    }

    @Test
    void independentRatesForTheSameEventAndPartyStillOwnTheirOwnFrequency() {
        var result = breach("乙方延期交付，每周按合同金额2%支付违约金；"
                + "乙方延期交付，每日另按合同金额1%支付违约金。");
        assertThat(result.items()).extracting(BreachClauseReview.Item::frequency).containsExactly("WEEK", "DAY");
        assertThat(result.items()).extracting(BreachClauseReview.Item::rate)
                .containsExactly(new BigDecimal("2"), new BigDecimal("1"));
    }

    @Test
    void aBareDelayDoesNotProveDeliveryOrMatchAnExplicitDeliveryObligation() {
        var result = new BreachClauseAnalyzer().analyze(new BigDecimal("30000"), List.of(
                contract("乙方延期，每周按合同金额2%支付违约金。"),
                tender("乙方延期交付，每周按合同金额2%支付违约金。")));
        assertThat(result.items()).singleElement().satisfies(item ->
                assertThat(item.scenario()).isEqualTo("延期事项未明确"));
        assertThat(result.comparisons()).allSatisfy(value ->
                assertThat(value.status()).isEqualTo("INSUFFICIENT"));
    }

    @Test
    void aKnownBreachingPartyDoesNotFillAnOmittedTerminationRightHolder() {
        var result = new ContractTermClauseAnalyzer().analyze(List.of(
                contract("乙方延期超过4周，可解除合同。"), tender("乙方延期超过4周，可解除合同。")));
        assertThat(result.comparisons()).singleElement().satisfies(value ->
                assertThat(value.status()).isEqualTo("INSUFFICIENT"));
    }

    private static BreachClauseReview breach(String text) {
        return new BreachClauseAnalyzer().analyze(new BigDecimal("30000"), List.of(contract(text)));
    }

    private static ContractClauseMaterial contract(String text) {
        return new ContractClauseMaterial(new UUID(0, 801), "合同.docx", ProjectFileCategory.CONTRACT_BODY, text);
    }

    private static ContractClauseMaterial tender(String text) {
        return new ContractClauseMaterial(new UUID(0, 802), "采购文件.docx", ProjectFileCategory.TENDER_DOCUMENT, text);
    }
}
