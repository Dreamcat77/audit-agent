package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.BudgetApprovalRuleRepository;
import com.fuquan.audit.budget.application.port.BudgetApprovalValidationRepository;
import com.fuquan.audit.budget.application.port.BudgetReviewItemRepository;
import com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository;
import com.fuquan.audit.budget.domain.BudgetApprovalRule;
import com.fuquan.audit.budget.domain.BudgetApprovalStep;
import com.fuquan.audit.budget.domain.BudgetApprovalValidation;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetApprovalProgramService {

    public static final String RULE_SET_CODE = "PM_DEMO_20260828";
    private static final String IDEMPOTENCY_SCOPE = "BUDGET_APPROVAL_PROGRAM_VALIDATE";
    private static final String RESULT_RESOURCE_TYPE = "BUDGET_APPROVAL_VALIDATION";

    private final BudgetApprovalRuleRepository ruleRepository;
    private final BudgetApprovalValidationRepository validationRepository;
    private final BudgetReviewTaskRepository taskRepository;
    private final BudgetReviewItemRepository itemRepository;
    private final BudgetApprovalPolicyEvaluator evaluator;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final AuthContext authContext;
    private final Clock clock;

    public BudgetApprovalProgramService(
            BudgetApprovalRuleRepository ruleRepository,
            BudgetApprovalValidationRepository validationRepository,
            BudgetReviewTaskRepository taskRepository,
            BudgetReviewItemRepository itemRepository,
            BudgetApprovalPolicyEvaluator evaluator,
            IdempotencyPort idempotency,
            OperationAuditPort audit,
            AuthContext authContext,
            Clock clock) {
        this.ruleRepository = ruleRepository;
        this.validationRepository = validationRepository;
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.evaluator = evaluator;
        this.idempotency = idempotency;
        this.audit = audit;
        this.authContext = authContext;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BudgetApprovalRule> activeRules() {
        return requireActiveRules(RULE_SET_CODE);
    }

    @Transactional
    public BudgetApprovalProgramView validate(BudgetApprovalValidationCommand command) {
        CurrentUser actor = currentUser();
        requireIdempotencyKey(command.idempotencyKey());
        BudgetReviewTask task = taskRepository.findById(command.taskId())
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "budget review task not found: " + command.taskId()));
        if (!"REVIEWED".equals(task.status())) {
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT, "budget review task must be REVIEWED before F04 validation");
        }
        BudgetReviewItem item = itemRepository.findById(command.itemId())
                .filter(candidate -> command.taskId().equals(candidate.taskId()))
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "budget review item not found for task"));
        BigDecimal amount = item.declaredAmount();
        if (amount == null || amount.signum() < 0) {
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "budget review item declared amount is unavailable");
        }

        Instant now = clock.instant();
        UUID validationId = UUID.randomUUID();
        List<String> actualSteps = command.actualSteps() == null ? List.of() : List.copyOf(command.actualSteps());
        String fingerprint = fingerprint(
                IDEMPOTENCY_SCOPE,
                actor.userId(),
                command.taskId().toString(),
                command.itemId().toString(),
                amount.stripTrailingZeros().toPlainString(),
                RULE_SET_CODE,
                BudgetApprovalStepSnapshotCodec.encode(actualSteps));
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(
                IDEMPOTENCY_SCOPE,
                command.idempotencyKey(),
                actor.userId(),
                "BUDGET_REVIEW_ITEM",
                item.id().toString(),
                fingerprint,
                RESULT_RESOURCE_TYPE,
                validationId.toString(),
                safe(command.requestId()),
                safe(command.traceId()),
                now));
        if (!claim.acquired()) {
            return replay(claim.record(), fingerprint, actor.userId(), task.id(), item.id());
        }

        try {
            List<BudgetApprovalRule> rules = requireActiveRules(RULE_SET_CODE);
            BudgetApprovalEvaluation evaluation;
            try {
                evaluation = evaluator.evaluate(amount, rules, actualSteps);
            } catch (IllegalArgumentException invalidInput) {
                throw new BusinessException(
                        BusinessErrorCode.INVALID_ARGUMENT, invalidInput.getMessage(), invalidInput);
            }
            BudgetApprovalValidation validation = new BudgetApprovalValidation(
                    validationId,
                    RULE_SET_CODE,
                    task.id(),
                    item.id(),
                    item.rowNumber(),
                    amount,
                    "MANUAL_TEST",
                    evaluation.requiredProcedureSteps(),
                    evaluation.requiredAuthorityStep(),
                    evaluation.actualSteps(),
                    evaluation.missingProcedureSteps(),
                    evaluation.procedureOrderValid(),
                    evaluation.authorityMatched(),
                    evaluation.passed(),
                    evaluation.resultSummary(),
                    actor.userId(),
                    now);
            validationRepository.create(validation);
            audit.append(new OperationAuditEvent(
                    "BUDGET_APPROVAL_PROGRAM_VALIDATED",
                    "BUDGET",
                    "validateApprovalProgram",
                    actor.userId(),
                    actor.username(),
                    RESULT_RESOURCE_TYPE,
                    validation.id().toString(),
                    task.id().toString(),
                    OperationAuditResultStatus.SUCCESS,
                    validation.passed() ? "PASS" : "RISK_FOUND",
                    "执行预算审批程序规范性校验",
                    "{\"ruleSetCode\":\"" + RULE_SET_CODE + "\",\"actualSource\":\"MANUAL_TEST\"}",
                    safe(command.requestId()),
                    safe(command.traceId()),
                    command.sourceIp(),
                    now));
            idempotency.markSucceeded(
                    claim.record(), RESULT_RESOURCE_TYPE, validation.id().toString(), now);
            return toView(validation, rules);
        } catch (RuntimeException failure) {
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public BudgetApprovalValidationPage history(UUID taskId, int page, int size) {
        int offset = (page - 1) * size;
        List<BudgetApprovalProgramView> items = validationRepository.findPage(taskId, offset, size).stream()
                .map(validation -> toView(validation, requireRuleSnapshot(validation.ruleSetCode())))
                .toList();
        return new BudgetApprovalValidationPage(items, page, size, validationRepository.count(taskId));
    }

    private BudgetApprovalProgramView replay(
            IdempotencyRecord record,
            String fingerprint,
            String actorId,
            UUID taskId,
            UUID itemId) {
        if (!fingerprint.equals(record.requestFingerprint()) || !actorId.equals(record.actorId())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "idempotency key fingerprint conflict");
        }
        if (record.status() == IdempotencyStatus.SUCCEEDED
                && RESULT_RESOURCE_TYPE.equals(record.resultResourceType())
                && record.resultResourceId() != null) {
            BudgetApprovalValidation validation = validationRepository
                    .findById(UUID.fromString(record.resultResourceId()))
                    .filter(candidate -> taskId.equals(candidate.sourceTaskId()))
                    .filter(candidate -> itemId.equals(candidate.sourceItemId()))
                    .filter(candidate -> actorId.equals(candidate.createdBy()))
                    .orElseThrow(() -> new BusinessException(
                            BusinessErrorCode.CONFLICT, "idempotent validation result is unavailable"));
            return toView(validation, requireRuleSnapshot(validation.ruleSetCode()));
        }
        throw new BusinessException(
                BusinessErrorCode.CONFLICT,
                record.status() == IdempotencyStatus.IN_PROGRESS
                        ? "budget approval validation is in progress"
                        : "idempotent budget approval validation previously failed");
    }

    private List<BudgetApprovalRule> requireActiveRules(String ruleSetCode) {
        return requireRules(ruleRepository.findActiveByRuleSetCode(ruleSetCode), "active", ruleSetCode);
    }

    private List<BudgetApprovalRule> requireRuleSnapshot(String ruleSetCode) {
        return requireRules(ruleRepository.findByRuleSetCode(ruleSetCode), "historical", ruleSetCode);
    }

    private static List<BudgetApprovalRule> requireRules(
            List<BudgetApprovalRule> availableRules,
            String kind,
            String ruleSetCode) {
        List<BudgetApprovalRule> rules = availableRules.stream()
                .sorted(Comparator.comparing((BudgetApprovalRule rule) -> rule.ruleGroup().ordinal())
                        .thenComparingInt(BudgetApprovalRule::stepOrder))
                .toList();
        if (rules.isEmpty()) {
            throw new IllegalStateException(kind + " budget approval rules are unavailable: " + ruleSetCode);
        }
        return rules;
    }

    private BudgetApprovalProgramView toView(
            BudgetApprovalValidation validation,
            List<BudgetApprovalRule> rules) {
        Map<String, BudgetApprovalStep> steps = rules.stream()
                .map(BudgetApprovalRule::step)
                .collect(Collectors.toMap(
                        BudgetApprovalStep::code,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        return new BudgetApprovalProgramView(
                validation.id(),
                validation.ruleSetCode(),
                validation.sourceTaskId(),
                validation.sourceItemId(),
                validation.sourceRowNumber(),
                validation.declaredAmount(),
                resolve(validation.requiredProcedureSteps(), steps),
                resolve(validation.requiredAuthorityStep(), steps),
                resolve(validation.actualSteps(), steps),
                resolve(validation.missingProcedureSteps(), steps),
                validation.procedureOrderValid(),
                validation.authorityMatched(),
                validation.passed(),
                validation.resultSummary(),
                validation.actualSource(),
                validation.createdAt());
    }

    private static List<BudgetApprovalStep> resolve(
            List<String> codes,
            Map<String, BudgetApprovalStep> steps) {
        return codes.stream().map(code -> resolve(code, steps)).toList();
    }

    private static BudgetApprovalStep resolve(
            String code,
            Map<String, BudgetApprovalStep> steps) {
        BudgetApprovalStep step = steps.get(code);
        if (step == null) {
            throw new IllegalStateException("approval step is unavailable in rule set: " + code);
        }
        return step;
    }

    private CurrentUser currentUser() {
        return authContext.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "Idempotency-Key is required");
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private static String fingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
