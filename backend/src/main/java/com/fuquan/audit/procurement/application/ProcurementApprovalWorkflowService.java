package com.fuquan.audit.procurement.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementApprovalRepository;
import com.fuquan.audit.procurement.application.port.ProcurementApprovalRepository.Workflow;
import com.fuquan.audit.procurement.application.port.ProcurementApprovalTask;
import com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort;
import com.fuquan.audit.procurement.application.port.ProcurementNotificationPort;
import com.fuquan.audit.procurement.domain.ProcurementApprovalInstance;
import com.fuquan.audit.procurement.domain.ProcurementApprovalNode;
import com.fuquan.audit.procurement.domain.ProcurementApprovalOperation;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.procurement.domain.ProcurementNotification;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

/** 采购需求审批流程用例。规则状态由流程服务维护，操作日志只追加不覆盖。 */
@Service
public class ProcurementApprovalWorkflowService {
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String APPROVED = "APPROVED";
    private static final String RETURNED = "RETURNED";
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private final ProcurementApprovalRepository repository;
    private final ProcurementProjectQueryService projectQueryService;
    private final ProcurementProjectCommandPort projectCommandPort;
    private final UserAccountRoleQueryPort userQuery;
    private final ProcurementApprovalProperties properties;
    private final ObjectMapper objectMapper;
    private final ProcurementNotificationPort notificationPort;
    private final ProcurementReviewWorkflowService reviewWorkflowService;

    public ProcurementApprovalWorkflowService(ProcurementApprovalRepository repository,
                                               ProcurementProjectQueryService projectQueryService,
                                               ProcurementProjectCommandPort projectCommandPort) {
        this(repository, projectQueryService, projectCommandPort, null, new ProcurementApprovalProperties(), new ObjectMapper(),
                new ProcurementNotificationPort() {
                    public void save(ProcurementNotification n) {}
                    public List<ProcurementNotification> findByRecipient(String id, boolean unread) { return List.of(); }
                    public void markRead(UUID id, String user, Instant time) {}
                }, null);
    }

    public ProcurementApprovalWorkflowService(ProcurementApprovalRepository repository,
                                               ProcurementProjectQueryService projectQueryService,
                                               ProcurementProjectCommandPort projectCommandPort,
                                               UserAccountRoleQueryPort userQuery,
                                               ProcurementApprovalProperties properties) {
        this(repository, projectQueryService, projectCommandPort, userQuery, properties, new ObjectMapper(), new ProcurementNotificationPort() {
            public void save(ProcurementNotification n) {}
            public List<ProcurementNotification> findByRecipient(String id, boolean unread) { return List.of(); }
            public void markRead(UUID id, String user, Instant time) {}
        }, null);
    }

    @Autowired
    public ProcurementApprovalWorkflowService(ProcurementApprovalRepository repository,
                                               ProcurementProjectQueryService projectQueryService,
                                               ProcurementProjectCommandPort projectCommandPort,
                                               UserAccountRoleQueryPort userQuery,
                                               ProcurementApprovalProperties properties,
                                               ObjectMapper objectMapper,
                                               ProcurementNotificationPort notificationPort,
                                               ProcurementReviewWorkflowService reviewWorkflowService) {
        this.repository = repository;
        this.projectQueryService = projectQueryService;
        this.projectCommandPort = projectCommandPort;
        this.userQuery = userQuery;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.notificationPort = notificationPort;
        this.reviewWorkflowService = reviewWorkflowService;
    }

    @Transactional
    public Workflow start(UUID projectId, CurrentUser operator) {
        return start(projectId, operator, null);
    }

    @Transactional
    public Workflow start(UUID projectId, CurrentUser operator, String modificationNote) {
        if (reviewWorkflowService != null) reviewWorkflowService.ensureReportCompletedIfStarted(projectId);
        var project = projectQueryService.requireById(projectId);
        var previous = repository.findByProjectId(projectId);
        if (previous.isPresent() && !RETURNED.equals(previous.get().instance().status())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "采购项目审批流程已发起");
        }
        if (previous.isPresent() && !"DRAFT".equalsIgnoreCase(project.status())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "已退回项目修改后才能重新发起审批");
        }
        if (previous.isPresent()) {
            if (modificationNote == null || modificationNote.isBlank()) {
                throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "重新发起审批必须填写修改说明");
            }
            if (previous.get().instance().returnSnapshot() == null) {
                throw new BusinessException(BusinessErrorCode.CONFLICT,
                        "历史退回记录缺少项目快照，无法校验修改，请联系管理员处理");
            }
            if (sameProjectSnapshot(previous.get().instance().returnSnapshot(), project)) {
                throw new BusinessException(BusinessErrorCode.CONFLICT,
                        "未检测到针对退回意见的修改，请修改后再提交");
            }
        }
        Instant now = Instant.now();
        UUID instanceId = UUID.randomUUID();
        List<NodeDefinition> definitions = definitionsFor(project.budgetAmount());
        ProcurementApprovalInstance instance = new ProcurementApprovalInstance(
                instanceId, projectId, IN_PROGRESS, definitions.get(0).code(), operator.userId(),
                operator.displayName(), now, null, now, now);
        List<ProcurementApprovalNode> nodes = new ArrayList<>();
        nodes.add(node(instanceId, new NodeDefinition("SUBMISSION", "需求提交", "PROCUREMENT_APPLICANT"),
                1, "COMPLETED", operator, now, now));
        for (int i = 0; i < definitions.size(); i++) {
            NodeDefinition definition = definitions.get(i);
            CurrentUser assignee = resolveAssignee(definition.requiredRoleCode());
            boolean current = i == 0;
            String status = current && (assignee != null || userQuery == null) ? "IN_PROGRESS" : "PENDING";
            nodes.add(node(instanceId, definition, i + 2, status, assignee,
                    current && assignee != null ? now : null, null));
        }
        repository.create(new Workflow(instance, nodes));
        projectCommandPort.updateStatus(projectId, "REVIEWING");
        repository.appendOperation(operation(instance, definitions.get(0).code(), "START", operator,
                modificationNote == null || modificationNote.isBlank() ? "审批流程已发起" : "修改说明：" + modificationNote.trim(),
                null, IN_PROGRESS, now, false, null));
        if (reviewWorkflowService != null) reviewWorkflowService.recordApprovalStartedIfStarted(projectId, "已提交采购审批");
        return new Workflow(instance, nodes);
    }

    @Transactional(readOnly = true)
    public Workflow require(UUID projectId) {
        return repository.findByProjectId(projectId).orElseThrow(() ->
                new BusinessException(BusinessErrorCode.NOT_FOUND, "采购项目审批流程不存在: " + projectId));
    }

    @Transactional
    public Workflow approve(UUID projectId, CurrentUser operator, String opinion) {
        Workflow workflow = require(projectId);
        boolean adminActing = ensureActionable(workflow, operator);
        Instant now = Instant.now();
        ProcurementApprovalNode current = currentNode(workflow);
        List<ProcurementApprovalNode> nodes = new ArrayList<>(workflow.nodes());
        replaceNode(nodes, completed(current, operator, now));
        String from = workflow.instance().status();
        String nextCode = null;
        String instanceStatus = IN_PROGRESS;
        Instant completedTime = null;
        if (current.nodeOrder() < nodes.size()) {
            ProcurementApprovalNode next = nodes.stream()
                    .filter(node -> node.nodeOrder() == current.nodeOrder() + 1).findFirst().orElseThrow();
            replaceNode(nodes, started(next, now));
            nextCode = next.nodeCode();
            projectCommandPort.updateStatus(projectId, projectStatusFor(nextCode));
        } else {
            instanceStatus = APPROVED;
            completedTime = now;
            projectCommandPort.updateStatus(projectId, "APPROVED");
        }
        ProcurementApprovalInstance updated = updatedInstance(workflow.instance(), instanceStatus,
                nextCode, completedTime, now);
        Workflow updatedWorkflow = new Workflow(updated, nodes);
        repository.save(updatedWorkflow);
        repository.appendOperation(operation(updated, current.nodeCode(), "APPROVE", operator,
                normalizeOpinion(opinion, "审批通过"), from, instanceStatus, now, adminActing, current));
        return updatedWorkflow;
    }

    @Transactional
    public Workflow returnForModification(UUID projectId, CurrentUser operator, String opinion) {
        Workflow workflow = require(projectId);
        boolean adminActing = ensureActionable(workflow, operator);
        if (opinion == null || opinion.isBlank()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "退回修改必须填写审批意见");
        }
        Instant now = Instant.now();
        ProcurementApprovalNode current = currentNode(workflow);
        List<ProcurementApprovalNode> nodes = new ArrayList<>(workflow.nodes());
        replaceNode(nodes, new ProcurementApprovalNode(current.id(), current.instanceId(), current.nodeCode(),
                current.nodeName(), current.nodeOrder(), RETURNED, current.requiredRoleCode(),
                current.assigneeUserId(), current.assigneeName(), current.startedTime(), now));
        ProcurementApprovalInstance updated = updatedInstance(workflow.instance(), RETURNED,
                current.nodeCode(), now, now, snapshot(projectQueryService.requireById(projectId)));
        Workflow updatedWorkflow = new Workflow(updated, nodes);
        projectCommandPort.updateStatus(projectId, "DRAFT");
        repository.save(updatedWorkflow);
        repository.appendOperation(operation(updated, current.nodeCode(), "RETURN", operator,
                opinion.trim(), IN_PROGRESS, RETURNED, now, adminActing, current));
        var project = projectQueryService.requireById(projectId);
        notificationPort.save(new ProcurementNotification(UUID.randomUUID(), workflow.instance().startedByUserId(),
                projectId, project.projectName(), "PENDING_MODIFICATION", "采购项目被退回",
                "您的采购项目【" + project.projectName() + "】被退回，退回意见：" + opinion.trim() + "，请修改后重新提交",
                operator.displayName(), opinion.trim(), false, now, null));
        return updatedWorkflow;
    }

    @Transactional
    public Workflow addComment(UUID projectId, CurrentUser operator, String opinion) {
        Workflow workflow = require(projectId);
        boolean adminActing = ensureActionable(workflow, operator);
        if (opinion == null || opinion.isBlank()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "审查意见不能为空");
        }
        Instant now = Instant.now();
        ProcurementApprovalNode current = currentNode(workflow);
        repository.appendOperation(operation(workflow.instance(), current.nodeCode(), "COMMENT", operator,
                opinion.trim(), workflow.instance().status(), workflow.instance().status(), now,
                adminActing, current));
        return workflow;
    }

    @Transactional
    public Workflow reassign(UUID projectId, String nodeCode, String targetUserId,
                             String opinion, CurrentUser operator) {
        requireAdmin(operator);
        Workflow workflow = require(projectId);
        ProcurementApprovalNode current = workflow.nodes().stream()
                .filter(node -> node.nodeCode().equals(nodeCode)).findFirst()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "审批节点不存在: " + nodeCode));
        if ("COMPLETED".equals(current.status()) || "RETURNED".equals(current.status())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "已结束的审批节点不能改派");
        }
        if (userQuery == null) {
            throw new BusinessException(BusinessErrorCode.INTERNAL_ERROR, "审批人查询服务不可用");
        }
        var target = userQuery.findActiveById(targetUserId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "目标审批人不存在或已停用"));
        if (!target.roles().contains(current.requiredRoleCode())) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "目标用户不具备节点所需角色");
        }
        Instant now = Instant.now();
        ProcurementApprovalNode replacement = new ProcurementApprovalNode(current.id(), current.instanceId(),
                current.nodeCode(), current.nodeName(), current.nodeOrder(),
                "PENDING".equals(current.status()) ? "PENDING" : "IN_PROGRESS", current.requiredRoleCode(),
                target.id(), target.realName(), current.startedTime(), current.completedTime());
        List<ProcurementApprovalNode> nodes = new ArrayList<>(workflow.nodes());
        replaceNode(nodes, replacement);
        Workflow updated = new Workflow(updatedInstance(workflow.instance(), workflow.instance().status(),
                workflow.instance().currentNodeCode(), workflow.instance().completedTime(), now), nodes);
        repository.save(updated);
        repository.appendOperation(operation(updated.instance(), current.nodeCode(), "REASSIGN", operator,
                normalizeOpinion(opinion, "管理员改派审批人"), workflow.instance().status(),
                workflow.instance().status(), now, true, current, target.id(), target.realName()));
        return updated;
    }

    @Transactional(readOnly = true)
    public List<ProcurementApprovalOperation> history(UUID projectId) {
        require(projectId);
        return repository.findOperationsByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public List<ProcurementApprovalTask> pendingTasks(String userId) {
        return repository.findPendingTasksByAssignee(userId);
    }

    private boolean ensureActionable(Workflow workflow, CurrentUser operator) {
        if (!IN_PROGRESS.equals(workflow.instance().status())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "当前审批流程不可操作: " + workflow.instance().status());
        }
        if (workflow.instance().startedByUserId().equals(operator.userId())
                && !operator.roles().contains(SYSTEM_ADMIN)) {
            throw new BusinessException(BusinessErrorCode.FORBIDDEN, "发起人不能审批自己发起的采购需求");
        }
        if (operator.roles().contains(SYSTEM_ADMIN)) return true;
        ProcurementApprovalNode current = currentNode(workflow);
        if (current.assigneeUserId() != null && current.assigneeUserId().equals(operator.userId())) return false;
        // Three-argument construction remains a unit-test seam for the pre-assignment workflow.
        if (userQuery == null && (operator.roles().contains("AUDITOR")
                || operator.roles().contains("PROCUREMENT_MANAGER"))) return false;
        if (current.assigneeUserId() == null) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "当前审批节点尚未指派审批人");
        }
        throw new BusinessException(BusinessErrorCode.FORBIDDEN, "当前用户不是该审批节点的审批人");
    }

    private void requireAdmin(CurrentUser operator) {
        if (!operator.roles().contains(SYSTEM_ADMIN)) {
            throw new BusinessException(BusinessErrorCode.FORBIDDEN, "只有系统管理员可以改派审批人");
        }
    }

    private CurrentUser resolveAssignee(String roleCode) {
        if (userQuery == null || roleCode == null) return null;
        return userQuery.findFirstActiveByRole(roleCode)
                .map(account -> new CurrentUser(account.id(), account.username(), account.realName(), account.roles()))
                .orElse(null);
    }

    private static ProcurementApprovalNode currentNode(Workflow workflow) {
        return workflow.nodes().stream()
                .filter(node -> node.nodeCode().equals(workflow.instance().currentNodeCode()))
                .findFirst().orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT, "当前审批节点不存在"));
    }

    private static ProcurementApprovalNode node(UUID instanceId, NodeDefinition definition, int order,
                                                String status, CurrentUser user, Instant started, Instant completed) {
        return new ProcurementApprovalNode(UUID.randomUUID(), instanceId, definition.code(), definition.name(), order,
                status, definition.requiredRoleCode(), user == null ? null : user.userId(),
                user == null ? null : user.displayName(), started, completed);
    }

    private static ProcurementApprovalNode completed(ProcurementApprovalNode node, CurrentUser user, Instant now) {
        return new ProcurementApprovalNode(node.id(), node.instanceId(), node.nodeCode(), node.nodeName(),
                node.nodeOrder(), "COMPLETED", node.requiredRoleCode(), user.userId(), user.displayName(),
                node.startedTime(), now);
    }

    private static ProcurementApprovalNode started(ProcurementApprovalNode node, Instant now) {
        return new ProcurementApprovalNode(node.id(), node.instanceId(), node.nodeCode(), node.nodeName(),
                node.nodeOrder(), node.assigneeUserId() == null ? "PENDING" : "IN_PROGRESS",
                node.requiredRoleCode(), node.assigneeUserId(), node.assigneeName(),
                node.assigneeUserId() == null ? null : now, null);
    }

    private static void replaceNode(List<ProcurementApprovalNode> nodes, ProcurementApprovalNode replacement) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id().equals(replacement.id())) {
                nodes.set(i, replacement);
                return;
            }
        }
        throw new IllegalStateException("approval node not found");
    }

    private static ProcurementApprovalInstance updatedInstance(ProcurementApprovalInstance source, String status,
                                                                String currentNodeCode, Instant completedTime, Instant now) {
        return new ProcurementApprovalInstance(source.id(), source.projectId(), status, currentNodeCode,
                source.startedByUserId(), source.startedByName(), source.startedTime(), completedTime,
                source.createdTime(), now, source.returnSnapshot());
    }

    private static ProcurementApprovalInstance updatedInstance(ProcurementApprovalInstance source, String status,
                                                                String currentNodeCode, Instant completedTime, Instant now,
                                                                String returnSnapshot) {
        return new ProcurementApprovalInstance(source.id(), source.projectId(), status, currentNodeCode,
                source.startedByUserId(), source.startedByName(), source.startedTime(), completedTime,
                source.createdTime(), now, returnSnapshot);
    }

    private String snapshot(ProcurementProject project) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.ofEntries(
                    java.util.Map.entry("projectName", value(project.projectName())),
                    java.util.Map.entry("budgetAmount", value(project.budgetAmount())),
                    java.util.Map.entry("subjectCode", value(project.subjectCode())),
                    java.util.Map.entry("subjectName", value(project.subjectName())),
                    java.util.Map.entry("procurementMethod", value(project.procurementMethod())),
                    java.util.Map.entry("requirementDescription", value(project.requirementDescription())),
                    java.util.Map.entry("departmentName", value(project.departmentName())),
                    java.util.Map.entry("applicantName", value(project.applicantName()))));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot serialize returned project snapshot", exception);
        }
    }

    private boolean sameProjectSnapshot(String snapshot, ProcurementProject project) {
        try {
            var oldValues = objectMapper.readValue(snapshot, new TypeReference<java.util.Map<String, Object>>() {});
            var currentValues = objectMapper.readValue(snapshot(project), new TypeReference<java.util.Map<String, Object>>() {});
            return oldValues.equals(currentValues);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot compare returned project snapshot", exception);
        }
    }

    private static Object value(Object value) { return value == null ? "" : value; }

    private static ProcurementApprovalOperation operation(ProcurementApprovalInstance instance, String nodeCode,
                                                          String type, CurrentUser operator, String opinion,
                                                          String from, String to, Instant now, boolean adminActing,
                                                          ProcurementApprovalNode original) {
        return operation(instance, nodeCode, type, operator, opinion, from, to, now, adminActing, original,
                null, null);
    }

    private static ProcurementApprovalOperation operation(ProcurementApprovalInstance instance, String nodeCode,
                                                          String type, CurrentUser operator, String opinion,
                                                          String from, String to, Instant now, boolean adminActing,
                                                          ProcurementApprovalNode original, String newUserId, String newName) {
        return new ProcurementApprovalOperation(UUID.randomUUID(), instance.id(), instance.projectId(), nodeCode,
                type, operator.userId(), operator.displayName(), opinion, from, to, now, now,
                original == null ? null : original.assigneeUserId(), original == null ? null : original.assigneeName(),
                newUserId, newName, adminActing);
    }

    private static String projectStatusFor(String nodeCode) {
        return "BUDGET_REVIEW".equals(nodeCode) || "FINANCE_DEPARTMENT_APPROVAL".equals(nodeCode)
                ? "BUDGET_REVIEW" : "REVIEWING";
    }

    private static String normalizeOpinion(String opinion, String fallback) {
        return opinion == null || opinion.isBlank() ? fallback : opinion.trim();
    }

    private List<NodeDefinition> definitionsFor(BigDecimal amount) {
        if (userQuery == null) {
            return List.of(
                    new NodeDefinition("REQUIREMENT_REVIEW", "需求审查", "AUDITOR"),
                    new NodeDefinition("BUDGET_REVIEW", "预算审核", "AUDITOR"),
                    new NodeDefinition("LEADERSHIP_APPROVAL", "领导审批", "AUDITOR"));
        }
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        List<NodeDefinition> definitions = new ArrayList<>();
        definitions.add(new NodeDefinition("REQUIREMENT_REVIEW", "需求审查", "PROCUREMENT_REVIEWER"));
        definitions.add(new NodeDefinition("BUDGET_REVIEW", "预算审核", "FINANCE_REVIEWER"));
        if (value.compareTo(properties.requestApprovalThreshold()) >= 0) {
            definitions.add(new NodeDefinition("REQUEST_APPROVAL", "请示报批", "PROCUREMENT_LEADER"));
        }
        if (value.compareTo(properties.committeeThreshold()) >= 0) {
            definitions.add(new NodeDefinition("COMMITTEE_REVIEW", "总支会审议", "PROCUREMENT_COMMITTEE"));
        }
        if (value.compareTo(properties.requestApprovalThreshold()) >= 0
                && value.compareTo(properties.cityLeadershipThreshold()) < 0) {
            definitions.add(new NodeDefinition("FINANCE_DEPARTMENT_APPROVAL", "财处审批", "FINANCE_REVIEWER"));
        } else if (value.compareTo(properties.cityLeadershipThreshold()) >= 0
                && value.compareTo(properties.partyCommitteeThreshold()) < 0) {
            definitions.add(new NodeDefinition("LEADERSHIP_APPROVAL", "市局领导审批", "PROCUREMENT_LEADER"));
        } else if (value.compareTo(properties.partyCommitteeThreshold()) >= 0) {
            definitions.add(new NodeDefinition("PARTY_COMMITTEE_REVIEW", "党委会审议", "PROCUREMENT_COMMITTEE"));
        }
        return definitions;
    }

    private record NodeDefinition(String code, String name, String requiredRoleCode) {}
}
