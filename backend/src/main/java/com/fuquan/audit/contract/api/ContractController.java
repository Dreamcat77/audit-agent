package com.fuquan.audit.contract.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.ContractListQuery;
import com.fuquan.audit.contract.application.ContractPage;
import com.fuquan.audit.contract.application.ContractQueryService;
import com.fuquan.audit.contract.application.ContractReviewCommand;
import com.fuquan.audit.contract.application.ContractReviewResult;
import com.fuquan.audit.contract.application.ContractReviewService;
import com.fuquan.audit.contract.application.ContractSummary;
import com.fuquan.audit.contract.application.F02ReviewProgress;
import com.fuquan.audit.contract.application.F02ReviewProgressService;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.CheckStatus;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露 F02 合同查询、确定性审查和审查结果读取接口。
 *
 * <p>控制器负责角色授权后的 HTTP 参数提取、RequestId/TraceId/来源 IP 传递和响应 DTO 映射；
 * 审查规则、幂等和事务边界由应用层负责。</p>
 */
@RestController
@Validated
@RequestMapping("/api/v1/contracts")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class ContractController {

    private final ContractReviewService reviewService;
    private final F02ReviewProgressService reviewProgressService;
    private final ContractQueryService queryService;
    private final Tracer tracer;
    private final FlowDebugLogger flowDebugLogger;

    /**
     * 创建合同审查 HTTP 入口。
     *
     * @param reviewService F02 应用服务
     * @param tracer 当前 HTTP 链路追踪器
     * @param flowDebugLogger 分层流程调试日志记录器
     */
    public ContractController(
            ContractReviewService reviewService,
            F02ReviewProgressService reviewProgressService,
            ContractQueryService queryService,
            Tracer tracer,
            FlowDebugLogger flowDebugLogger) {
        this.reviewService = reviewService;
        this.reviewProgressService = reviewProgressService;
        this.queryService = queryService;
        this.tracer = tracer;
        this.flowDebugLogger = flowDebugLogger;
    }

    /** 查询可用于新建合同内审的真实合同目录。 */
    @GetMapping
    public ApiResponse<ContractPageResponse> contracts(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 32) String status,
            @RequestParam(required = false) @Size(max = 128) String keyword) {
        controllerEnter("LIST_CONTRACTS");
        return ApiResponse.success(ContractPageResponse.from(
                queryService.list(new ContractListQuery(page, size, status, keyword))));
    }

    /**
     * 查询指定 UUID 对应的结构化合同及中标依据。
     *
     * @param id 合同唯一标识
     * @return 公共响应封装的合同详情
     */
    @GetMapping("/{id}")
    public ApiResponse<ContractResponse> contract(@PathVariable UUID id) {
        controllerEnter("GET_CONTRACT");
        return ApiResponse.success(ContractResponse.from(reviewService.getContract(id)));
    }

    /**
     * 发起一次具有持久化幂等约束的 F02 确定性审查。
     *
     * <p>请求级 RequestId、TraceId 和来源 IP 只用于本次命令与审计证据，不作为合同业务字段。</p>
     *
     * @param id 待审查合同唯一标识
     * @param idempotencyKey 客户端为本次写命令提供的非空幂等键
     * @param request 当前 HTTP 请求，用于取得来源 IP
     * @return 公共响应封装的完整 F02 审查结果
     * @throws IllegalStateException 当前 HTTP 请求缺少追踪 Span 时抛出
     */
    @PostMapping("/{id}/agent-review")
    public ApiResponse<ReviewResponse> review(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request) {
        controllerEnter("F02_REVIEW");
        Span span = tracer.currentSpan();
        if (span == null) {
            throw new IllegalStateException("HTTP review request has no tracing span");
        }
        ContractReviewResult result = reviewService.review(new ContractReviewCommand(
                id,
                idempotencyKey,
                MDC.get(RequestIdFilter.MDC_KEY),
                span.context().traceId(),
                request.getRemoteAddr()));
        return ApiResponse.success(ReviewResponse.from(result));
    }

    /**
     * 查询合同最近一次已完成的 F02 审查结果。
     *
     * @param id 合同唯一标识
     * @return 最近已完成任务及其全部检查结论
     */
    @GetMapping("/{id}/agent-results")
    public ApiResponse<ReviewResponse> results(@PathVariable UUID id) {
        controllerEnter("GET_F02_RESULTS");
        return ApiResponse.success(ReviewResponse.from(reviewService.latestResult(id)));
    }

    /** Reads the contract-owned progress projection for one real F02 audit task. */
    @GetMapping("/{contractId}/audit-tasks/{taskId}/review-progress")
    public ApiResponse<F02ReviewProgress> reviewProgress(
            @PathVariable UUID contractId,
            @PathVariable UUID taskId) {
        controllerEnter("GET_F02_REVIEW_PROGRESS");
        return ApiResponse.success(reviewProgressService.getProgress(contractId, taskId));
    }

    /**
     * 查询合同最近一次已完成审查中的非 PASS 偏差项。
     *
     * @param id 合同唯一标识
     * @return 审查任务标识和偏差结论列表
     */
    @GetMapping("/{id}/deviations")
    public ApiResponse<DeviationResponse> deviations(@PathVariable UUID id) {
        controllerEnter("GET_F02_DEVIATIONS");
        ContractReviewResult result = reviewService.latestResult(id);
        return ApiResponse.success(new DeviationResponse(
                result.task().id(),
                result.findings().stream()
                        .filter(finding -> finding.checkStatus() != CheckStatus.PASS)
                        .map(FindingResponse::from)
                        .toList()));
    }

    /**
     * 生成合同最近一次已完成审查的签署检查清单。
     *
     * @param id 合同唯一标识
     * @return 总体风险、阻断标记及逐规则签署检查项
     */
    @GetMapping("/{id}/sign-checklist")
    public ApiResponse<SignChecklistResponse> signChecklist(@PathVariable UUID id) {
        controllerEnter("GET_F02_SIGN_CHECKLIST");
        ContractReviewResult result = reviewService.latestResult(id);
        return ApiResponse.success(new SignChecklistResponse(
                result.task().id(),
                result.overallRisk().name(),
                result.blockSign(),
                result.findings().stream().map(SignChecklistItem::from).toList()));
    }

    /**
     * 记录控制器已通过授权并进入指定合同操作。
     *
     * @param operation 稳定的控制器操作名称
     */
    private void controllerEnter(String operation) {
        flowDebugLogger.log("AUTHORIZATION_SUCCESS", "CONTRACT", "SECURITY", operation);
        flowDebugLogger.log("CONTROLLER_ENTER", "CONTRACT", "WEB", operation);
    }

    /**
     * 面向 API 的结构化合同响应。
     *
     * @param id 合同唯一标识
     * @param contractNo 合同业务编号
     * @param contractName 合同名称
     * @param contractType 合同类型
     * @param partyA 甲方名称
     * @param partyB 乙方名称
     * @param partyBCreditCode 乙方统一社会信用代码
     * @param contractAmount 合同金额
     * @param amountInWords 合同金额中文大写
     * @param status 合同状态
     * @param versionNo 合同版本号
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param lineItems 合同明细响应
     * @param awardNotice 中标依据响应
     */
    public record ContractResponse(
            UUID id,
            String contractNo,
            String contractName,
            String contractType,
            String partyA,
            String partyB,
            String partyBCreditCode,
            BigDecimal contractAmount,
            String amountInWords,
            String status,
            long versionNo,
            Instant createdAt,
            Instant updatedAt,
            List<LineItemResponse> lineItems,
            AwardNoticeResponse awardNotice) {

        /**
         * 将合同领域快照映射为不暴露领域值对象的 API DTO。
         *
         * @param contract 合同领域快照
         * @return 合同响应 DTO
         */
        static ContractResponse from(Contract contract) {
            return new ContractResponse(
                    contract.id(), contract.contractNo(), contract.contractName(), contract.contractType(),
                    contract.partyA(), contract.partyB(), contract.partyBCreditCode(),
                    contract.contractAmount().amount(), contract.amountInWords(), contract.status(),
                    contract.versionNo(), contract.createdAt(), contract.updatedAt(),
                    contract.lineItems().stream().map(LineItemResponse::from).toList(),
                    contract.awardNotice() == null ? null : AwardNoticeResponse.from(contract.awardNotice()));
        }
    }

    /** 合同目录分页 API DTO。 */
    public record ContractPageResponse(
            List<ContractSummaryResponse> items,
            int page,
            int size,
            long total,
            long totalPages) {
        static ContractPageResponse from(ContractPage value) {
            return new ContractPageResponse(
                    value.items().stream().map(ContractSummaryResponse::from).toList(),
                    value.page(), value.size(), value.total(), value.totalPages());
        }
    }

    /** 不加载 lineItems/awardNotice 的合同目录行。 */
    public record ContractSummaryResponse(
            UUID id,
            String contractNo,
            String contractName,
            String contractType,
            String partyA,
            String partyB,
            String partyBCreditCode,
            BigDecimal contractAmount,
            String amountInWords,
            String status,
            long versionNo,
            Instant createdAt,
            Instant updatedAt) {
        static ContractSummaryResponse from(ContractSummary value) {
            return new ContractSummaryResponse(
                    value.id(), value.contractNo(), value.contractName(), value.contractType(),
                    value.partyA(), value.partyB(), value.partyBCreditCode(), value.contractAmount(),
                    value.amountInWords(), value.status(), value.versionNo(), value.createdAt(),
                    value.updatedAt());
        }
    }

    /**
     * 面向 API 的合同明细响应。
     *
     * @param id 明细唯一标识
     * @param itemKey 明细业务键
     * @param itemName 明细名称
     * @param quantity 数量
     * @param unitPrice 单价
     * @param declaredAmount 声明行金额
     * @param sortNo 排序号
     */
    public record LineItemResponse(
            UUID id,
            String itemKey,
            String itemName,
            com.fuquan.audit.contract.domain.ContractLineItemBasis basis,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal declaredAmount,
            int sortNo) {

        /**
         * 将合同明细领域对象映射为 API DTO。
         *
         * @param item 合同明细领域对象
         * @return 明细响应 DTO
         */
        static LineItemResponse from(com.fuquan.audit.contract.domain.ContractLineItem item) {
            return new LineItemResponse(
                    item.id(), item.itemKey(), item.itemName(), item.basis(), item.quantity(), item.unitPrice(),
                    item.declaredAmount().amount(), item.sortNo());
        }
    }

    /**
     * 面向 API 的中标依据响应。
     *
     * @param id 中标依据唯一标识
     * @param noticeNo 中标通知书编号
     * @param projectName 项目名称
     * @param winningBidder 中标供应商
     * @param winningBidderCreditCode 中标供应商统一社会信用代码
     * @param winningAmount 中标金额
     * @param winningDate 中标日期
     * @param sourceRef 来源引用
     */
    public record AwardNoticeResponse(
            UUID id,
            String noticeNo,
            String projectName,
            String winningBidder,
            String winningBidderCreditCode,
            BigDecimal winningAmount,
            LocalDate winningDate,
            String sourceRef) {

        /**
         * 将中标依据领域对象映射为 API DTO。
         *
         * @param award 中标依据领域对象
         * @return 中标依据响应 DTO
         */
        static AwardNoticeResponse from(com.fuquan.audit.contract.domain.AwardNoticeReference award) {
            return new AwardNoticeResponse(
                    award.id(), award.noticeNo(), award.projectName(), award.winningBidder(),
                    award.winningBidderCreditCode(), award.winningAmount().amount(),
                    award.winningDate(), award.sourceRef());
        }
    }

    /**
     * 面向 API 的 F02 审查汇总响应。
     *
     * @param taskId 审查任务标识
     * @param status 任务状态
     * @param reviewType 审查类型
     * @param overallRisk 总体风险
     * @param blockSign 是否阻断签署
     * @param replayed 是否由幂等历史结果重放
     * @param findings 全部检查结论
     */
    public record ReviewResponse(
            UUID taskId,
            String status,
            String reviewType,
            String overallRisk,
            boolean blockSign,
            boolean replayed,
            List<FindingResponse> findings) {

        /**
         * 将应用层审查结果映射为 API DTO。
         *
         * @param result 完整应用层审查结果
         * @return F02 审查响应 DTO
         */
        static ReviewResponse from(ContractReviewResult result) {
            return new ReviewResponse(
                    result.task().id(), result.task().status().name(), result.task().reviewType(),
                    result.overallRisk().name(), result.blockSign(), result.replayed(),
                    result.findings().stream().map(FindingResponse::from).toList());
        }
    }

    /**
     * 面向 API 的单条确定性检查结论。
     *
     * @param id 持久化结论标识
     * @param ruleCode 规则编码
     * @param itemKey 关联明细键
     * @param checkStatus 检查状态
     * @param riskLevel 风险等级
     * @param blockSign 是否阻断签署
     * @param actualValue 实际值
     * @param expectedValue 期望值
     * @param detail 结论说明
     * @param ruleVersion 规则版本
     * @param createdAt 结论形成时间
     */
    public record FindingResponse(
            UUID id,
            String ruleCode,
            String itemKey,
            String checkStatus,
            String riskLevel,
            boolean blockSign,
            String actualValue,
            String expectedValue,
            String detail,
            long ruleVersion,
            Instant createdAt) {

        /**
         * 将公共审查结果实体映射为 API DTO。
         *
         * @param finding 持久化检查结论
         * @return 单条检查响应 DTO
         */
        static FindingResponse from(AuditCheckResult finding) {
            return new FindingResponse(
                    finding.id(), finding.ruleCode(), finding.itemKey(), finding.checkStatus().name(),
                    finding.riskLevel().name(), finding.blockSign(), finding.actualValue(),
                    finding.expectedValue(), finding.detail(), finding.ruleVersion(), finding.createdAt());
        }
    }

    /**
     * 合同偏差列表响应。
     *
     * @param taskId 产生偏差的审查任务标识
     * @param deviations 非 PASS 检查结论
     */
    public record DeviationResponse(UUID taskId, List<FindingResponse> deviations) {
    }

    /**
     * 合同签署检查清单响应。
     *
     * @param taskId 审查任务标识
     * @param overallRisk 总体风险
     * @param blockSign 是否应阻断签署
     * @param items 逐规则检查项
     */
    public record SignChecklistResponse(
            UUID taskId,
            String overallRisk,
            boolean blockSign,
            List<SignChecklistItem> items) {
    }

    /**
     * 签署清单中的单条规则状态。
     *
     * @param rule 规则编码
     * @param itemKey 关联明细键
     * @param passed 是否通过
     * @param level 风险等级
     * @param detail 结论说明
     * @param blockSign 是否阻断签署
     */
    public record SignChecklistItem(
            String rule,
            String itemKey,
            boolean passed,
            String level,
            String detail,
            boolean blockSign) {

        /**
         * 将持久化检查结论映射为签署清单项。
         *
         * @param finding 持久化检查结论
         * @return 签署清单项
         */
        static SignChecklistItem from(AuditCheckResult finding) {
            return new SignChecklistItem(
                    finding.ruleCode(), finding.itemKey(), finding.checkStatus() == CheckStatus.PASS,
                    finding.riskLevel().name(), finding.detail(), finding.blockSign());
        }
    }
}
