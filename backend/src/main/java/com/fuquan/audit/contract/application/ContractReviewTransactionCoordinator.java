package com.fuquan.audit.contract.application;

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
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理同步 F02 审查的持久化短事务边界。
 *
 * <p>DM8 是任务、结果、业务审计和持久化幂等记录的事实源。START、COMPLETE 分别提交，FAILURE 使用新事务，
 * 确定性规则本身不在本协调器事务内执行。</p>
 */
@Component
public class ContractReviewTransactionCoordinator {

    private final AuditCheckResultRepository checkResultRepository;
    private final AuditTaskRepository auditTaskRepository;
    private final IdempotencyPort idempotencyPort;
    private final OperationAuditPort operationAuditPort;
    private final FlowDebugLogger flowDebugLogger;

    /**
     * 创建使用公共审查、幂等和业务审计端口的事务协调器。
     *
     * @param checkResultRepository 检查结果持久化端口
     * @param auditTaskRepository 审查任务持久化端口
     * @param idempotencyPort 持久化幂等端口
     * @param operationAuditPort 长期业务操作审计端口
     * @param flowDebugLogger 分层流程调试日志记录器
     */
    public ContractReviewTransactionCoordinator(
            AuditCheckResultRepository checkResultRepository,
            AuditTaskRepository auditTaskRepository,
            IdempotencyPort idempotencyPort,
            OperationAuditPort operationAuditPort,
            FlowDebugLogger flowDebugLogger) {
        this.checkResultRepository = checkResultRepository;
        this.auditTaskRepository = auditTaskRepository;
        this.idempotencyPort = idempotencyPort;
        this.operationAuditPort = operationAuditPort;
        this.flowDebugLogger = flowDebugLogger;
    }

    /**
     * 在 START 短事务中取得幂等 claim、创建任务并转换为 PROCESSING。
     *
     * <p>相同幂等键的成功记录返回历史结果；处理中、失败或指纹冲突的记录拒绝新的规则执行。</p>
     *
     * @param contract 本次审查合同快照
     * @param user 发起审查的用户
     * @param command 原始审查命令和请求元数据
     * @param fingerprint 绑定业务输入的请求指纹
     * @param taskId 预先分配的任务标识
     * @param startedAt 统一业务开始时间
     * @return 已提交的新执行身份或历史结果重放
     */
    @Transactional
    public ContractReviewStart start(
            Contract contract,
            CurrentUser user,
            ContractReviewCommand command,
            String fingerprint,
            UUID taskId,
            Instant startedAt) {
        IdempotencyClaim claim = idempotencyPort.claim(new IdempotencyRequest(
                ContractReviewService.IDEMPOTENCY_SCOPE, command.idempotencyKey(), user.userId(),
                "CONTRACT", contract.id().toString(), fingerprint, "AUDIT_TASK", taskId.toString(),
                command.requestId(), command.traceId(), startedAt));
        flowDebugLogger.log(
                "IDEMPOTENCY_CLAIM", "CONTRACT", "APPLICATION", "F02_REVIEW",
                Map.of("status", claim.acquired() ? "ACQUIRED" : claim.record().status().name()));
        if (!claim.acquired()) {
            return duplicateClaim(claim.record(), fingerprint);
        }

        AuditTask task = AuditTask.pending(
                taskId, "CONTRACT", contract.id(), contract.versionNo(), ContractReviewService.REVIEW_TYPE,
                user.userId(), command.requestId(), command.traceId(), startedAt);
        auditTaskRepository.create(task);
        flowDebugLogger.log("AUDIT_TASK_CREATE", "CONTRACT", "REPOSITORY", "create");
        task = auditTaskRepository.transition(
                task.id(), AuditTaskStatus.PENDING, task.versionNo(),
                AuditTaskStatus.PROCESSING, startedAt);
        logTransition(AuditTaskStatus.PENDING, task);
        appendAudit(
                "CONTRACT_REVIEW_STARTED", "REVIEW_START", OperationAuditResultStatus.SUCCESS,
                null, "Contract F02 review started", null,
                user, contract, task, command, startedAt);
        return ContractReviewStart.executing(task, claim.record());
    }

    /**
     * 在 COMPLETE 短事务中追加检查结果、完成任务、写业务审计并标记幂等成功。
     *
     * @param start 已提交的 START 执行身份
     * @param results 规则执行产生的公共检查结果
     * @param user 发起审查的用户
     * @param contract 审查合同快照
     * @param command 原始审查命令和请求元数据
     * @param completedAt 统一完成时间
     * @return 已完成且非重放的审查汇总
     */
    @Transactional
    public ContractReviewResult complete(
            ContractReviewStart start,
            List<AuditCheckResult> results,
            CurrentUser user,
            Contract contract,
            ContractReviewCommand command,
            Instant completedAt) {
        checkResultRepository.appendAll(results);
        flowDebugLogger.log(
                "AUDIT_RESULT_WRITE", "CONTRACT", "REPOSITORY", "appendAll",
                Map.of("count", results.size()));

        AuditTask completedTask = auditTaskRepository.transition(
                start.task().id(), AuditTaskStatus.PROCESSING, start.task().versionNo(),
                AuditTaskStatus.DONE, completedAt);
        logTransition(AuditTaskStatus.PROCESSING, completedTask);

        RiskLevel overallRisk = overallRisk(results);
        boolean blockSign = results.stream().anyMatch(AuditCheckResult::blockSign);
        long blockingCount = results.stream().filter(AuditCheckResult::blockSign).count();
        appendAudit(
                "CONTRACT_REVIEW_COMPLETED", "REVIEW_COMPLETE", OperationAuditResultStatus.SUCCESS,
                overallRisk.name(), "Contract F02 review completed",
                results.size() + " findings evaluated; " + blockingCount + " blocking findings",
                user, contract, completedTask, command, completedAt);
        idempotencyPort.markSucceeded(
                start.idempotency(), "AUDIT_TASK", completedTask.id().toString(), completedAt);
        return new ContractReviewResult(completedTask, results, overallRisk, blockSign, false);
    }

    /**
     * 在 REQUIRES_NEW 事务中将 PROCESSING 任务收口为 FAILED。
     *
     * <p>独立事务确保调用方原事务异常或回滚时，失败任务、失败审计和幂等失败状态仍可持久化。</p>
     *
     * @param start 已提交的 START 执行身份
     * @param user 发起审查的用户
     * @param contract 审查合同快照
     * @param command 原始审查命令和请求元数据
     * @param failure 导致审查失败的异常
     * @param failedAt 统一失败时间
     * @return 已转换为 FAILED 的任务
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditTask fail(
            ContractReviewStart start,
            CurrentUser user,
            Contract contract,
            ContractReviewCommand command,
            RuntimeException failure,
            Instant failedAt) {
        AuditTask failedTask = auditTaskRepository.transition(
                start.task().id(), AuditTaskStatus.PROCESSING, start.task().versionNo(),
                AuditTaskStatus.FAILED, failedAt);
        logTransition(AuditTaskStatus.PROCESSING, failedTask);
        appendAudit(
                "CONTRACT_REVIEW_FAILED", "REVIEW_FAILED", OperationAuditResultStatus.FAILURE,
                "FAILED", "Contract F02 review failed", failure.getClass().getSimpleName(),
                user, contract, failedTask, command, failedAt);
        idempotencyPort.markFailed(start.idempotency(), failedAt);
        return failedTask;
    }

    /**
     * 在只读事务中读取合同最近一次已完成审查及其结果。
     *
     * @param sourceId 合同唯一标识
     * @return 最近 DONE 任务的审查汇总
     * @throws BusinessException 找不到任务或最近任务尚未完成时抛出
     */
    @Transactional(readOnly = true)
    public ContractReviewResult latest(UUID sourceId) {
        AuditTask task = auditTaskRepository.findLatest(
                        "CONTRACT", sourceId, ContractReviewService.REVIEW_TYPE)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        if (task.status() != AuditTaskStatus.DONE) {
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "contract review has no completed result");
        }
        return resultForTask(task, false);
    }

    /**
     * 解释未取得的持久化幂等 claim，并决定重放或拒绝请求。
     *
     * @param record 已存在的幂等记录
     * @param fingerprint 当前请求业务指纹
     * @return 历史成功结果重放
     * @throws BusinessException 指纹不同、请求仍处理中或历史执行失败时抛出冲突
     * @throws IllegalStateException 成功幂等记录引用的任务缺失时抛出
     */
    private ContractReviewStart duplicateClaim(IdempotencyRecord record, String fingerprint) {
        if (!record.requestFingerprint().equals(fingerprint)) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "idempotency key fingerprint conflict");
        }
        if (record.status() == IdempotencyStatus.SUCCEEDED) {
            UUID taskId = UUID.fromString(record.resultResourceId());
            AuditTask task = auditTaskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("idempotency result task is missing"));
            return ContractReviewStart.replay(resultForTask(task, true));
        }
        String taskId = record.resultResourceId();
        throw new BusinessException(
                BusinessErrorCode.CONFLICT,
                record.status() == IdempotencyStatus.IN_PROGRESS
                        ? "idempotent request is in progress; taskId=" + taskId
                        : "idempotent request previously failed; use a new Idempotency-Key");
    }

    /**
     * 从已完成任务和持久化检查结果重建 F02 汇总。
     *
     * @param task 已完成审查任务
     * @param replayed 是否作为幂等历史结果返回
     * @return 聚合风险和阻断标记后的审查结果
     * @throws IllegalStateException DONE 任务没有任何检查结果时抛出
     */
    private ContractReviewResult resultForTask(AuditTask task, boolean replayed) {
        List<AuditCheckResult> results = checkResultRepository.findByTaskId(task.id());
        if (results.isEmpty()) {
            throw new IllegalStateException("completed audit task has no check results");
        }
        RiskLevel overallRisk = overallRisk(results);
        return new ContractReviewResult(
                task, results, overallRisk,
                results.stream().anyMatch(AuditCheckResult::blockSign), replayed);
    }

    /**
     * 聚合全部检查结果中的最高风险等级。
     *
     * @param results 持久化检查结果
     * @return 最高风险等级；空列表的具体语义由 RiskLevel 统一定义
     */
    private RiskLevel overallRisk(List<AuditCheckResult> results) {
        return RiskLevel.highest(results.stream().map(AuditCheckResult::riskLevel).toList());
    }

    /**
     * 将 F02 生命周期事件写入长期业务操作审计。
     *
     * @param eventType 稳定审计事件类型
     * @param action 业务动作
     * @param status 动作结果状态
     * @param businessResult 业务结果，可为空
     * @param description 审计摘要
     * @param detail 审计详情，可为空
     * @param user 操作用户
     * @param contract 操作合同
     * @param task 关联审查任务
     * @param command 携带 RequestId、TraceId 和来源 IP 的命令
     * @param occurredAt 事件发生时间
     */
    private void appendAudit(
            String eventType,
            String action,
            OperationAuditResultStatus status,
            String businessResult,
            String description,
            String detail,
            CurrentUser user,
            Contract contract,
            AuditTask task,
            ContractReviewCommand command,
            Instant occurredAt) {
        operationAuditPort.append(new OperationAuditEvent(
                eventType, "CONTRACT", action, user.userId(), user.username(),
                "CONTRACT", contract.id().toString(), task.id().toString(), status,
                businessResult, description, detail, command.requestId(), command.traceId(),
                command.sourceIp(), occurredAt));
        flowDebugLogger.log("OPERATION_AUDIT_WRITE", "CONTRACT", "APPLICATION", action);
    }

    /**
     * 记录任务乐观锁状态转换及新版本号。
     *
     * @param previous 转换前状态
     * @param task 转换后任务快照
     */
    private void logTransition(AuditTaskStatus previous, AuditTask task) {
        flowDebugLogger.log(
                "AUDIT_TASK_TRANSITION", "CONTRACT", "REPOSITORY", "transition",
                Map.of("transition", previous + "->" + task.status(), "versionNo", task.versionNo()));
    }
}
