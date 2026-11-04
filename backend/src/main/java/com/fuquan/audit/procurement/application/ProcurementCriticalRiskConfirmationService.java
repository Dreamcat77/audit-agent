package com.fuquan.audit.procurement.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditQueryPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.procurement.application.port.ProcurementReviewReportRepository;
import com.fuquan.audit.procurement.domain.ProcurementReviewReport;
import com.fuquan.audit.security.domain.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 严重风险报告人工确认，以长期操作审计作为可重开的业务事实。 */
@Service
public class ProcurementCriticalRiskConfirmationService {

    public static final String ACTION = "CONFIRM_PROCUREMENT_CRITICAL_RISK";
    public static final String RESOURCE_TYPE = "PROCUREMENT_REVIEW_REPORT";
    public static final String IDEMPOTENCY_SCOPE = "PROCUREMENT_CRITICAL_RISK_CONFIRMATION";

    private final ProcurementReviewReportRepository reportRepository;
    private final ProcurementProjectQueryService projectQueryService;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final OperationAuditQueryPort auditQuery;
    private final ObjectMapper json;
    private final Clock clock;

    public ProcurementCriticalRiskConfirmationService(
            ProcurementReviewReportRepository reportRepository,
            ProcurementProjectQueryService projectQueryService,
            IdempotencyPort idempotency,
            OperationAuditPort audit,
            OperationAuditQueryPort auditQuery,
            ObjectMapper json,
            Clock clock) {
        this.reportRepository = reportRepository;
        this.projectQueryService = projectQueryService;
        this.idempotency = idempotency;
        this.audit = audit;
        this.auditQuery = auditQuery;
        this.json = json;
        this.clock = clock;
    }

    public Optional<Confirmation> find(UUID projectId, UUID reportId) {
        requireLatestReport(projectId, reportId);
        return findAudit(reportId).map(this::fromAudit);
    }

    /**
     * 同一报告使用确定性幂等键，确保多实例并发时也只写入一条确认事实。
     */
    @Transactional
    public synchronized Confirmation confirm(
            UUID projectId,
            UUID reportId,
            String idempotencyKey,
            String note,
            CurrentUser user) {
        ProcurementReviewReport report = requireLatestReport(projectId, reportId);
        if (!"CRITICAL".equals(report.riskLevel())) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "仅严重风险采购审查报告需要人工确认");
        }
        Optional<OperationAuditEvent> existing = findAudit(reportId);
        if (existing.isPresent()) return fromAudit(existing.get());

        Instant now = clock.instant();
        String requestId = safe(MDC.get(RequestIdFilter.MDC_KEY));
        String traceId = safeTraceId(requestId);
        String fingerprint = fingerprint(report);
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(
                IDEMPOTENCY_SCOPE,
                report.id().toString(),
                user.userId(),
                RESOURCE_TYPE,
                report.id().toString(),
                fingerprint,
                RESOURCE_TYPE,
                report.id().toString(),
                requestId,
                traceId,
                now));
        if (!claim.acquired()) {
            return replay(claim.record(), report, fingerprint);
        }

        String confirmedContent = report.overallConclusion() == null || report.overallConclusion().isBlank()
                ? "已知悉该采购审查报告被判定为严重风险"
                : report.overallConclusion();
        String normalizedNote = optional(note, 1000);
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("projectId", projectId.toString());
        facts.put("reportId", report.id().toString());
        facts.put("reportNumber", report.reportNumber());
        facts.put("riskLevel", report.riskLevel());
        facts.put("confirmedContent", confirmedContent);
        facts.put("confirmerName", user.displayName());
        facts.put("idempotencyKey", idempotencyKey);
        facts.put("confirmationNote", normalizedNote == null ? "" : normalizedNote);

        try {
            OperationAuditEvent event = new OperationAuditEvent(
                    ACTION,
                    "PROCUREMENT",
                    ACTION,
                    user.userId(),
                    user.username(),
                    RESOURCE_TYPE,
                    report.id().toString(),
                    null,
                    OperationAuditResultStatus.SUCCESS,
                    "CONFIRMED",
                    "确认采购审查严重风险",
                    null,
                    requestId,
                    traceId,
                    sourceIp(),
                    now,
                    report.reportNumber(),
                    null,
                    encode(facts),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    normalizedNote);
            audit.append(event);
            idempotency.markSucceeded(
                    claim.record(), RESOURCE_TYPE, report.id().toString(), now);
            return fromAudit(event);
        } catch (RuntimeException failure) {
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }

    private Confirmation replay(
            IdempotencyRecord record,
            ProcurementReviewReport report,
            String fingerprint) {
        if (!IDEMPOTENCY_SCOPE.equals(record.scope())
                || !RESOURCE_TYPE.equals(record.resourceType())
                || !report.id().toString().equals(record.resourceId())
                || !fingerprint.equals(record.requestFingerprint())) {
            throw new IllegalStateException("critical risk confirmation idempotency identity mismatch");
        }
        if (record.status() != IdempotencyStatus.SUCCEEDED) {
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT,
                    record.status() == IdempotencyStatus.IN_PROGRESS
                            ? "严重风险确认正在处理中，请稍后重试"
                            : "严重风险确认上次未完成，请联系管理员核查");
        }
        return findAudit(report.id())
                .map(this::fromAudit)
                .orElseThrow(() -> new IllegalStateException(
                        "succeeded critical risk confirmation has no durable audit fact"));
    }

    private ProcurementReviewReport requireLatestReport(UUID projectId, UUID reportId) {
        projectQueryService.requireById(projectId);
        ProcurementReviewReport report = reportRepository.findLatestByProjectId(projectId)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "采购审查报告不存在: " + projectId));
        if (!report.id().equals(reportId)) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "采购审查报告已更新，请刷新后重新确认");
        }
        return report;
    }

    private Optional<OperationAuditEvent> findAudit(UUID reportId) {
        return auditQuery.find(null, ACTION, RESOURCE_TYPE, null, null).stream()
                .filter(event -> reportId.toString().equals(event.resourceId()))
                .findFirst();
    }

    private Confirmation fromAudit(OperationAuditEvent event) {
        try {
            JsonNode data = json.readTree(event.afterData());
            return new Confirmation(
                    UUID.fromString(text(data, "projectId")),
                    UUID.fromString(event.resourceId()),
                    firstNonBlank(text(data, "reportNumber"), event.resourceName()),
                    firstNonBlank(text(data, "riskLevel"), "CRITICAL"),
                    text(data, "confirmedContent"),
                    event.userId(),
                    firstNonBlank(text(data, "confirmerName"), event.username()),
                    event.occurredAt(),
                    optional(text(data, "confirmationNote"), 1000),
                    event.requestId());
        } catch (Exception exception) {
            throw new IllegalStateException("invalid procurement critical risk confirmation audit", exception);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot serialize critical risk confirmation", exception);
        }
    }

    private static String fingerprint(ProcurementReviewReport report) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[] {
                    report.id().toString(),
                    report.projectId().toString(),
                    report.reportNumber(),
                    report.riskLevel(),
                    report.overallConclusion() == null ? "" : report.overallConclusion()
            }) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String safeTraceId(String requestId) {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? requestId : traceId;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private static String sourceIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            return forwarded == null || forwarded.isBlank()
                    ? request.getRemoteAddr()
                    : forwarded.split(",")[0].trim();
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    public record Confirmation(
            UUID projectId,
            UUID reportId,
            String reportNumber,
            String riskLevel,
            String confirmedContent,
            String confirmerUserId,
            String confirmerName,
            Instant confirmedTime,
            String confirmationNote,
            String requestId) {}
}
