package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementReviewWorkflowRepository;
import com.fuquan.audit.procurement.domain.ProcurementReviewStep;
import com.fuquan.audit.procurement.domain.ProcurementReviewWorkflow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates completion state only; individual F03/F06/F10/F11 services remain their facts of record. */
@Service
public class ProcurementReviewWorkflowService {
    public static final String QUOTE_UPLOAD = "QUOTE_UPLOAD";
    public static final String OVER_BUDGET = "OVER_BUDGET";
    public static final String QUOTE_PATTERN = "QUOTE_PATTERN";
    public static final String BLACKLIST = "BLACKLIST";
    public static final String TENDER_DOCUMENT = "TENDER_DOCUMENT";
    public static final String COLLUSION = "COLLUSION";
    public static final String REPORT = "REPORT";
    public static final String APPROVAL = "APPROVAL";

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(QUOTE_UPLOAD, 1, "询比价"),
            new Definition(OVER_BUDGET, 2, "超预算"),
            new Definition(QUOTE_PATTERN, 3, "报价规律"),
            new Definition(BLACKLIST, 4, "黑名单"),
            new Definition(TENDER_DOCUMENT, 5, "招标文件"),
            new Definition(COLLUSION, 6, "围串标"),
            new Definition(REPORT, 7, "审查报告"),
            new Definition(APPROVAL, 8, "提交审批"));

    private final ProcurementProjectQueryService projectQueryService;
    private final ProcurementReviewWorkflowRepository repository;
    private final Clock clock;

    public ProcurementReviewWorkflowService(ProcurementProjectQueryService projectQueryService,
            ProcurementReviewWorkflowRepository repository, Clock clock) {
        this.projectQueryService = projectQueryService;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public State start(UUID projectId) {
        projectQueryService.requireById(projectId);
        repository.lockProject(projectId);
        return repository.findByProjectId(projectId).map(this::toState).orElseGet(() -> {
            Instant now = clock.instant();
            ProcurementReviewWorkflow workflow = new ProcurementReviewWorkflow(UUID.randomUUID(), projectId, 5,
                    ProcurementReviewWorkflow.IN_PROGRESS, now, now);
            List<ProcurementReviewStep> steps = initialSteps();
            repository.create(workflow, steps);
            return new State(workflow, steps);
        });
    }

    @Transactional
    public void lockForSnapshot(UUID projectId) {
        repository.lockProject(projectId);
    }

    @Transactional(readOnly = true)
    public State require(UUID projectId) {
        projectQueryService.requireById(projectId);
        return repository.findByProjectId(projectId).map(this::toState).orElseThrow(() ->
                new BusinessException(BusinessErrorCode.NOT_FOUND, "采购审查流程尚未开始"));
    }

    @Transactional
    public State recordQuoteCheck(UUID projectId, String summary) {
        State started = start(projectId);
        return complete(started, List.of(QUOTE_UPLOAD, OVER_BUDGET, QUOTE_PATTERN), summary);
    }

    @Transactional
    public State recordBlacklistCheck(UUID projectId, String summary) {
        return complete(requireForAction(projectId, BLACKLIST), List.of(BLACKLIST), summary);
    }

    @Transactional
    public State recordTenderReview(UUID projectId, String summary) {
        return complete(start(projectId), List.of(TENDER_DOCUMENT), summary);
    }

    @Transactional
    public State recordCollusionScore(UUID projectId, String summary) {
        return complete(start(projectId), List.of(COLLUSION), summary);
    }

    @Transactional
    public State recordReportGenerated(UUID projectId, String summary) {
        return complete(requireForAction(projectId, REPORT), List.of(REPORT), summary);
    }

    /** Keep direct-report compatibility; applicable auxiliary analysis is never a prerequisite. */
    @Transactional
    public void recordReportGeneratedIfStarted(UUID projectId, String summary) {
        repository.lockProject(projectId);
        repository.findByProjectId(projectId)
                .map(this::toState)
                .ifPresent(state -> complete(requireForAction(state, REPORT), List.of(REPORT), summary));
    }

    /** Only the report service's locked, fully verified material snapshot may use this path. */
    @Transactional
    void recordVerifiedMaterialReport(UUID projectId, String summary) {
        repository.lockProject(projectId);
        repository.findByProjectId(projectId).map(this::toState)
                .ifPresent(state -> complete(state, List.of(REPORT), summary));
    }

    @Transactional
    public State recordApprovalStarted(UUID projectId, String summary) {
        return complete(requireForAction(projectId, APPROVAL), List.of(APPROVAL), summary);
    }

    @Transactional
    public void recordApprovalStartedIfStarted(UUID projectId, String summary) {
        repository.lockProject(projectId);
        repository.findByProjectId(projectId)
                .map(this::toState)
                .ifPresent(state -> complete(requireForAction(state, APPROVAL), List.of(APPROVAL), summary));
    }

    @Transactional(readOnly = true)
    public void ensureReportCompleted(UUID projectId) {
        State state = require(projectId);
        ensureReportCompleted(state);
    }

    @Transactional(readOnly = true)
    public void ensureReportCompletedIfStarted(UUID projectId) {
        repository.findByProjectId(projectId).map(this::toState).ifPresent(this::ensureReportCompleted);
    }

    private void ensureReportCompleted(State state) {
        ProcurementReviewStep report = state.steps().stream()
                .filter(step -> REPORT.equals(step.stepCode())).findFirst().orElseThrow();
        if (!ProcurementReviewStep.COMPLETED.equals(report.status())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "采购审查尚未完成审查报告，不能提交审批");
        }
    }

    private State requireForAction(UUID projectId, String stepCode) {
        repository.lockProject(projectId);
        return requireForAction(require(projectId), stepCode);
    }

    private State requireForAction(State state, String stepCode) {
        if (REPORT.equals(stepCode) && !materialCompleted(state.steps())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "请先完成采购材料分析");
        }
        String prerequisite = switch (stepCode) {
            case BLACKLIST -> QUOTE_UPLOAD;
            case APPROVAL -> REPORT;
            default -> null;
        };
        for (ProcurementReviewStep step : state.steps()) {
            if (!step.stepCode().equals(prerequisite)) continue;
            if (!ProcurementReviewStep.COMPLETED.equals(step.status())) {
                throw new BusinessException(BusinessErrorCode.CONFLICT,
                        "请先完成“" + step.stepName() + "”");
            }
        }
        return state;
    }

    private State complete(State state, List<String> stepCodes, String summary) {
        Instant now = clock.instant();
        List<ProcurementReviewStep> next = new ArrayList<>();
        for (ProcurementReviewStep step : state.steps()) {
            next.add(stepCodes.contains(step.stepCode()) ? step.complete(now, summary) : step);
        }
        int current = mainCurrentOrder(next);
        next.replaceAll(step -> step.stepOrder() == current && !ProcurementReviewStep.COMPLETED.equals(step.status())
                ? step.activate() : step);
        String status = mainStepsCompleted(next)
                ? ProcurementReviewWorkflow.COMPLETED : ProcurementReviewWorkflow.IN_PROGRESS;
        ProcurementReviewWorkflow workflow = new ProcurementReviewWorkflow(state.workflow().id(),
                state.workflow().projectId(), current, status, state.workflow().createdTime(), now);
        repository.update(workflow, next);
        return new State(workflow, next);
    }

    private static List<ProcurementReviewStep> initialSteps() {
        return DEFINITIONS.stream().map(definition -> new ProcurementReviewStep(definition.code(),
                definition.order(), definition.name(), definition.order() == 5
                        ? ProcurementReviewStep.IN_PROGRESS : ProcurementReviewStep.PENDING,
                null, null)).toList();
    }

    private static int orderOf(String stepCode) {
        return DEFINITIONS.stream().filter(definition -> definition.code().equals(stepCode))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown review step: " + stepCode)).order();
    }

    private State toState(ProcurementReviewWorkflowRepository.Snapshot snapshot) {
        // Read old eight-step workflows without migration or inventing completed auxiliary facts.
        var old = snapshot.workflow();
        return new State(new ProcurementReviewWorkflow(old.id(), old.projectId(), mainCurrentOrder(snapshot.steps()),
                mainStepsCompleted(snapshot.steps()) ? ProcurementReviewWorkflow.COMPLETED : ProcurementReviewWorkflow.IN_PROGRESS,
                old.createdTime(), old.updatedTime()), snapshot.steps());
    }

    private static boolean mainStepsCompleted(List<ProcurementReviewStep> steps) {
        return materialCompleted(steps) && steps.stream().filter(s -> List.of(REPORT, APPROVAL).contains(s.stepCode()))
                .allMatch(s -> ProcurementReviewStep.COMPLETED.equals(s.status()));
    }

    private static int mainCurrentOrder(List<ProcurementReviewStep> steps) {
        if (!materialCompleted(steps)) return 5;
        return steps.stream().filter(s -> List.of(REPORT, APPROVAL).contains(s.stepCode()))
                .filter(s -> !ProcurementReviewStep.COMPLETED.equals(s.status())).mapToInt(ProcurementReviewStep::stepOrder)
                .min().orElse(orderOf(APPROVAL));
    }

    private static boolean materialCompleted(List<ProcurementReviewStep> steps) {
        return steps.stream().anyMatch(s -> List.of(TENDER_DOCUMENT, QUOTE_UPLOAD, COLLUSION, REPORT).contains(s.stepCode())
                && ProcurementReviewStep.COMPLETED.equals(s.status()));
    }

    public record State(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps) {
        public State { steps = List.copyOf(steps); }
    }

    private record Definition(String code, int order, String name) {}
}
