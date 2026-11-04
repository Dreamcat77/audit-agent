package com.fuquan.audit.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;

import com.fuquan.audit.contract.domain.rule.AmountWordsMatchRule;
import com.fuquan.audit.contract.domain.rule.LineItemArithmeticRule;
import com.fuquan.audit.contract.domain.rule.LineSumMatchRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class AmountAndLineRulesTest {

    private final AmountWordsMatchRule wordsRule =
            new AmountWordsMatchRule(new ChineseRmbUppercaseParser());
    private final LineItemArithmeticRule arithmeticRule = new LineItemArithmeticRule();
    private final LineSumMatchRule sumRule = new LineSumMatchRule();

    @Test
    void wordsMatchUsesNumericEqualityAcrossScale() {
        Contract contract = F02RuleTestFixtures.contract(
                "100.00", "壹佰元整", List.of(), null, "Supplier", null);

        assertPass(wordsRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.AMOUNT_WORDS_MATCH)).get(0));
    }

    @Test
    void wordsMismatchIsRedBlockingFailure() {
        Contract contract = F02RuleTestFixtures.contract(
                "100.00", "玖拾玖元整", List.of(), null, "Supplier", null);

        assertFail(wordsRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.AMOUNT_WORDS_MATCH)).get(0));
    }

    @Test
    void illegalWordsAreNotEvaluatedAndNeverPass() {
        Contract contract = F02RuleTestFixtures.contract(
                "100.00", "not-rmb", List.of(), null, "Supplier", null);

        ContractReviewFinding finding = wordsRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.AMOUNT_WORDS_MATCH)).get(0);

        assertThat(finding.checkStatus()).isEqualTo(CheckStatus.NOT_EVALUATED);
        assertThat(finding.riskLevel()).isEqualTo(RiskLevel.YELLOW);
        assertThat(finding.blockSign()).isTrue();
    }

    @Test
    void lineQuantityTimesUnitPriceCanPass() {
        ContractLineItem item = F02RuleTestFixtures.item("L1", "2.0000", "50.0000", "100.00");
        Contract contract = F02RuleTestFixtures.contract(
                "100", "壹佰元整", List.of(item), null, "Supplier", null);

        assertPass(arithmeticRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.LINE_ITEM_ARITHMETIC)).get(0));
    }

    @Test
    void incorrectDeclaredLineAmountFails() {
        ContractLineItem item = F02RuleTestFixtures.item("L1", "2", "50", "99.00");
        Contract contract = F02RuleTestFixtures.contract(
                "99", "玖拾玖元整", List.of(item), null, "Supplier", null);

        assertFail(arithmeticRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.LINE_ITEM_ARITHMETIC)).get(0));
    }

    @Test
    void declaredLineSumCanMatchContractAmount() {
        Contract contract = F02RuleTestFixtures.contract(
                "100", "壹佰元整",
                List.of(
                        F02RuleTestFixtures.item("L1", "1", "40", "40"),
                        F02RuleTestFixtures.item("L2", "1", "60", "60")),
                null, "Supplier", null);

        assertPass(sumRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.LINE_SUM_MATCH)).get(0));
    }

    @Test
    void amountOnlyLinesAreIndividuallyNotEvaluatedForArithmeticButStillParticipateInDeclaredSum() {
        Contract contract = F02RuleTestFixtures.contract(
                "67000", "陆万柒仟元整",
                List.of(
                        amountOnly("SERVICE-1", "28000"),
                        amountOnly("SERVICE-2", "16000"),
                        amountOnly("SERVICE-3", "9000"),
                        amountOnly("SERVICE-4", "14000")),
                null, "Supplier", null);

        List<ContractReviewFinding> arithmetic = arithmeticRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.LINE_ITEM_ARITHMETIC));
        ContractReviewFinding sum = sumRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.LINE_SUM_MATCH)).get(0);

        assertThat(arithmetic).hasSize(4).allSatisfy(finding -> {
            assertThat(finding.checkStatus()).isEqualTo(CheckStatus.NOT_EVALUATED);
            assertThat(finding.detail()).isEqualTo("Line item has declared amount only; quantity and unit price are unavailable");
        });
        assertPass(sum);
        assertThat(sum.actualValue()).isEqualTo("67000");
    }

    @Test
    void pm44DemoLineArithmeticAndDeclaredSumRemainPassingBusinessFacts() {
        Contract contract = F02RuleTestFixtures.contract(
                "67000", "陆万柒仟元整",
                List.of(
                        F02RuleTestFixtures.item("SOFT-01", "1", "40000", "40000"),
                        F02RuleTestFixtures.item("SERV-01", "1", "17000", "17000"),
                        F02RuleTestFixtures.item("TRAIN-01", "1", "10000", "10000")),
                null, "Supplier", null);

        List<ContractReviewFinding> lineFindings = arithmeticRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.LINE_ITEM_ARITHMETIC));
        ContractReviewFinding sumFinding = sumRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.LINE_SUM_MATCH)).get(0);

        assertThat(lineFindings).hasSize(3).allSatisfy(this::assertPass);
        assertPass(sumFinding);
        assertThat(sumFinding.actualValue()).isEqualTo("67000");
        assertThat(sumFinding.expectedValue()).isEqualTo("67000");
    }

    @Test
    void declaredLineSumMismatchFails() {
        Contract contract = F02RuleTestFixtures.contract(
                "101", "壹佰零壹元整",
                List.of(F02RuleTestFixtures.item("L1", "1", "100", "100")),
                null, "Supplier", null);

        assertFail(sumRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.LINE_SUM_MATCH)).get(0));
    }

    @Test
    void missingLineBasisIsNotEvaluated() {
        Contract contract = F02RuleTestFixtures.contract(
                "100", "壹佰元整", List.of(), null, "Supplier", null);

        assertThat(sumRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.LINE_SUM_MATCH)).get(0).checkStatus())
                .isEqualTo(CheckStatus.NOT_EVALUATED);
    }

    private void assertPass(ContractReviewFinding finding) {
        assertThat(finding.checkStatus()).isEqualTo(CheckStatus.PASS);
        assertThat(finding.riskLevel()).isEqualTo(RiskLevel.GREEN);
        assertThat(finding.blockSign()).isFalse();
    }

    private void assertFail(ContractReviewFinding finding) {
        assertThat(finding.checkStatus()).isEqualTo(CheckStatus.FAIL);
        assertThat(finding.riskLevel()).isEqualTo(RiskLevel.RED);
        assertThat(finding.blockSign()).isTrue();
    }

    private static ContractLineItem amountOnly(String key, String amount) {
        return new ContractLineItem(java.util.UUID.randomUUID(), F02RuleTestFixtures.CONTRACT_ID, key,
                "服务 " + key, ContractLineItemBasis.AMOUNT_ONLY, null, null,
                Money.of(new java.math.BigDecimal(amount)), 1);
    }
}
