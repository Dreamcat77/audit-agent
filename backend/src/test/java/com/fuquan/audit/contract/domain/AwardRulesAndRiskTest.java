package com.fuquan.audit.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;

import com.fuquan.audit.contract.domain.rule.AwardAmountMatchRule;
import com.fuquan.audit.contract.domain.rule.AwardSupplierMatchRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class AwardRulesAndRiskTest {

    private final AwardAmountMatchRule amountRule = new AwardAmountMatchRule();
    private final AwardSupplierMatchRule supplierRule = new AwardSupplierMatchRule();

    @Test
    void awardAmountMatchPasses() {
        assertThat(amountFinding("100", "100").checkStatus()).isEqualTo(CheckStatus.PASS);
    }

    @Test
    void awardAmountMismatchFailsRedAndBlocksSigning() {
        ContractReviewFinding finding = amountFinding("876000", "849000");

        assertThat(finding.checkStatus()).isEqualTo(CheckStatus.FAIL);
        assertThat(finding.riskLevel()).isEqualTo(RiskLevel.RED);
        assertThat(finding.blockSign()).isTrue();
    }

    @Test
    void missingAwardIsNotEvaluated() {
        Contract contract = F02RuleTestFixtures.contract(
                "100", "壹佰元整", List.of(), null, "Supplier", "CODE-1");

        ContractReviewFinding finding = amountRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.AWARD_AMOUNT_MATCH)).get(0);

        assertThat(finding.checkStatus()).isEqualTo(CheckStatus.NOT_EVALUATED);
        assertThat(finding.riskLevel()).isEqualTo(RiskLevel.YELLOW);
        assertThat(finding.blockSign()).isTrue();
    }

    @Test
    void supplierNameMatchPassesWhenCodesAreUnavailable() {
        Contract contract = F02RuleTestFixtures.contract(
                "100", "壹佰元整", List.of(),
                F02RuleTestFixtures.award("100", "Synthetic Supplier", null),
                " Synthetic  Supplier ", null);

        assertThat(supplierFinding(contract).checkStatus()).isEqualTo(CheckStatus.PASS);
    }

    @Test
    void supplierNameFallbackTreatsFullwidthAndAsciiParenthesesAsTheSameIdentity() {
        Contract contract = F02RuleTestFixtures.contract(
                "100", "One hundred", List.of(),
                F02RuleTestFixtures.award("100", "Synthetic Supplier(Test)", null),
                "Synthetic Supplier（Test）", null);

        assertThat(supplierFinding(contract).checkStatus()).isEqualTo(CheckStatus.PASS);
    }

    @Test
    void supplierMismatchFails() {
        Contract contract = F02RuleTestFixtures.contract(
                "100", "壹佰元整", List.of(),
                F02RuleTestFixtures.award("100", "Supplier B", null),
                "Supplier A", null);

        assertThat(supplierFinding(contract).checkStatus()).isEqualTo(CheckStatus.FAIL);
    }

    @Test
    void creditCodeTakesPriorityOverMatchingNames() {
        Contract contract = F02RuleTestFixtures.contract(
                "100", "壹佰元整", List.of(),
                F02RuleTestFixtures.award("100", "Same Supplier", "CODE-B"),
                "Same Supplier", "CODE-A");

        ContractReviewFinding finding = supplierFinding(contract);

        assertThat(finding.checkStatus()).isEqualTo(CheckStatus.FAIL);
        assertThat(finding.detail()).contains("credit code");
    }

    @Test
    void overallRiskUsesHighestLevelAndAnyBlockAggregates() {
        assertThat(RiskLevel.highest(List.of(RiskLevel.GREEN, RiskLevel.RED, RiskLevel.YELLOW)))
                .isEqualTo(RiskLevel.RED);
        assertThat(List.of(
                        ContractReviewFinding.pass(
                                F02RuleTestFixtures.config("A"), null, "a", "a", "pass"),
                        ContractReviewFinding.notEvaluated(
                                F02RuleTestFixtures.config("B"), null, "missing"))
                .stream().anyMatch(ContractReviewFinding::blockSign)).isTrue();
    }

    private ContractReviewFinding amountFinding(String contractAmount, String awardAmount) {
        Contract contract = F02RuleTestFixtures.contract(
                contractAmount, "零元整", List.of(),
                F02RuleTestFixtures.award(awardAmount, "Supplier", "CODE"),
                "Supplier", "CODE");
        return amountRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.AWARD_AMOUNT_MATCH)).get(0);
    }

    private ContractReviewFinding supplierFinding(Contract contract) {
        return supplierRule.evaluate(
                new ContractReviewContext(contract),
                F02RuleTestFixtures.config(F02RuleCodes.AWARD_SUPPLIER_MATCH)).get(0);
    }
}
