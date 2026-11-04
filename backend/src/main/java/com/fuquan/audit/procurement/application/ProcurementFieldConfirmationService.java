package com.fuquan.audit.procurement.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditQueryPort;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 采购预审字段人工确认：事实由当前项目和预审结果生成，客户端不能提交审计快照。 */
@Service
public class ProcurementFieldConfirmationService {
    public static final String ACTION = "CONFIRM_PROCUREMENT_FIELD";

    private final ProcurementAgentReviewSnapshotService reviewSnapshotService;
    private final ProcurementProjectQueryService projectQueryService;
    private final OperationAuditService auditService;
    private final OperationAuditQueryPort auditQuery;
    private final ObjectMapper json;

    public ProcurementFieldConfirmationService(ProcurementAgentReviewSnapshotService reviewSnapshotService,
                                               ProcurementProjectQueryService projectQueryService,
                                               OperationAuditService auditService,
                                               OperationAuditQueryPort auditQuery,
                                               ObjectMapper json) {
        this.reviewSnapshotService = reviewSnapshotService;
        this.projectQueryService = projectQueryService;
        this.auditService = auditService;
        this.auditQuery = auditQuery;
        this.json = json;
    }

    public Confirmation confirm(UUID projectId, String checkType, String note, CurrentUser user) {
        ProcurementProject project = projectQueryService.requireById(projectId);
        ProcurementAgentReviewService.ReviewResult review = reviewSnapshotService.find(projectId)
                .orElseGet(() -> reviewSnapshotService.refresh(projectId));
        ProcurementAgentReviewService.ReviewItem item = review.items().stream()
                .filter(candidate -> candidate.checkType().equals(checkType))
                .findFirst()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        if (!item.needsConfirmation()) {
            throw new BusinessException(BusinessErrorCode.CONFLICT);
        }
        String normalizedNote = optional(note, 1000);
        auditService.successConfirmation(ACTION, user, "PROJECT", projectId.toString(), project.projectName(),
                item.checkType(), item.checkName(), item.actualValue(), item.detail(),
                firstNonBlank(item.aiExplanation(), item.aiSuggestion()), normalizedNote);
        return new Confirmation(user.userId(), user.displayName(), Instant.now(), projectId,
                item.checkType(), item.checkName(), item.actualValue(), item.detail(),
                firstNonBlank(item.aiExplanation(), item.aiSuggestion()), normalizedNote, null);
    }

    public List<Confirmation> find(UUID projectId) {
        return auditQuery.find(null, ACTION, "PROJECT", null, null).stream()
                .filter(event -> projectId.toString().equals(event.resourceId()))
                .map(this::fromAudit)
                .toList();
    }

    private Confirmation fromAudit(OperationAuditEvent event) {
        try {
            JsonNode data = json.readTree(event.afterData());
            return new Confirmation(event.userId(),
                    firstNonBlank(text(data, "confirmerName"), event.username()), event.occurredAt(),
                    UUID.fromString(event.resourceId()),
                    firstNonBlank(event.confirmationFieldCode(), text(data, "fieldCode")),
                    firstNonBlank(event.confirmationFieldName(), text(data, "fieldName")),
                    firstNonBlank(event.confirmationFieldValue(), text(data, "fieldValue")),
                    firstNonBlank(event.confirmationRulePrompt(), text(data, "rulePrompt")),
                    firstNonBlank(event.confirmationAiPrompt(), text(data, "aiPrompt")),
                    firstNonBlank(event.confirmationNote(), text(data, "confirmationNote")), event.requestId());
        } catch (Exception exception) {
            throw new IllegalStateException("invalid procurement field confirmation audit", exception);
        }
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

    public record Confirmation(String confirmerUserId, String confirmerName, Instant confirmedTime,
                               UUID projectId, String fieldCode, String fieldName, String fieldValue,
                               String rulePrompt, String aiPrompt, String confirmationNote,
                               String requestId) {}
}
