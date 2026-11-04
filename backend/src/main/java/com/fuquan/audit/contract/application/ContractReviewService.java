package com.fuquan.audit.contract.application;

import com.fuquan.audit.common.application.port.IdempotencyConcurrencyException;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.port.ContractRuleConfigRepository;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractReviewContext;
import com.fuquan.audit.contract.domain.ContractReviewFinding;
import com.fuquan.audit.contract.domain.ContractReviewRule;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.review.application.port.AuditTaskConcurrencyException;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排 F02 合同确定性审查用例。
 *
 * <p>服务先通过独立 START 短事务提交幂等 claim 和 PROCESSING 任务，再在事务外执行五条 Java 规则，
 * 最后通过独立 COMPLETE 或 FAILURE 事务收口，避免规则执行期间占用数据库长事务。</p>
 */
@Service
public class ContractReviewService {

    public static final String REVIEW_TYPE = "F02_DETERMINISTIC";
    public static final String IDEMPOTENCY_SCOPE = "CONTRACT_F02_REVIEW";
    public static final String REVIEW_RUN_INTERNAL_KEY_PREFIX = "reviewrun-f02:";
    private static final Pattern SAFE_IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Logger log = LoggerFactory.getLogger(ContractReviewService.class);

    private final ContractRepository contractRepository;
    private final ContractRuleConfigRepository ruleConfigRepository;
    private final ContractReviewTransactionCoordinator transactionCoordinator;
    private final AuthContext authContext;
    private final Clock clock;
    private final FlowDebugLogger flowDebugLogger;
    private final Map<String, ContractReviewRule> rules;

    /**
     * 创建 F02 编排服务并按稳定规则编码索引规则实现。
     *
     * @param contractRepository 合同读取端口
     * @param ruleConfigRepository 规则配置读取端口
     * @param transactionCoordinator F02 短事务协调器
     * @param authContext 当前认证用户端口
     * @param clock 业务时间来源
     * @param flowDebugLogger 分层流程调试日志记录器
     * @param rules 已注册的确定性规则实现
     * @throws IllegalStateException 存在重复规则编码时由不可变映射构建过程抛出
     */
    public ContractReviewService(
            ContractRepository contractRepository,
            ContractRuleConfigRepository ruleConfigRepository,
            ContractReviewTransactionCoordinator transactionCoordinator,
            AuthContext authContext,
            Clock clock,
            FlowDebugLogger flowDebugLogger,
            List<ContractReviewRule> rules) {
        this.contractRepository = contractRepository;
        this.ruleConfigRepository = ruleConfigRepository;
        this.transactionCoordinator = transactionCoordinator;
        this.authContext = authContext;
        this.clock = clock;
        this.flowDebugLogger = flowDebugLogger;
        this.rules = rules.stream().collect(Collectors.toUnmodifiableMap(
                ContractReviewRule::ruleCode, rule -> rule));
    }

    /**
     * 执行或幂等重放一次 F02 合同审查。
     *
     * <p>新请求依次经历 START、事务外规则执行和 COMPLETE；规则执行异常使用 REQUIRES_NEW FAILURE 事务记录失败。
     * 并发写冲突统一映射为业务冲突，且不会用 FAILED 覆盖其他写入者已经完成的状态。</p>
     *
     * @param command 包含合同、幂等键和请求审计元数据的审查命令
     * @return 新执行结果或相同指纹成功请求的历史结果
     * @throws BusinessException 合同不存在、未认证、幂等键非法、规则未配置或并发冲突时抛出
     * @throws ContractReviewExecutionFailedException 规则执行或持久化收口发生非业务异常时抛出
     */
    public ContractReviewResult review(ContractReviewCommand command) {
        validateIdempotencyKey(command.idempotencyKey());
        if (command.idempotencyKey().startsWith(REVIEW_RUN_INTERNAL_KEY_PREFIX)) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                    "reserved Idempotency-Key namespace");
        }
        CurrentUser user = currentUser();
        Contract contract = getContract(command.contractId());
        List<ContractRuleConfiguration> configurations = enabledConfigurations();
        return execute(contract, configurations, user, command);
    }

    /**
     * Executes F02 against inputs already frozen and verified by a contract review run.
     *
     * <p>This seam deliberately performs no latest Contract or rule-configuration reads. The
     * caller owns the frozen input authority and supplies a server-owned idempotency key in the
     * command.</p>
     *
     * @param contract exact frozen Contract snapshot
     * @param configurations exact immutable F02 configurations frozen for the run
     * @param user authenticated actor captured by the review-step command
     * @param command server-owned F02 command
     * @return durable F02 AuditTask and its existing AuditCheckResult rows
     */
    public ContractReviewResult executeFrozen(
            Contract contract,
            List<ContractRuleConfiguration> configurations,
            CurrentUser user,
            ContractReviewCommand command) {
        validateIdempotencyKey(command.idempotencyKey());
        if (!contract.id().equals(command.contractId())) {
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT,
                    "frozen contract does not match F02 command");
        }
        return execute(contract, List.copyOf(configurations), user, command);
    }

    private ContractReviewResult execute(
            Contract contract,
            List<ContractRuleConfiguration> configurations,
            CurrentUser user,
            ContractReviewCommand command) {
        UUID taskId = UUID.randomUUID();
        Instant startedAt = clock.instant();
        String fingerprint = IdempotencyFingerprint.forF02(
                IDEMPOTENCY_SCOPE, user.userId(), contract, configurations);

        ContractReviewStart start;
        try {
            start = transactionCoordinator.start(
                    contract, user, command, fingerprint, taskId, startedAt);
        } catch (AuditTaskConcurrencyException | IdempotencyConcurrencyException exception) {
            throw new BusinessException(BusinessErrorCode.CONFLICT);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ContractReviewExecutionFailedException(exception);
        }
        if (start.isReplay()) {
            return start.replay();
        }

        long operationStartedAt = System.nanoTime();
        flowDebugLogger.log("APPLICATION_START", "CONTRACT", "APPLICATION", "F02_REVIEW");
        try {
            List<ContractReviewFinding> findings = executeRules(contract, configurations);
            Instant completedAt = clock.instant();
            List<AuditCheckResult> results = findings.stream()
                    .map(finding -> toAuditCheckResult(start.task().id(), finding, completedAt))
                    .toList();
            ContractReviewResult result = transactionCoordinator.complete(
                    start, results, user, contract, command, completedAt);
            flowDebugLogger.log(
                    "APPLICATION_END", "CONTRACT", "APPLICATION", "F02_REVIEW",
                    Map.of("durationMs", elapsedMs(operationStartedAt), "outcome", result.overallRisk()));
            return result;
        } catch (AuditTaskConcurrencyException | IdempotencyConcurrencyException exception) {
            // A stale COMPLETE writer is a conflict. Never overwrite another writer's state with FAILED.
            throw new BusinessException(BusinessErrorCode.CONFLICT);
        } catch (RuntimeException originalFailure) {
            closeFailure(start, user, contract, command, originalFailure);
            throw new ContractReviewExecutionFailedException(originalFailure);
        }
    }

    /**
     * 在只读事务中读取完整合同聚合。
     *
     * @param contractId 合同唯一标识
     * @return 用于 API 展示或审查的合同快照
     * @throws BusinessException 合同不存在时抛出 NOT_FOUND
     */
    @Transactional(readOnly = true)
    public Contract getContract(UUID contractId) {
        return contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
    }

    /**
     * 查询指定合同最近一次已完成的 F02 结果。
     *
     * @param contractId 合同唯一标识
     * @return 最近已完成的任务及其持久化结论
     * @throws BusinessException 合同或已完成结果不存在时抛出
     */
    public ContractReviewResult latestResult(UUID contractId) {
        getContract(contractId);
        return transactionCoordinator.latest(contractId);
    }

    /**
     * 尝试在独立失败事务中关闭已启动任务，并保留原始异常为主异常。
     *
     * <p>失败收口自身出错时只作为 suppressed exception 附加和记录，不替换最初导致审查失败的异常。</p>
     *
     * @param start 已提交的 START 执行身份
     * @param user 发起审查的当前用户
     * @param contract 审查合同快照
     * @param command 原始审查命令
     * @param originalFailure 原始执行异常
     */
    private void closeFailure(
            ContractReviewStart start,
            CurrentUser user,
            Contract contract,
            ContractReviewCommand command,
            RuntimeException originalFailure) {
        try {
            transactionCoordinator.fail(
                    start, user, contract, command, originalFailure, clock.instant());
        } catch (RuntimeException closureFailure) {
            originalFailure.addSuppressed(closureFailure);
            log.atError()
                    .addKeyValue("event", "CONTRACT_REVIEW_FAILURE_CLOSURE_FAILED")
                    .addKeyValue("module", "CONTRACT")
                    .addKeyValue("layer", "APPLICATION")
                    .addKeyValue("operation", "F02_REVIEW")
                    .addKeyValue("failureType", closureFailure.getClass().getSimpleName())
                    .log("Contract review failure closure failed");
        }
    }

    /**
     * 将合同领域规则结论映射为公共可持久化检查结果。
     *
     * @param taskId 所属审查任务标识
     * @param finding F02 领域规则结论
     * @param createdAt 结论形成时间
     * @return 带独立标识和规则版本的公共检查结果
     */
    private AuditCheckResult toAuditCheckResult(
            UUID taskId, ContractReviewFinding finding, Instant createdAt) {
        return new AuditCheckResult(
                UUID.randomUUID(), taskId, finding.ruleCode(), finding.itemKey(),
                finding.checkStatus(), finding.riskLevel(), finding.blockSign(),
                finding.actualValue(), finding.expectedValue(), finding.detail(),
                finding.ruleVersion(), createdAt);
    }

    /**
     * 在数据库事务外按已锁定配置顺序执行所有启用规则。
     *
     * @param contract 本次审查的合同快照
     * @param configurations 已启用且按规则编码排序的配置
     * @return 所有规则结论的不可变列表
     * @throws IllegalStateException 启用配置没有对应 Java 规则实现时抛出
     */
    private List<ContractReviewFinding> executeRules(
            Contract contract,
            List<ContractRuleConfiguration> configurations) {
        ContractReviewContext context = new ContractReviewContext(contract);
        List<ContractReviewFinding> findings = new ArrayList<>();
        for (ContractRuleConfiguration configuration : configurations) {
            ContractReviewRule rule = rules.get(configuration.ruleCode());
            if (rule == null) {
                throw new IllegalStateException("enabled F02 rule implementation is missing");
            }
            long startedAt = System.nanoTime();
            flowDebugLogger.log(
                    "RULE_START", "CONTRACT", "DOMAIN", "F02_REVIEW",
                    Map.of("rule", rule.ruleCode()));
            List<ContractReviewFinding> ruleFindings = rule.evaluate(context, configuration);
            findings.addAll(ruleFindings);
            flowDebugLogger.log(
                    "RULE_END", "CONTRACT", "DOMAIN", "F02_REVIEW",
                    Map.of(
                            "rule", rule.ruleCode(),
                            "status", aggregateStatus(ruleFindings),
                            "durationMs", elapsedMs(startedAt)));
        }
        return List.copyOf(findings);
    }

    /**
     * 读取、过滤并稳定排序本次审查使用的启用规则配置。
     *
     * @return 至少包含一条配置的有序列表
     * @throws BusinessException 没有启用的 F02 规则时抛出业务校验错误
     */
    private List<ContractRuleConfiguration> enabledConfigurations() {
        List<ContractRuleConfiguration> configurations = ruleConfigRepository.findEnabledForF02().stream()
                .filter(ContractRuleConfiguration::enabled)
                .sorted(Comparator.comparing(ContractRuleConfiguration::ruleCode))
                .toList();
        if (configurations.isEmpty()) {
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "no F02 rule is enabled");
        }
        return configurations;
    }

    /**
     * 获取执行 F02 用例的当前认证用户。
     *
     * @return 当前用户及其角色
     * @throws BusinessException 当前请求未认证时抛出 UNAUTHENTICATED
     */
    private CurrentUser currentUser() {
        return authContext.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    /**
     * 校验 Idempotency-Key 的长度和安全字符集合。
     *
     * @param key 客户端提供的幂等键
     * @throws BusinessException 幂等键不符合格式约束时抛出 INVALID_ARGUMENT
     */
    private void validateIdempotencyKey(String key) {
        if (!SAFE_IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid Idempotency-Key");
        }
    }

    /**
     * 将一条规则产生的多个结论聚合为流程日志状态。
     *
     * @param findings 单条规则的结论列表
     * @return 按 FAIL、NOT_EVALUATED、PASS 优先级聚合的状态文本
     */
    private String aggregateStatus(List<ContractReviewFinding> findings) {
        if (findings.stream().anyMatch(finding -> finding.checkStatus() == CheckStatus.FAIL)) {
            return "FAIL";
        }
        if (findings.stream().anyMatch(finding -> finding.checkStatus() == CheckStatus.NOT_EVALUATED)) {
            return "NOT_EVALUATED";
        }
        return "PASS";
    }

    /**
     * 将单调时钟起点换算为已耗时毫秒数。
     *
     * @param startedAt {@link System#nanoTime()} 取得的起点
     * @return 非墙钟语义的耗时毫秒数
     */
    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
