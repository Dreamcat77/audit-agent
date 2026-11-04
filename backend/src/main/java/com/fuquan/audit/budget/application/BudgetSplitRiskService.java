package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.BudgetSplitRiskAssessmentRepository;
import com.fuquan.audit.budget.application.port.BudgetSplitRiskRuleRepository;
import com.fuquan.audit.budget.application.port.ProcurementDemandRepository;
import com.fuquan.audit.budget.domain.BudgetSplitRiskAssessment;
import com.fuquan.audit.budget.domain.ProcurementDemand;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetSplitRiskService {

    private static final String SCOPE = "BUDGET_F05_SPLIT_RISK_CHECK";
    private static final String RESULT_TYPE = "BUDGET_SPLIT_RISK_ASSESSMENT";

    private final ProcurementDemandRepository demandRepository;
    private final BudgetSplitRiskRuleRepository ruleRepository;
    private final BudgetSplitRiskAssessmentRepository assessmentRepository;
    private final BudgetSplitRiskEvaluator evaluator;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final AuthContext authContext;
    private final Clock clock;

    public BudgetSplitRiskService(
            ProcurementDemandRepository demandRepository,
            BudgetSplitRiskRuleRepository ruleRepository,
            BudgetSplitRiskAssessmentRepository assessmentRepository,
            BudgetSplitRiskEvaluator evaluator,
            IdempotencyPort idempotency,
            OperationAuditPort audit,
            AuthContext authContext,
            Clock clock) {
        this.demandRepository = demandRepository;
        this.ruleRepository = ruleRepository;
        this.assessmentRepository = assessmentRepository;
        this.evaluator = evaluator;
        this.idempotency = idempotency;
        this.audit = audit;
        this.authContext = authContext;
        this.clock = clock;
    }

    @Transactional
    public BudgetSplitRiskView check(BudgetSplitRiskCommand command) {
        CurrentUser actor = authContext.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        List<UUID> ids = normalizeIds(command.procurementIds());
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "Idempotency-Key is required");
        }
        List<ProcurementDemand> demands = demandRepository.findAllByIds(ids);
        Set<UUID> foundIds = demands.stream().map(ProcurementDemand::id)
                .collect(java.util.stream.Collectors.toSet());
        List<UUID> missing = ids.stream().filter(id -> !foundIds.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.NOT_FOUND,
                    "采购记录不存在或已删除: " + missing);
        }

        String fingerprint = fingerprint(actor.userId(), ids.stream().map(UUID::toString).toArray(String[]::new));
        UUID assessmentId = UUID.randomUUID();
        Instant now = clock.instant();
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(
                SCOPE, command.idempotencyKey(), actor.userId(), "PROCUREMENT_DEMAND_SET",
                fingerprint(ids), fingerprint, RESULT_TYPE, assessmentId.toString(),
                safe(command.requestId()), safe(command.traceId()), now));
        if (!claim.acquired()) return replay(claim.record(), fingerprint, actor.userId());

        try {
            BudgetSplitRiskEvaluator.Evaluation result;
            try {
                result = evaluator.evaluate(demands, ruleRepository.findActive());
            } catch (IllegalArgumentException invalid) {
                throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, invalid.getMessage(), invalid);
            }
            BudgetSplitRiskAssessment assessment = new BudgetSplitRiskAssessment(
                    assessmentId, ids, result.totalAmount(), result.riskLevel(), result.riskScore(),
                    result.splitRatio(), result.timeConcentration(), result.supplierConcentration(),
                    result.records(), actor.userId(), now);
            assessmentRepository.create(assessment);
            audit.append(new OperationAuditEvent(
                    "BUDGET_F05_SPLIT_RISK_CHECKED", "BUDGET", "checkSplitRisk",
                    actor.userId(), actor.username(), RESULT_TYPE, assessment.id().toString(), null,
                    OperationAuditResultStatus.SUCCESS, assessment.riskLevel(),
                    "执行预算项目拆分风险分析",
                    "{\"recordCount\":" + ids.size() + ",\"riskScore\":" + assessment.riskScore() + "}",
                    safe(command.requestId()), safe(command.traceId()), command.sourceIp(), now));
            idempotency.markSucceeded(claim.record(), RESULT_TYPE, assessment.id().toString(), now);
            return BudgetSplitRiskView.from(assessment);
        } catch (RuntimeException failure) {
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public BudgetSplitRiskView get(UUID assessmentId) {
        return BudgetSplitRiskView.from(assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "项目拆分风险分析结果不存在: " + assessmentId)));
    }

    private BudgetSplitRiskView replay(IdempotencyRecord record, String fingerprint, String actorId) {
        if (!fingerprint.equals(record.requestFingerprint()) || !actorId.equals(record.actorId())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "idempotency key fingerprint conflict");
        }
        if (record.status() == IdempotencyStatus.SUCCEEDED
                && RESULT_TYPE.equals(record.resultResourceType()) && record.resultResourceId() != null) {
            return get(UUID.fromString(record.resultResourceId()));
        }
        throw new BusinessException(BusinessErrorCode.CONFLICT,
                record.status() == IdempotencyStatus.IN_PROGRESS
                        ? "项目拆分风险分析正在处理中"
                        : "上次项目拆分风险分析未完成");
    }

    private static List<UUID> normalizeIds(List<UUID> source) {
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(source);
        if (unique.size() < 2 || unique.size() > 100 || unique.contains(null)) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                    "procurementIds must contain 2 to 100 unique values");
        }
        return unique.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private static String fingerprint(List<UUID> ids) {
        return fingerprint("PROCUREMENT_IDS", ids.stream().map(UUID::toString).toArray(String[]::new));
    }

    private static String fingerprint(String prefix, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix.getBytes(StandardCharsets.UTF_8));
            for (String value : values) {
                digest.update((byte) 0);
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }
}
