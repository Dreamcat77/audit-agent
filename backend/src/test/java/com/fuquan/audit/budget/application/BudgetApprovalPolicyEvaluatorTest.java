package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.budget.domain.BudgetApprovalRule;
import com.fuquan.audit.budget.domain.BudgetApprovalRuleGroup;
import com.fuquan.audit.budget.domain.BudgetApprovalStep;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BudgetApprovalPolicyEvaluatorTest {

    private final BudgetApprovalPolicyEvaluator evaluator = new BudgetApprovalPolicyEvaluator();

    @ParameterizedTest
    @MethodSource("boundaryCases")
    void evaluatesPmDemoBoundaries(
            String amount,
            List<String> expectedProcedure,
            String expectedAuthority) {
        BudgetApprovalEvaluation result = evaluator.evaluate(
                new BigDecimal(amount), rules(), validActualSteps());

        assertThat(result.requiredProcedureSteps()).containsExactlyElementsOf(expectedProcedure);
        assertThat(result.requiredAuthorityStep()).isEqualTo(expectedAuthority);
    }

    static Stream<Arguments> boundaryCases() {
        return Stream.of(
                Arguments.of("9999.99", List.of(), "FINANCE_REVIEW"),
                Arguments.of("10000.00", List.of("REQUEST_APPROVAL"), "FINANCE_REVIEW"),
                Arguments.of("49999.99", List.of("REQUEST_APPROVAL"), "FINANCE_REVIEW"),
                Arguments.of("50000.00", List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW"),
                        "FINANCE_REVIEW"),
                Arguments.of("199999.99", List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW"),
                        "FINANCE_REVIEW"),
                Arguments.of("200000.00", List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW"),
                        "MUNICIPAL_LEADER_REVIEW"),
                Arguments.of("1999999.99", List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW"),
                        "MUNICIPAL_LEADER_REVIEW"),
                Arguments.of("2000000.00", List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW"),
                        "PARTY_COMMITTEE_REVIEW"));
    }

    @Test
    void passesWhenRequiredProcedureOrderAndAuthorityArePresent() {
        BudgetApprovalEvaluation result = evaluator.evaluate(
                new BigDecimal("50000.00"), rules(),
                List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW", "FINANCE_REVIEW"));

        assertThat(result.passed()).isTrue();
        assertThat(result.missingProcedureSteps()).isEmpty();
        assertThat(result.procedureOrderValid()).isTrue();
        assertThat(result.authorityMatched()).isTrue();
        assertThat(result.resultSummary()).isEqualTo("审批程序符合当前规则集");
    }

    @Test
    void reportsMissingProcedureWithoutInventingAnOrderFailure() {
        BudgetApprovalEvaluation missing = evaluator.evaluate(
                new BigDecimal("50000.00"), rules(),
                List.of("GENERAL_BRANCH_REVIEW", "FINANCE_REVIEW"));
        assertThat(missing.passed()).isFalse();
        assertThat(missing.missingProcedureSteps()).containsExactly("REQUEST_APPROVAL");
        assertThat(missing.procedureOrderValid()).isTrue();
        assertThat(missing.authorityMatched()).isTrue();
    }

    @Test
    void reportsOrderInvalidWhenAllRequiredStepsAreReversed() {
        BudgetApprovalEvaluation reversed = evaluator.evaluate(
                new BigDecimal("50000.00"), rules(),
                List.of("GENERAL_BRANCH_REVIEW", "REQUEST_APPROVAL", "FINANCE_REVIEW"));
        assertThat(reversed.missingProcedureSteps()).isEmpty();
        assertThat(reversed.procedureOrderValid()).isFalse();
    }

    @Test
    void reportsAuthorityMismatchIndependently() {
        BudgetApprovalEvaluation authorityMissing = evaluator.evaluate(
                new BigDecimal("50000.00"), rules(),
                List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW"));
        assertThat(authorityMissing.authorityMatched()).isFalse();
        assertThat(authorityMissing.passed()).isFalse();
    }

    @Test
    void allowsExtraKnownStepsWhenRequiredProcedureOrderRemainsValid() {
        BudgetApprovalEvaluation result = evaluator.evaluate(
                new BigDecimal("50000.00"), rules(),
                List.of("REQUEST_APPROVAL", "MUNICIPAL_LEADER_REVIEW",
                        "GENERAL_BRANCH_REVIEW", "FINANCE_REVIEW"));

        assertThat(result.procedureOrderValid()).isTrue();
        assertThat(result.authorityMatched()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void rejectsDuplicateAndUnknownActualSteps() {
        assertThatThrownBy(() -> evaluator.evaluate(
                BigDecimal.TEN, rules(), List.of("FINANCE_REVIEW", "FINANCE_REVIEW")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> evaluator.evaluate(
                BigDecimal.TEN, rules(), List.of("UNKNOWN_STEP")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void failsClosedWhenAuthorityConfigurationDoesNotMatchExactlyOnce() {
        List<BudgetApprovalRule> missingAuthority = rules().stream()
                .filter(rule -> rule.ruleGroup() != BudgetApprovalRuleGroup.AUTHORITY_LEVEL)
                .toList();
        assertThatThrownBy(() -> evaluator.evaluate(
                new BigDecimal("10000"), missingAuthority, List.of("REQUEST_APPROVAL")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");

        List<BudgetApprovalRule> overlapping = new java.util.ArrayList<>(rules());
        overlapping.add(rule(
                BudgetApprovalRuleGroup.AUTHORITY_LEVEL, "RANGE", null, new BigDecimal("300000"),
                false, false, "PARTY_COMMITTEE_REVIEW", "党委会", 99));
        assertThatThrownBy(() -> evaluator.evaluate(
                new BigDecimal("10000"), overlapping, List.of("REQUEST_APPROVAL", "FINANCE_REVIEW")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsRuleGroupAndMatchModeMismatch() {
        assertThatThrownBy(() -> rule(
                BudgetApprovalRuleGroup.PROCEDURE_REQUIREMENT,
                "RANGE",
                BigDecimal.ZERO,
                BigDecimal.TEN,
                true,
                false,
                "REQUEST_APPROVAL",
                "请示报批",
                10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matchMode");
        assertThatThrownBy(() -> rule(
                BudgetApprovalRuleGroup.AUTHORITY_LEVEL,
                "CUMULATIVE_MIN",
                BigDecimal.ZERO,
                null,
                true,
                false,
                "FINANCE_REVIEW",
                "财处审批",
                10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matchMode");
    }

    @Test
    void supportsAnExclusiveCumulativeMinimumWithoutChangingTheDemoSeedBoundary() {
        BudgetApprovalRule exclusive = rule(
                BudgetApprovalRuleGroup.PROCEDURE_REQUIREMENT,
                "CUMULATIVE_MIN",
                new BigDecimal("10000.00"),
                null,
                false,
                false,
                "REQUEST_APPROVAL",
                "请示报批",
                10);
        BudgetApprovalRule finance = rule(
                BudgetApprovalRuleGroup.AUTHORITY_LEVEL,
                "RANGE",
                null,
                new BigDecimal("200000.00"),
                false,
                false,
                "FINANCE_REVIEW",
                "财处审批",
                10);

        BudgetApprovalEvaluation atBoundary = evaluator.evaluate(
                new BigDecimal("10000.00"), List.of(exclusive, finance), List.of("FINANCE_REVIEW"));
        BudgetApprovalEvaluation aboveBoundary = evaluator.evaluate(
                new BigDecimal("10000.01"), List.of(exclusive, finance),
                List.of("REQUEST_APPROVAL", "FINANCE_REVIEW"));

        assertThat(atBoundary.requiredProcedureSteps()).isEmpty();
        assertThat(aboveBoundary.requiredProcedureSteps()).containsExactly("REQUEST_APPROVAL");
        assertThat(evaluator.evaluate(
                new BigDecimal("10000.00"), rules(), validActualSteps()).requiredProcedureSteps())
                .containsExactly("REQUEST_APPROVAL");
    }

    private static List<String> validActualSteps() {
        return List.of(
                "REQUEST_APPROVAL",
                "GENERAL_BRANCH_REVIEW",
                "FINANCE_REVIEW",
                "MUNICIPAL_LEADER_REVIEW",
                "PARTY_COMMITTEE_REVIEW");
    }

    private static List<BudgetApprovalRule> rules() {
        return List.of(
                rule(BudgetApprovalRuleGroup.PROCEDURE_REQUIREMENT, "CUMULATIVE_MIN",
                        new BigDecimal("10000.00"), null, true, false,
                        "REQUEST_APPROVAL", "请示报批", 10),
                rule(BudgetApprovalRuleGroup.PROCEDURE_REQUIREMENT, "CUMULATIVE_MIN",
                        new BigDecimal("50000.00"), null, true, false,
                        "GENERAL_BRANCH_REVIEW", "总支会审议", 20),
                rule(BudgetApprovalRuleGroup.AUTHORITY_LEVEL, "RANGE",
                        null, new BigDecimal("200000.00"), false, false,
                        "FINANCE_REVIEW", "财处审批", 10),
                rule(BudgetApprovalRuleGroup.AUTHORITY_LEVEL, "RANGE",
                        new BigDecimal("200000.00"), new BigDecimal("2000000.00"), true, false,
                        "MUNICIPAL_LEADER_REVIEW", "市局领导", 20),
                rule(BudgetApprovalRuleGroup.AUTHORITY_LEVEL, "RANGE",
                        new BigDecimal("2000000.00"), null, true, false,
                        "PARTY_COMMITTEE_REVIEW", "党委会", 30));
    }

    private static BudgetApprovalRule rule(
            BudgetApprovalRuleGroup group,
            String matchMode,
            BigDecimal min,
            BigDecimal max,
            boolean minInclusive,
            boolean maxInclusive,
            String stepCode,
            String stepName,
            int order) {
        return new BudgetApprovalRule(
                UUID.randomUUID(), "PM_DEMO_20260828", group, matchMode,
                min, max, minInclusive, maxInclusive,
                new BudgetApprovalStep(stepCode, stepName), order, "ACTIVE", 0);
    }
}
