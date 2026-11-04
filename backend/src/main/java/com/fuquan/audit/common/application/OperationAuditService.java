package com.fuquan.audit.common.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.security.domain.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class OperationAuditService {
    private final OperationAuditPort port; private final ObjectMapper json;
    public OperationAuditService(OperationAuditPort port, ObjectMapper json) { this.port=port; this.json=json; }
    @Transactional
    public void success(String action, CurrentUser user, String type, String id, String name,
                        Object before, Object after, String description) {
        append(action, user, type, id, name, before, after, description, OperationAuditResultStatus.SUCCESS, null,
                null, null, null, null, null, null);
    }

    @Transactional
    public void successConfirmation(String action, CurrentUser user, String type, String id, String name,
                                    String fieldCode, String fieldName, String fieldValue,
                                    String rulePrompt, String aiPrompt, String confirmationNote) {
        append(action, user, type, id, name, null,
                java.util.Map.of("fieldCode", fieldCode, "fieldName", fieldName,
                        "confirmerName", user == null ? "" : user.displayName(),
                        "fieldValue", fieldValue == null ? "" : fieldValue,
                        "rulePrompt", rulePrompt == null ? "" : rulePrompt,
                        "aiPrompt", aiPrompt == null ? "" : aiPrompt,
                        "confirmationNote", confirmationNote == null ? "" : confirmationNote),
                "确认采购预审字段合理性", OperationAuditResultStatus.SUCCESS, null,
                fieldCode, fieldName, fieldValue, rulePrompt, aiPrompt, confirmationNote);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(String action, CurrentUser user, String type, String id, String name, String reason) {
        append(action, user, type, id, name, null, null, "关键操作失败", OperationAuditResultStatus.FAILURE, reason,
                null, null, null, null, null, null);
    }
    private void append(String action, CurrentUser user, String type, String id, String name, Object before, Object after,
                        String description, OperationAuditResultStatus status, String failure,
                        String confirmationFieldCode, String confirmationFieldName, String confirmationFieldValue,
                        String confirmationRulePrompt, String confirmationAiPrompt, String confirmationNote) {
        port.append(new OperationAuditEvent(action, module(type), action, user == null ? null : user.userId(),
                user == null ? null : user.username(), type, id, null, status, status.name(), description, null,
                MDC.get(RequestIdFilter.MDC_KEY), MDC.get("traceId"), sourceIp(), Instant.now(), name,
                encode(before), encode(after), failure, confirmationFieldCode, confirmationFieldName,
                confirmationFieldValue, confirmationRulePrompt, confirmationAiPrompt, confirmationNote));
    }
    private String encode(Object value) { if (value == null) return null; try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("cannot serialize operation audit data", e); } }
    private static String module(String type) {
        if ("USER".equals(type)) return "SECURITY";
        if ("CONTRACT_ANALYSIS_CASE".equals(type)) return "CONTRACT";
        return java.util.Set.of("BUDGET_REVIEW_TASK","BUDGET_MATERIAL_BATCH","BUDGET_MATERIAL_RISK_RESULT").contains(type) ? "BUDGET" : "PROCUREMENT";
    }
    private static String sourceIp() { var a=RequestContextHolder.getRequestAttributes(); if (a instanceof ServletRequestAttributes s) {
        HttpServletRequest r=s.getRequest(); String forwarded=r.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? r.getRemoteAddr() : forwarded.split(",")[0].trim(); } return null; }
}
