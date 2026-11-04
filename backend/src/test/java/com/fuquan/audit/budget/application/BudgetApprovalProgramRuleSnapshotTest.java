package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.budget.application.port.BudgetApprovalRuleRepository;
import com.fuquan.audit.budget.domain.BudgetApprovalRule;
import com.fuquan.audit.budget.domain.BudgetApprovalRuleGroup;
import com.fuquan.audit.budget.domain.BudgetApprovalStep;
import com.fuquan.audit.budget.domain.BudgetApprovalValidation;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetApprovalValidationRepository;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetReviewItemRepository;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetReviewTaskRepository;
import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BudgetApprovalProgramRuleSnapshotTest {

    private static final String OLD_RULE_SET = "RULE_SET_OLD";
    private static final String ACTOR_ID = "historical-rule-test-user";
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final UUID TASK_ID = UUID.fromString("61000000-0000-0000-0000-000000000001");
    private static final UUID ITEM_ID = UUID.fromString("61000000-0000-0000-0000-000000000002");
    private static final UUID VALIDATION_ID = UUID.fromString("61000000-0000-0000-0000-000000000003");

    private BudgetApprovalProgramService service;

    @BeforeEach
    void setUpRetiredHistoricalRuleSet() {
        RetiredRuleRepository ruleRepository = new RetiredRuleRepository(oldRules());
        InMemoryBudgetApprovalValidationRepository validationRepository =
                new InMemoryBudgetApprovalValidationRepository();
        validationRepository.create(oldValidation());

        InMemoryBudgetReviewTaskRepository taskRepository = new InMemoryBudgetReviewTaskRepository();
        taskRepository.save(new BudgetReviewTask(
                TASK_ID, "old-budget.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                100L, null, "REVIEWED", 1, null, "2026", "FINANCE", NOW, NOW));
        InMemoryBudgetReviewItemRepository itemRepository = new InMemoryBudgetReviewItemRepository();
        itemRepository.saveAll(List.of(new BudgetReviewItem(
                ITEM_ID, TASK_ID, 2, "办公设备采购", new BigDecimal("50000.00"),
                "办公设备采购", new BigDecimal("50000.00"), "MATCHED", "EXACT", 0,
                "PASSED", BigDecimal.ZERO, null, null, NOW)));

        service = new BudgetApprovalProgramService(
                ruleRepository,
                validationRepository,
                taskRepository,
                itemRepository,
                new BudgetApprovalPolicyEvaluator(),
                new SuccessfulReplayIdempotency(VALIDATION_ID),
                event -> { },
                () -> Optional.of(new CurrentUser(
                        ACTOR_ID, "historical.rules", "Historical Rules", Set.of("AUDITOR"))),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void historyResolvesStepNamesFromAnInactiveHistoricalRuleSet() {
        BudgetApprovalProgramView restored = service.history(TASK_ID, 1, 20).items().get(0);

        assertThat(restored.requiredProcedureSteps())
                .extracting(BudgetApprovalStep::displayName)
                .containsExactly("请示报批", "总支会审议");
        assertThat(restored.requiredAuthorityStep().displayName()).isEqualTo("财处审批");
        assertThat(restored.actualSteps())
                .extracting(BudgetApprovalStep::displayName)
                .containsExactly("请示报批", "总支会审议", "财处审批");
    }

    @Test
    void successfulIdempotentReplayResolvesStepNamesAfterRuleSetRetirement() {
        BudgetApprovalProgramView replayed = service.validate(new BudgetApprovalValidationCommand(
                TASK_ID,
                ITEM_ID,
                List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW", "FINANCE_REVIEW"),
                "retired-rule-replay-key",
                "request-1",
                "trace-1",
                "127.0.0.1"));

        assertThat(replayed.validationId()).isEqualTo(VALIDATION_ID);
        assertThat(replayed.requiredProcedureSteps())
                .extracting(BudgetApprovalStep::displayName)
                .containsExactly("请示报批", "总支会审议");
        assertThat(replayed.requiredAuthorityStep().displayName()).isEqualTo("财处审批");
        assertThat(replayed.actualSteps())
                .extracting(BudgetApprovalStep::displayName)
                .containsExactly("请示报批", "总支会审议", "财处审批");
    }

    private static BudgetApprovalValidation oldValidation() {
        return new BudgetApprovalValidation(
                VALIDATION_ID,
                OLD_RULE_SET,
                TASK_ID,
                ITEM_ID,
                2,
                new BigDecimal("50000.00"),
                "MANUAL_TEST",
                List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW"),
                "FINANCE_REVIEW",
                List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW", "FINANCE_REVIEW"),
                List.of(),
                true,
                true,
                true,
                "审批程序符合当前规则集",
                ACTOR_ID,
                NOW);
    }

    private static List<BudgetApprovalRule> oldRules() {
        return List.of(
                rule(BudgetApprovalRuleGroup.PROCEDURE_REQUIREMENT, "CUMULATIVE_MIN",
                        new BigDecimal("10000.00"), null, true, false,
                        "REQUEST_APPROVAL", "请示报批", 10),
                rule(BudgetApprovalRuleGroup.PROCEDURE_REQUIREMENT, "CUMULATIVE_MIN",
                        new BigDecimal("50000.00"), null, true, false,
                        "GENERAL_BRANCH_REVIEW", "总支会审议", 20),
                rule(BudgetApprovalRuleGroup.AUTHORITY_LEVEL, "RANGE",
                        null, new BigDecimal("200000.00"), false, false,
                        "FINANCE_REVIEW", "财处审批", 10));
    }

    private static BudgetApprovalRule rule(
            BudgetApprovalRuleGroup group,
            String matchMode,
            BigDecimal min,
            BigDecimal max,
            boolean minInclusive,
            boolean maxInclusive,
            String stepCode,
            String displayName,
            int order) {
        return new BudgetApprovalRule(
                UUID.randomUUID(), OLD_RULE_SET, group, matchMode,
                min, max, minInclusive, maxInclusive,
                new BudgetApprovalStep(stepCode, displayName), order, "INACTIVE", 1);
    }

    private record RetiredRuleRepository(List<BudgetApprovalRule> rules)
            implements BudgetApprovalRuleRepository {

        @Override
        public List<BudgetApprovalRule> findActiveByRuleSetCode(String ruleSetCode) {
            return List.of();
        }

        public List<BudgetApprovalRule> findByRuleSetCode(String ruleSetCode) {
            return OLD_RULE_SET.equals(ruleSetCode) ? rules : List.of();
        }
    }

    private record SuccessfulReplayIdempotency(UUID validationId) implements IdempotencyPort {

        @Override
        public IdempotencyClaim claim(IdempotencyRequest request) {
            return new IdempotencyClaim(false, new IdempotencyRecord(
                    UUID.randomUUID(), request.scope(), request.idempotencyKey(), request.actorId(),
                    request.resourceType(), request.resourceId(), request.requestFingerprint(),
                    IdempotencyStatus.SUCCEEDED, "BUDGET_APPROVAL_VALIDATION", validationId.toString(),
                    request.firstRequestId(), request.traceId(), request.createdAt(), request.createdAt(), 1));
        }

        @Override
        public IdempotencyRecord markSucceeded(
                IdempotencyRecord record,
                String resultResourceType,
                String resultResourceId,
                Instant completedAt) {
            throw new AssertionError("replay must not mark the existing record succeeded again");
        }

        @Override
        public IdempotencyRecord markFailed(IdempotencyRecord record, Instant completedAt) {
            throw new AssertionError("replay must not mark the existing record failed");
        }
    }
}
