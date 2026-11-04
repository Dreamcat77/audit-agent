package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.BudgetReviewItemRepository;
import com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository;
import com.fuquan.audit.budget.application.port.BudgetCategoryRuleRepository;
import com.fuquan.audit.budget.application.port.BudgetProjectConfigRepository;
import com.fuquan.audit.budget.application.port.BudgetSubjectMappingRepository;
import com.fuquan.audit.budget.application.port.BudgetValidationConfirmLogRepository;
import com.fuquan.audit.budget.application.port.BudgetMaterialParser;
import com.fuquan.audit.budget.application.port.BudgetMaterialStoragePort;
import com.fuquan.audit.blacklist.application.port.BlacklistCheckPort;
import com.fuquan.audit.budget.application.port.BudgetYearValidityConfigRepository;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisResult;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort.BudgetSemanticAnalysisItem;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisUseCase;
import com.fuquan.audit.budget.domain.BudgetProjectConfig;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.budget.domain.BudgetCategoryRule;
import com.fuquan.audit.budget.domain.BudgetSubjectMapping;
import com.fuquan.audit.budget.domain.BudgetValidationConfirmLog;
import com.fuquan.audit.budget.domain.BudgetYearValidityConfig;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class BudgetReviewService {

    public static final long MAX_FILE_SIZE = 500L * 1024 * 1024;
    private static final int FUZZY_DISTANCE_LIMIT = 2;
    private static final Pattern SUBJECT_CODE_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{2,19}$");
    private static final Pattern NUMERIC_PATTERN =
            Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    private final BudgetReviewTaskRepository taskRepository;
    private final BudgetReviewItemRepository itemRepository;
    private final BudgetSubjectMappingRepository mappingRepository;
    private final BudgetProjectConfigRepository projectConfigRepository;
    private final BudgetYearValidityConfigRepository yearValidityConfigRepository;
    private final BudgetCategoryRuleRepository categoryRuleRepository;
    private final BudgetValidationConfirmLogRepository confirmLogRepository;
    private final BlacklistCheckPort blacklistCheckPort;
    private final BudgetSemanticAnalysisUseCase budgetSemanticAnalysisUseCase;
    private final List<BudgetMaterialParser> parsers;
    private final FlowDebugLogger flowDebugLogger;
    private final BudgetMaterialStoragePort materialStorage;
    private final BudgetReviewTransactionCoordinator transactionCoordinator;
    private com.fuquan.audit.budget.application.port.BudgetMaterialJobRepository materialJobs;

    @Autowired
    public void setMaterialJobs(com.fuquan.audit.budget.application.port.BudgetMaterialJobRepository jobs) { this.materialJobs=jobs; }

    public void validateMaterial(String fileName,long fileSize) {
        validateFile(fileName,fileSize);
        if(parsers.stream().noneMatch(p->p.supports(fileName,null)))
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,"请上传 Word、Excel、PDF、PPT、文本或常见图片预算材料");
    }

    /** Parse a durably stored task; the caller fences the short result commit. */
    public BudgetReviewTask parseStored(BudgetReviewTask task) throws IOException {
        return prepareStored(task).task();
    }

    public record PreparedMaterial(BudgetReviewTask task,List<BudgetMaterialLine> lines) {}
    public PreparedMaterial prepareStored(BudgetReviewTask task) throws IOException {
        var parser=parsers.stream().filter(p->p.supports(task.fileName(),task.contentType())).findFirst()
            .orElseThrow(()->new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,"不支持的预算材料类型"));
        try(var span=PipelineDiagnostics.start("BUDGET","BACKGROUND_DOCUMENT_PARSE",task.id(),Map.of("inputBytes",task.fileSize()));
            var input=materialStorage.open(task.tempFilePath())) {
            var lines=parser.parse(input,task.fileName());
            if(lines.isEmpty()) throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,"未提取到预算明细");
            span.success(Map.of("rowCount",lines.size()));
            return new PreparedMaterial(task.withStatus("PARSED",lines.size(),null),List.copyOf(lines));
        }
    }

    @Autowired
    public BudgetReviewService(
            BudgetReviewTaskRepository taskRepository,
            BudgetReviewItemRepository itemRepository,
            BudgetSubjectMappingRepository mappingRepository,
            List<BudgetMaterialParser> parsers,
            FlowDebugLogger flowDebugLogger,
            BudgetValidationConfirmLogRepository confirmLogRepository,
            BudgetCategoryRuleRepository categoryRuleRepository,
            BudgetProjectConfigRepository projectConfigRepository,
            BudgetYearValidityConfigRepository yearValidityConfigRepository,
            BlacklistCheckPort blacklistCheckPort,
            BudgetSemanticAnalysisUseCase budgetSemanticAnalysisUseCase,
            BudgetMaterialStoragePort materialStorage,
            BudgetReviewTransactionCoordinator transactionCoordinator) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.mappingRepository = mappingRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.yearValidityConfigRepository = yearValidityConfigRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.parsers = parsers;
        this.flowDebugLogger = flowDebugLogger;
        this.confirmLogRepository = confirmLogRepository;
        this.blacklistCheckPort = blacklistCheckPort;
        this.budgetSemanticAnalysisUseCase = budgetSemanticAnalysisUseCase;
        this.materialStorage = materialStorage;
        this.transactionCoordinator = transactionCoordinator;
    }

    /**
     * Compatibility constructor for unit tests and non-Spring callers.
     */
    public BudgetReviewService(
            BudgetReviewTaskRepository taskRepository,
            BudgetReviewItemRepository itemRepository,
            BudgetSubjectMappingRepository mappingRepository,
            List<BudgetMaterialParser> parsers,
            FlowDebugLogger flowDebugLogger,
            BudgetValidationConfirmLogRepository confirmLogRepository,
            BudgetCategoryRuleRepository categoryRuleRepository,
            BudgetProjectConfigRepository projectConfigRepository,
            BudgetYearValidityConfigRepository yearValidityConfigRepository,
            BlacklistCheckPort blacklistCheckPort,
            BudgetSemanticAnalysisUseCase budgetSemanticAnalysisUseCase,
            BudgetMaterialStoragePort materialStorage) {
        this(
                taskRepository,
                itemRepository,
                mappingRepository,
                parsers,
                flowDebugLogger,
                confirmLogRepository,
                categoryRuleRepository,
                projectConfigRepository,
                yearValidityConfigRepository,
                blacklistCheckPort,
                budgetSemanticAnalysisUseCase,
                materialStorage,
                new BudgetReviewTransactionCoordinator(
                        taskRepository,
                        itemRepository,
                        mappingRepository,
                        categoryRuleRepository,
                        projectConfigRepository,
                        yearValidityConfigRepository,
                        confirmLogRepository));
    }

    public BudgetReviewTask uploadAndParse(
            String fileName, String contentType, long fileSize, InputStream inputStream) {
        return uploadAndParse(fileName, contentType, fileSize, inputStream, null, null);
    }

    public BudgetReviewTask uploadAndParse(
            String fileName,
            String contentType,
            long fileSize,
            InputStream inputStream,
            String budgetYear,
            String departmentCode) {
        validateFile(fileName, fileSize);
        BudgetReviewTask task = new BudgetReviewTask(
                UUID.randomUUID(), fileName, contentType, fileSize, null, "UPLOADED",
                0, null, budgetYear, departmentCode, Instant.now(), Instant.now());
        PipelineDiagnostics.run("BUDGET", "DB_UPLOAD_START", task.id(), Map.of("inputBytes", fileSize),
                () -> { taskRepository.save(task); return null; });
        try {
            BudgetMaterialParser parser = parsers.stream()
                    .filter(candidate -> candidate.supports(fileName, contentType))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(
                            BusinessErrorCode.INVALID_ARGUMENT,
                            "请上传 Word、Excel、PDF、PPT、文本或常见图片预算材料"));
            BudgetMaterialStoragePort.StoredMaterial storedMaterial;
            try (var span = PipelineDiagnostics.start("BUDGET", "STORAGE_STORE", task.id(),
                    Map.of("inputBytes", fileSize, "byteLimit", MAX_FILE_SIZE))) {
                try {
                    try (InputStream source = inputStream) {
                        storedMaterial = materialStorage.store(source, fileName, MAX_FILE_SIZE);
                    }
                    span.success(Map.of());
                } catch (IOException | RuntimeException failure) { span.fail(failure); throw failure; }
            }
            List<BudgetMaterialLine> lines;
            try (var span = PipelineDiagnostics.start("BUDGET", "DOCUMENT_PARSE", task.id(), Map.of("inputBytes", fileSize))) {
                try {
                    try (InputStream stored = materialStorage.open(storedMaterial.reference())) {
                        lines = parser.parse(stored, fileName);
                    }
                    span.success(Map.of("rowCount", lines.size()));
                } catch (IOException | RuntimeException error) { span.fail(error); throw error; }
            }
            BudgetReviewTask parsed = new BudgetReviewTask(
                    task.id(), task.fileName(), task.contentType(), task.fileSize(),
                    storedMaterial.reference(), "PARSED", lines.size(), null,
                    task.budgetYear(), task.departmentCode(), task.createdTime(), Instant.now());
            PipelineDiagnostics.run("BUDGET", "DB_PARSE_RESULT", task.id(), Map.of("rowCount", lines.size()),
                    () -> { taskRepository.save(parsed); return null; });
            flowDebugLogger.log("F01_MATERIAL_PARSED", "BUDGET", "APPLICATION", "parse",
                    Map.of("taskId", task.id().toString(), "lineCount", lines.size()));
            return parsed;
        } catch (BusinessException exception) {
            markFailed(task, exception.getMessage());
            throw exception;
        } catch (IOException exception) {
            markFailed(task, exception.getMessage());
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "预算材料解析失败: " + exception.getMessage(),
                    exception);
        }
    }

    public BudgetReviewTask review(UUID taskId) {
        if(materialJobs!=null && materialJobs.find(taskId).filter(j->java.util.Set.of("QUEUED","PROCESSING").contains(j.status())).isPresent())
            throw new BusinessException(BusinessErrorCode.CONFLICT,"预算材料正在后台处理");
        return review(taskId,()->{});
    }

    public BudgetReviewTask review(UUID taskId, Runnable verifyLease) {
        return review(taskId, verifyLease, false);
    }

    /** Background reference report: retain exceptions without acknowledging them. */
    public BudgetReviewTask reviewReference(UUID taskId, Runnable verifyLease) {
        return review(taskId, verifyLease, true);
    }

    /** Keep one extraction through this attempt. Recovery reopens the original durable source. */
    public BudgetReviewTask reviewPreparedReference(UUID taskId,Runnable verifyLease,PreparedMaterial material) {
        if(!taskId.equals(material.task().id()))throw new IllegalArgumentException("prepared material identity mismatch");
        return review(taskId,verifyLease,true,material.lines());
    }

    private BudgetReviewTask review(UUID taskId, Runnable verifyLease, boolean reference) {
        return review(taskId,verifyLease,reference,null);
    }
    private BudgetReviewTask review(UUID taskId,Runnable verifyLease,boolean reference,List<BudgetMaterialLine> prepared) {
        BudgetReviewTransactionCoordinator.ReviewSnapshot snapshot =
                PipelineDiagnostics.run("BUDGET", "DB_REVIEW_LOAD", taskId, Map.of(),
                        () -> transactionCoordinator.loadReview(taskId));
        BudgetReviewTask task = snapshot.task();
        if ("REVIEWED".equals(task.status()) || "WAITING_CONFIRMATION".equals(task.status())) {
            return task;
        }
        String materialReference = task.tempFilePath();
        if (materialReference == null || !materialStorage.exists(materialReference)) {
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT,
                    "预算材料解析结果已失效，请重新上传文件");
        }
        BudgetMaterialParser parser = parsers.stream()
                .filter(candidate -> candidate.supports(task.fileName(), task.contentType()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.INVALID_ARGUMENT,
                        "无法确定预算材料解析器"));
        List<BudgetMaterialLine> lines = prepared==null?parseStoredLines(task, parser, materialReference):prepared;
        if(lines.size()!=task.parsedLineCount())throw new BusinessException(BusinessErrorCode.CONFLICT,"预算明细重新读取数量不一致，停止处理以避免漏行");
        try {
            return processReview(
                    task, lines, snapshot.mappings(), snapshot.categoryRules(),
                    snapshot.projectConfigs(), snapshot.yearValidityConfigs(),
                    snapshot.existingItems(), verifyLease, reference);
        } catch (RuntimeException failure) {
            PipelineDiagnostics.run("BUDGET", "DB_REVIEW_FAILURE_COMMIT", task.id(), Map.of(),
                    () -> { transactionCoordinator.fail(task, failure.getMessage(), verifyLease); return null; });
            throw failure;
        }
    }

    public ConfirmationProgress confirmRow(
            UUID taskId,
            int rowNumber,
            String remark,
            String confirmerUserId,
            String confirmerName) {
        return confirmExceptionInternal(
                taskId, rowNumber, null, remark, confirmerUserId, confirmerName, true);
    }

    public ConfirmationProgress confirmException(
            UUID taskId,
            int rowNumber,
            String confirmStep,
            String abnormalReason,
            String remark,
            String confirmerUserId,
            String confirmerName) {
        return confirmExceptionInternal(
                taskId, rowNumber, confirmStep, remark, confirmerUserId, confirmerName, false);
    }

    private ConfirmationProgress confirmExceptionInternal(
            UUID taskId,
            int rowNumber,
            String confirmStep,
            String remark,
            String confirmerUserId,
            String confirmerName,
            boolean moduleLevelLog) {
        if(materialJobs!=null && materialJobs.find(taskId).filter(j->java.util.Set.of("QUEUED","PROCESSING").contains(j.status())).isPresent())
            throw new BusinessException(BusinessErrorCode.CONFLICT,"预算后台结果正在保存，请稍后确认");
        BudgetReviewTransactionCoordinator.ReviewSnapshot snapshot =
                transactionCoordinator.loadReview(taskId);
        BudgetReviewTask task = snapshot.task();
        validateRequestedConfirmationStep(confirmStep, task.pendingStep(), moduleLevelLog);
        boolean alreadyConfirmed = hasConfirmationLog(taskId, rowNumber);
        if (!"WAITING_CONFIRMATION".equals(task.status())) {
            if (alreadyConfirmed && "REVIEWED".equals(task.status())) {
                return progressFromTask(task, taskId, rowNumber);
            }
            if (alreadyConfirmed && "PARSED".equals(task.status())) {
                throw new BusinessException(
                        BusinessErrorCode.CONFLICT, "该异常确认已受理，续检处理中，请稍后刷新");
            }
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT, "当前任务没有待确认异常");
        }
        if (!Integer.valueOf(rowNumber).equals(task.pendingRowNumber())) {
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT, "待确认异常已变化，请刷新后重试");
        }
        String materialReference = task.tempFilePath();
        if (materialReference == null || !materialStorage.exists(materialReference)) {
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT, "预算材料解析结果已失效，请重新上传文件");
        }
        BudgetMaterialParser parser = parsers.stream()
                .filter(candidate -> candidate.supports(task.fileName(), task.contentType()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.INVALID_ARGUMENT, "无法确定预算材料解析器"));
        List<BudgetMaterialLine> lines = parseStoredLines(task, parser, materialReference);
        BudgetReviewItem confirmedItem = snapshot.existingItems().stream()
                .filter(item -> Integer.valueOf(rowNumber).equals(item.rowNumber()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "待确认的预算审查明细不存在: " + rowNumber));
        String abnormalSummary = summarizeAbnormalReasons(confirmedItem);
        String confirmedStep = moduleLevelLog
                ? moduleKey(task.pendingStep())
                : task.pendingStep() == null ? "ROW_REVIEW" : task.pendingStep();
        Instant now = Instant.now();
        BudgetReviewTask resumable = task.withReviewState(
                "PARSED", task.reviewCursor(), null, task.pendingStep(), task.pendingReason());
        BudgetReviewTransactionCoordinator.ConfirmationPreparation preparation =
                transactionCoordinator.confirmAndPrepare(
                        taskId, rowNumber, task.pendingStep(),
                        new BudgetValidationConfirmLog(
                                0L, taskId.toString(), rowNumber, task.departmentCode(),
                                departmentName(task.departmentCode()), confirmerUserId, confirmerName,
                                now, confirmedStep, abnormalSummary, remark, now),
                        resumable);
        if (preparation.alreadyConfirmed()) {
            return progressFromTask(preparation.currentTask(), taskId, rowNumber);
        }

        BudgetReviewTask next;
        try {
            next = processReview(
                    preparation.resumableTask(), lines, snapshot.mappings(),
                    snapshot.categoryRules(), snapshot.projectConfigs(),
                    snapshot.yearValidityConfigs(), snapshot.existingItems());
        } catch (RuntimeException failure) {
            transactionCoordinator.fail(preparation.resumableTask(), failure.getMessage());
            throw failure;
        }
        boolean hasMore = "WAITING_CONFIRMATION".equals(next.status());
        List<BudgetReviewItem> updatedItems = itemRepository.findByTaskId(taskId);
        BudgetReviewItem updatedItem = updatedItems.stream()
                .filter(item -> Integer.valueOf(rowNumber).equals(item.rowNumber()))
                .findFirst()
                .orElse(null);
        BudgetReviewItem nextItem = hasMore && next.pendingRowNumber() != null
                ? updatedItems.stream()
                .filter(item -> next.pendingRowNumber().equals(item.rowNumber()))
                        .findFirst()
                        .orElse(null)
                : null;
        return new ConfirmationProgress(
                true, hasMore ? next.pendingStep() : null,
                hasMore ? "PAUSED" : "COMPLETED",
                hasMore, hasMore ? next.pendingRowNumber() : null,
                updatedItem, nextItem);
    }

    private ConfirmationProgress progressFromTask(
            BudgetReviewTask task, UUID taskId, int rowNumber) {
        boolean hasMore = "WAITING_CONFIRMATION".equals(task.status());
        List<BudgetReviewItem> items = itemRepository.findByTaskId(taskId);
        BudgetReviewItem updatedItem = items.stream()
                .filter(item -> Integer.valueOf(rowNumber).equals(item.rowNumber()))
                .findFirst()
                .orElse(null);
        BudgetReviewItem nextItem = hasMore && task.pendingRowNumber() != null
                ? items.stream()
                        .filter(item -> task.pendingRowNumber().equals(item.rowNumber()))
                        .findFirst()
                        .orElse(null)
                : null;
        return new ConfirmationProgress(
                true,
                hasMore ? task.pendingStep() : null,
                hasMore ? "PAUSED" : "COMPLETED",
                hasMore,
                hasMore ? task.pendingRowNumber() : null,
                updatedItem,
                nextItem);
    }

    public List<BudgetValidationConfirmLog> findConfirmLogs(UUID taskId) {
        requireTask(taskId);
        return confirmLogRepository.findByBatchId(taskId);
    }

    private BudgetReviewTask processReview(
            BudgetReviewTask task,
            List<BudgetMaterialLine> lines,
            List<BudgetSubjectMapping> mappings,
            List<BudgetCategoryRule> categoryRules,
            List<BudgetProjectConfig> projectConfigs,
            List<BudgetYearValidityConfig> yearValidityConfigs,
            List<BudgetReviewItem> existingItems) {
        return processReview(task,lines,mappings,categoryRules,projectConfigs,yearValidityConfigs,existingItems,()->{});
    }

    private BudgetReviewTask processReview(
            BudgetReviewTask task, List<BudgetMaterialLine> lines, List<BudgetSubjectMapping> mappings,
            List<BudgetCategoryRule> categoryRules, List<BudgetProjectConfig> projectConfigs,
            List<BudgetYearValidityConfig> yearValidityConfigs, List<BudgetReviewItem> existingItems, Runnable verifyLease) {
        return processReview(task, lines, mappings, categoryRules, projectConfigs,
                yearValidityConfigs, existingItems, verifyLease, false);
    }

    private BudgetReviewTask processReview(
            BudgetReviewTask task, List<BudgetMaterialLine> lines, List<BudgetSubjectMapping> mappings,
            List<BudgetCategoryRule> categoryRules, List<BudgetProjectConfig> projectConfigs,
            List<BudgetYearValidityConfig> yearValidityConfigs, List<BudgetReviewItem> existingItems,
            Runnable verifyLease, boolean reference) {
        int cursor = Math.max(0, Math.min(task.reviewCursor(), lines.size()));
        Map<String, List<Integer>> projectRows = new LinkedHashMap<>();
        Map<Integer, BudgetReviewItem> previousItems = existingItems.stream()
                .filter(item -> item.rowNumber() != null)
                .collect(Collectors.toMap(
                        BudgetReviewItem::rowNumber,
                        Function.identity(),
                        (left, right) -> right));
        List<BudgetReviewItem> itemsToPersist = new ArrayList<>();
        for (BudgetMaterialLine line : lines) {
            String projectCode = clean(line.projectCode());
            if (!isBlank(projectCode)) {
                projectRows.computeIfAbsent(projectRowKey(line, reference), ignored -> new ArrayList<>())
                        .add(line.rowNumber());
            }
        }
        for (int index = cursor; index < lines.size(); index++) {
            BudgetMaterialLine line = lines.get(index);
            BudgetReviewItem previous = previousItems.get(line.rowNumber());
            String resumeStep = index == cursor && task.pendingStep() != null
                    ? nextStep(task.pendingStep()) : "REQUIRED_FIELDS";
            LineReviewResult result = reviewLine(
                    task, line, mappings, categoryRules, projectConfigs, yearValidityConfigs,
                    projectRows, resumeStep, previous, reference);
            BudgetReviewItem item = result.item();
            itemsToPersist.add(item);
            previousItems.put(line.rowNumber(), item);
            if (result.paused()) {
                BudgetReviewTask waiting = task.withReviewState(
                        "WAITING_CONFIRMATION", index, line.rowNumber(),
                        result.pauseStep(), summarizeAbnormalReasons(item));
                PipelineDiagnostics.run("BUDGET", "DB_REVIEW_PROGRESS_COMMIT", task.id(), Map.of("itemCount", itemsToPersist.size()),
                        () -> { transactionCoordinator.saveProgress(waiting, itemsToPersist, verifyLease); return null; });
                try (var span = PipelineDiagnostics.start("BUDGET", "REVIEW_PAUSE", task.id(), Map.of())) {
                    span.outcome("WAITING_CONFIRMATION", Map.of("reason", result.pauseStep(), "rowCount", lines.size(), "itemCount", itemsToPersist.size()));
                }
                flowDebugLogger.log(
                        "F01_REVIEW_WAITING_CONFIRMATION", "BUDGET", "APPLICATION", "review",
                        Map.of("taskId", task.id().toString(),
                                "rowNumber", line.rowNumber(),
                                "confirmMode", "ROW_REVIEW",
                                "pauseStep", result.pauseStep(),
                                "abnormalSummary", summarizeAbnormalReasons(item)));
                return waiting;
            }
        }
        BudgetReviewTask reviewed = task.withReviewState(
                "REVIEWED", lines.size(), null, null, null);
        PipelineDiagnostics.run("BUDGET", "DB_REVIEW_PROGRESS_COMMIT", task.id(), Map.of("itemCount", itemsToPersist.size()),
                () -> { transactionCoordinator.saveProgress(reviewed, itemsToPersist, verifyLease); return null; });
        flowDebugLogger.log("F01_MATERIAL_REVIEW_COMPLETE", "BUDGET", "APPLICATION", "review",
                Map.of("taskId", task.id().toString(), "lineCount", lines.size()));
        return reviewed;
    }

    private void applyAiAnalysis(
            ReviewState state,
            BudgetSemanticAnalysisItem request) {
        if (budgetSemanticAnalysisUseCase == null) {
            return;
        }
        try {
            BudgetSemanticAnalysisResult result =
                    budgetSemanticAnalysisUseCase.analyzeItem(state.taskId, request);
            if (!result.available() || result.items().isEmpty()) {
                flowDebugLogger.log("F01_BUDGET_AI_UNAVAILABLE", "BUDGET", "APPLICATION",
                        request.analysisType(),
                        Map.of("taskId", state.taskId.toString(),
                                "rowNumber", state.line.rowNumber()));
                return;
            }
            // The provider may return more than one item and does not promise
            // response order. Never attach another row's suggestion to this
            // review line just because it was returned first.
            BudgetSemanticAnalysisResult.Item item = result.items().stream()
                    .filter(candidate -> request.itemId().equals(candidate.itemId()))
                    .findFirst()
                    .orElse(null);
            if (item == null) {
                flowDebugLogger.log("F01_BUDGET_AI_ITEM_MISSING", "BUDGET", "APPLICATION",
                        request.analysisType(),
                        Map.of("taskId", state.taskId.toString(),
                                "rowNumber", state.line.rowNumber(),
                                "outcome", "FALLBACK"));
                return;
            }
            state.aiModel = result.model();
            state.aiDurationMs = result.durationMs();
            switch (request.analysisType()) {
                case "CATEGORY_ATTRIBUTION" -> {
                    state.aiSuggestedCategory = item.suggestedCategory();
                    state.aiHint = item.hint();
                    state.aiConfidence = item.confidence();
                }
                case "FUZZY_MATCH" -> {
                    state.aiHint = item.hint();
                    state.aiJudgment = item.judgment();
                    state.aiSimilarityScore = item.similarityScore();
                }
                case "EXCEPTION_DESCRIPTION" -> {
                    state.aiEnhancedDescription = item.enhancedDescription();
                    state.aiSuggestion = item.suggestion();
                }
                default -> {
                    state.aiSuggestedCategory = item.suggestedCategory();
                    state.aiHint = item.hint();
                }
            }
            flowDebugLogger.logAiAnalysis("F01_BUDGET_AI_NODE_COMPLETE", "BUDGET",
                    request.analysisType(), state.taskId.toString(), "SUCCESS",
                    result.model(), result.durationMs(), 0, 1);
        } catch (RuntimeException exception) {
            try (var span = PipelineDiagnostics.start("BUDGET", "AI_DEGRADED", state.taskId, Map.of())) {
                span.fail(exception);
            }
            flowDebugLogger.log("F01_BUDGET_AI_DEGRADED", "BUDGET", "APPLICATION",
                    request.analysisType(),
                    Map.of("taskId", state.taskId.toString(),
                            "rowNumber", state.line.rowNumber(),
                            "outcome", "FALLBACK"));
        }
    }

    private static String exceptionData(ReviewState state) {
        StringBuilder data = new StringBuilder();
        if (!state.abnormalReasons.isEmpty()) {
            data.append("reasons=").append(String.join(";", state.abnormalReasons));
        }
        if (state.differenceAmount != null) {
            data.append(";differenceAmount=").append(state.differenceAmount);
        }
        if (!state.missingFields.isEmpty()) {
            data.append(";missingFields=").append(String.join(",", state.missingFields));
        }
        return data.toString();
    }

    private static String nextStep(String step) {
        return switch (step == null ? "" : step) {
            case "REQUIRED_FIELDS" -> "AMOUNT_FORMAT";
            case "AMOUNT_FORMAT" -> "SUBJECT_CODE";
            case "SUBJECT_CODE" -> "SUBJECT_NAME";
            case "SUBJECT_NAME" -> "CATEGORY_ATTRIBUTION";
            case "CATEGORY_ATTRIBUTION" -> "BUDGET_COMPARE";
            case "BUDGET_COMPARE" -> "SUPPLIER_NAME";
            case "SUPPLIER_NAME" -> "PROJECT_CODE";
            case "PROJECT_CODE" -> "PROJECT_DEPARTMENT";
            case "PROJECT_DEPARTMENT" -> "BUDGET_YEAR_VALIDITY";
            case "BUDGET_YEAR_VALIDITY" -> "COMPLETED";
            // 兼容旧版本把模块名写入 pendingStep 的历史任务。
            case "DATA_VALIDATION" -> "SUBJECT_CODE";
            case "SUBJECT_CONSISTENCY" -> "BUDGET_COMPARE";
            case "AMOUNT_COMPLIANCE" -> "SUPPLIER_NAME";
            case "BUDGET_AMOUNT" -> "SUPPLIER_NAME";
            case "SUPPLIER_CHECK" -> "PROJECT_CODE";
            case "PROJECT_COMPLIANCE" -> "COMPLETED";
            default -> "REQUIRED_FIELDS";
        };
    }

    private static String moduleKey(String step) {
        return switch (step == null ? "" : step) {
            case "REQUIRED_FIELDS", "AMOUNT_FORMAT" -> "DATA_VALIDATION";
            case "SUBJECT_CODE", "SUBJECT_NAME", "CATEGORY_ATTRIBUTION" ->
                    "SUBJECT_CONSISTENCY";
            case "BUDGET_COMPARE" -> "AMOUNT_COMPLIANCE";
            case "SUPPLIER_NAME" -> "SUPPLIER_CHECK";
            case "PROJECT_CODE", "PROJECT_DEPARTMENT", "BUDGET_YEAR_VALIDITY" ->
                    "PROJECT_COMPLIANCE";
            default -> "DATA_VALIDATION";
        };
    }

    private static String summarizeAbnormalReasons(BudgetReviewItem item) {
        if (item.abnormalReasons() != null && !item.abnormalReasons().isEmpty()) {
            return String.join("；", item.abnormalReasons());
        }
        return item.errorDetail() == null ? "该行存在预算校验异常" : item.errorDetail();
    }

    private static String departmentName(String departmentCode) {
        return switch (departmentCode == null ? "" : departmentCode) {
            case "PROCUREMENT" -> "采购管理部";
            case "FINANCE" -> "财务部";
            case "GENERAL" -> "综合管理部";
            default -> departmentCode;
        };
    }

    private List<BudgetMaterialLine> parseStoredLines(
             BudgetReviewTask task,
             BudgetMaterialParser parser,
             String materialReference) {
        try (var span = PipelineDiagnostics.start("BUDGET", "STORED_DOCUMENT_PARSE", task.id(), Map.of())) {
            try {
                List<BudgetMaterialLine> lines;
                try (InputStream stored = materialStorage.open(materialReference)) {
                    lines = parser.parse(stored, task.fileName());
                }
                span.success(Map.of("rowCount", lines.size()));
                return lines;
            } catch (IOException exception) {
                span.fail(exception);
                throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                        "预算材料读取失败: " + exception.getMessage(), exception);
            } catch (RuntimeException failure) { span.fail(failure); throw failure; }
        }
    }

    private boolean hasConfirmationLog(UUID taskId, int rowNumber) {
        return confirmLogRepository.findByBatchId(taskId).stream()
                .anyMatch(log -> log.rowNumber() == rowNumber);
    }

    private static void validateRequestedConfirmationStep(
            String requestedStep, String pendingStep, boolean moduleLevelLog) {
        if (moduleLevelLog || requestedStep == null || requestedStep.isBlank()) {
            return;
        }
        String normalized = requestedStep.trim().toUpperCase(Locale.ROOT);
        boolean compatible = normalized.equals(pendingStep)
                || normalized.equals(moduleKey(pendingStep))
                || ("ROW_REVIEW".equals(normalized) && "REQUIRED_FIELDS".equals(pendingStep));
        if (!compatible) {
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT, "待确认步骤已变化，请刷新后重试");
        }
    }

    public record ConfirmationProgress(
            boolean confirmed,
            String nextStep,
            String executionStatus,
            boolean hasMoreException,
            Integer nextExceptionRow,
            BudgetReviewItem updatedItem,
            BudgetReviewItem nextItem) {
    }

    public BudgetReviewTask requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "预算审查任务不存在: " + taskId));
    }

    public List<BudgetReviewTask> findTaskPage(
            String batchId, String budgetYear, String departmentCode, int offset, int limit) {
        return taskRepository.findPage(batchId, budgetYear, departmentCode, offset, limit);
    }

    public long countTasks(String batchId, String budgetYear, String departmentCode) {
        return taskRepository.count(batchId, budgetYear, departmentCode);
    }

    public List<BudgetReviewItem> findItems(UUID taskId) {
        requireTask(taskId);
        return itemRepository.findByTaskId(taskId);
    }

    public BudgetReviewItem findItem(UUID taskId, UUID itemId) {
        requireTask(taskId);
        BudgetReviewItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "预算审查明细不存在: " + itemId));
        if (!taskId.equals(item.taskId())) {
            throw new BusinessException(
                    BusinessErrorCode.NOT_FOUND, "预算审查明细不存在: " + itemId);
        }
        return item;
    }

    private LineReviewResult reviewLine(
            BudgetReviewTask task,
            BudgetMaterialLine line,
            List<BudgetSubjectMapping> mappings,
            List<BudgetCategoryRule> categoryRules,
            List<BudgetProjectConfig> projectConfigs,
            List<BudgetYearValidityConfig> yearValidityConfigs,
            Map<String, List<Integer>> projectRows,
            String resumeStep,
            BudgetReviewItem previous) {
        return reviewLine(task, line, mappings, categoryRules, projectConfigs,
                yearValidityConfigs, projectRows, resumeStep, previous, false);
    }

    private LineReviewResult reviewLine(
            BudgetReviewTask task, BudgetMaterialLine line, List<BudgetSubjectMapping> mappings,
            List<BudgetCategoryRule> categoryRules, List<BudgetProjectConfig> projectConfigs,
            List<BudgetYearValidityConfig> yearValidityConfigs, Map<String, List<Integer>> projectRows,
            String resumeStep, BudgetReviewItem previous, boolean reference) {
        ReviewState state = new ReviewState(task.id(), line, previous);
        ApprovalResolution approval = resolveApproval(line, mappings);
        state.approvalSubjectCode = approval.subjectCode();
        state.approvalSubjectName = approval.subjectName();
        state.approvalAmount = approval.amount();
        state.approvalSupplierName = approval.supplierName();

        int start = stepIndex(resumeStep);
        if (start <= 0) {
            debugStep(task.id(), line.rowNumber(), "REQUIRED_FIELDS");
            state.requiredFieldsStatus = "PASSED";
            addMissing(state.missingFields, "科目编码", state.subjectCode);
            addMissing(state.missingFields, "科目名称", state.subjectName);
            addMissing(state.missingFields, "申报金额", line.declaredAmountText());
            addMissing(state.missingFields, "供应商名称", state.supplierName);
            addMissing(state.missingFields, "项目编号", state.projectCode);
            if (!state.missingFields.isEmpty()) {
                state.requiredFieldsStatus = "FAILED";
                state.missingFields.forEach(field -> addReason(state, "必填信息缺失：" + fieldName(field)));
                if (isBlank(state.subjectCode)) {
                    state.codeFormatError = "科目编码为空";
                }
                if (!reference) return finish(state, "REQUIRED_FIELDS");
            }
        }

        if (start <= 1) {
            debugStep(task.id(), line.rowNumber(), "AMOUNT_FORMAT");
            state.amountFormatStatus = "PASSED";
            state.amountFormatErrors.addAll(validateAmountFormat(
                    line.declaredAmountText(), line.declaredAmount()));
            if (!state.amountFormatErrors.isEmpty()) {
                state.amountFormatStatus = "FAILED";
                state.amountFormatErrors.forEach(error ->
                        addReason(state, "金额格式非法：" + error));
                if (!reference) return finish(state, "AMOUNT_FORMAT");
            }
        }

        if (start <= 2) {
            debugStep(task.id(), line.rowNumber(), "SUBJECT_CODE");
            if (isBlank(state.subjectCode)) {
                state.codeFormatError = "科目编码为空";
                addReason(state, state.codeFormatError);
                // The required-field step already captured this same missing value.
                // Keep it failed without asking the user to confirm the same issue twice.
                if (!hasMissingField(state.missingFields, "科目编码")) {
                    if (!reference) return finish(state, "SUBJECT_CODE");
                }
            } else if (!SUBJECT_CODE_PATTERN.matcher(state.subjectCode).matches()) {
                state.codeFormatError = "科目编码格式异常，须符合字母/数字开头，长度3-20，允许下划线和连字符";
                addReason(state, state.codeFormatError);
                if (!reference) return finish(state, "SUBJECT_CODE");
            }
            if (!isBlank(state.subjectCode) && !isBlank(state.approvalSubjectCode)
                    && !trimmedEquals(state.approvalSubjectCode, state.subjectCode)) {
                addReason(state, "科目编码与预算批复不一致");
                if (!reference) return finish(state, "SUBJECT_CODE");
            }
        }

        if (start <= 3) {
            debugStep(task.id(), line.rowNumber(), "SUBJECT_NAME");
            state.matchMethod = approval.method();
            state.matchDistance = approval.distance();
            if (approval.candidates().isEmpty()) {
                state.matchStatus = "UNMATCHED";
                addReason(state, "科目匹配失败：未找到预算批复科目");
                if (!reference) return finish(state, "SUBJECT_NAME");
            }
            if ("FUZZY".equals(approval.method()) && !approval.candidates().isEmpty()) {
                applyAiAnalysis(state, new BudgetSemanticAnalysisItem(
                        state.itemId,
                        state.subjectName,
                        approval.candidates().get(0).mapping().budgetSubjectName(),
                        "FUZZY_MATCH",
                        state.subjectCode,
                        null,
                        state.subjectName,
                        approval.candidates().get(0).mapping().budgetSubjectName(),
                        null,
                        null));
            }
            if (!approval.candidates().isEmpty() && approval.uniqueMappings() != 1) {
                state.matchStatus = "SUSPECTED";
                state.amountStatus = "NEED_CONFIRMATION";
                addReason(state, "科目疑似匹配：存在多个候选预算批复科目，需要人工确认");
                if (!reference) return finish(state, "SUBJECT_NAME");
            }
            if (approval.uniqueMappings() == 1) state.matchStatus = "MATCHED";
        }

        if (start <= 4) {
            debugStep(task.id(), line.rowNumber(), "CATEGORY_ATTRIBUTION");
            if (!categoryRules.isEmpty() && !isBlank(state.subjectCode)
                    && !isBlank(state.subjectName)) {
                BudgetCategoryRule rule = categoryRules.stream()
                        .filter(candidate -> candidate.categoryCode().equalsIgnoreCase(state.subjectCode))
                        .findFirst()
                        .orElse(null);
                if (rule != null) {
                    List<String> forbidden = keywords(rule.forbiddenKeywords());
                    List<String> allowed = keywords(rule.allowedKeywords());
                    String forbiddenKeyword = forbidden.stream()
                            .filter(keyword -> containsNormalized(state.subjectName, keyword))
                            .findFirst()
                            .orElse(null);
                    if (forbiddenKeyword != null) {
                        state.categoryAttributionStatus = "FAILED";
                        state.categorySuggestion = rule.suggestCategory();
                        addReason(state, "科目分类归属不匹配：" + rule.categoryName()
                                + "不应包含“" + forbiddenKeyword + "”，建议使用 "
                                + (isBlank(rule.suggestCategory()) ? "对应科目" : rule.suggestCategory()));
                        if (!reference) return finish(state, "CATEGORY_ATTRIBUTION");
                    }
                    boolean hasAllowedKeyword = allowed.isEmpty()
                            || allowed.stream().anyMatch(keyword ->
                            containsNormalized(state.subjectName, keyword));
                    if (!hasAllowedKeyword) {
                        state.categoryAttributionStatus = "FAILED";
                        addReason(state, "科目分类归属需确认：" + rule.categoryName()
                                + "未匹配允许关键词");
                        if (!reference) return finish(state, "CATEGORY_ATTRIBUTION");
                    }
                }
            }
            if (!"FAILED".equals(state.categoryAttributionStatus) && !isBlank(state.subjectName)) {
                state.categoryAttributionStatus = "PASSED";
            }
            if (!isBlank(state.subjectName)) applyAiAnalysis(state, new BudgetSemanticAnalysisItem(
                    state.itemId,
                    state.subjectName,
                    state.subjectCode,
                    "CATEGORY_ATTRIBUTION",
                    state.subjectCode,
                    state.subjectCode,
                    null,
                    null,
                    null,
                    null));
        }

        if (start <= 5) {
            debugStep(task.id(), line.rowNumber(), "BUDGET_COMPARE");
            if (state.approvalAmount != null && line.declaredAmount() != null
                    && (!reference || approval.uniqueMappings() == 1
                        && "PASSED".equals(state.amountFormatStatus))) {
                state.differenceAmount = line.declaredAmount()
                        .subtract(state.approvalAmount)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                state.amountStatus = state.differenceAmount.compareTo(BigDecimal.ZERO) <= 0
                        ? "PASSED" : "OVER_BUDGET";
                if ("OVER_BUDGET".equals(state.amountStatus)) {
                    addReason(state, "超预算：申报金额超过预算批复金额");
                    if (!reference) return finish(state, "BUDGET_COMPARE");
                }
            } else if (approval.uniqueMappings() == 1 && state.approvalAmount == null) {
                addReason(state, "预算批复数据不存在：批复金额为空");
                if (!reference) return finish(state, "BUDGET_COMPARE");
            } else {
                // 科目没有匹配到预算批复时没有可比较的预算金额。
                // 该项是有明确原因的跳过，不是活动中的待校验步骤。
                if (!"NEED_CONFIRMATION".equals(state.amountStatus)) state.amountStatus = "SKIPPED";
            }
        }

        if (start <= 6) {
            debugStep(task.id(), line.rowNumber(), "SUPPLIER_NAME");
            if (state.approvalSupplierName != null
                    && !trimmedEquals(state.approvalSupplierName, state.supplierName)) {
                addReason(state, "供应商名称与预算批复不一致");
                state.supplierNameStatus = "FAILED";
                if (!reference) return finish(state, "SUPPLIER_NAME");
            }
            if (!"FAILED".equals(state.supplierNameStatus)) state.supplierNameStatus = "PASSED";
        }

        if (start <= 7) {
            debugStep(task.id(), line.rowNumber(), "PROJECT_CODE");
            if (isBlank(state.projectCode)) {
                state.projectCodeStatus = "FAILED";
                if (!hasMissingField(state.missingFields, "项目编号")) {
                    addReason(state, "项目编号为空");
                    if (!reference) return finish(state, "PROJECT_CODE");
                }
                // Missing projectCode was already confirmed in REQUIRED_FIELDS.
                // Do not create a second confirmation for the same root cause.
            } else {
                List<Integer> rows = projectRows.getOrDefault(projectRowKey(line, reference), List.of());
                if (rows.size() > 1) {
                    state.projectCodeStatus = "FAILED";
                    addReason(state, (reference ? "项目编号重复且科目、供应商相同：第" : "项目编号重复：第") + rows.stream()
                            .map(String::valueOf).collect(Collectors.joining("、"))
                            + "行与当前行相同（" + state.projectCode + "）");
                    if (!reference) return finish(state, "PROJECT_CODE");
                }
                if (!"FAILED".equals(state.projectCodeStatus)) state.projectCodeStatus = "PASSED";
            }
        }

        if (start <= 8) {
            debugStep(task.id(), line.rowNumber(), "PROJECT_DEPARTMENT");
            if (isBlank(state.projectCode)) {
                // Department ownership cannot be resolved without a project code.
                state.projectDepartmentStatus = "NOT_CHECKED";
            } else {
            List<BudgetProjectConfig> configs = projectConfigs.stream()
                    .filter(config -> config.projectCode().equalsIgnoreCase(state.projectCode))
                    .toList();
            if (!projectConfigs.isEmpty() && configs.isEmpty()) {
                state.projectDepartmentStatus = "FAILED";
                addReason(state, "项目编号不存在：" + state.projectCode);
                if (!reference) return finish(state, "PROJECT_DEPARTMENT");
            }
            if (!configs.isEmpty() && !isBlank(task.departmentCode())
                    && !equalsIgnoreCase(configs.get(0).departmentCode(), task.departmentCode())) {
                state.projectDepartmentStatus = "FAILED";
                addReason(state, "项目不属于当前部门：项目归属 "
                        + configs.get(0).departmentName() + "，当前部门 "
                        + departmentName(task.departmentCode()));
                if (!reference) return finish(state, "PROJECT_DEPARTMENT");
            }
            if (!"FAILED".equals(state.projectDepartmentStatus)) state.projectDepartmentStatus = "PASSED";
            }
        }

        if (start <= 9) {
            debugStep(task.id(), line.rowNumber(), "BUDGET_YEAR_VALIDITY");
            List<BudgetYearValidityConfig> configs = yearValidityConfigs.stream()
                    .filter(config -> config.subjectCode().equalsIgnoreCase(state.subjectCode))
                    .toList();
            if (!yearValidityConfigs.isEmpty() && configs.isEmpty()) {
                state.budgetYearValidityStatus = "FAILED";
                addReason(state, "科目未配置有效期：" + state.subjectCode);
                if (!reference) return finish(state, "BUDGET_YEAR_VALIDITY");
            }
            if (!configs.isEmpty()) {
                int year = parseYear(task.budgetYear());
                BudgetYearValidityConfig config = configs.get(0);
                if (year < config.startYear()) {
                    state.budgetYearValidityStatus = "FAILED";
                    addReason(state, "科目编码 " + state.subjectCode + " 在 " + year
                            + " 年度尚未启用（起始年度：" + config.startYear() + "）");
                    if (!reference) return finish(state, "BUDGET_YEAR_VALIDITY");
                }
                if (year > config.endYear()) {
                    state.budgetYearValidityStatus = "FAILED";
                    addReason(state, "科目编码 " + state.subjectCode + " 在 " + year
                            + " 年度已停用（有效期至：" + config.endYear() + "）");
                    if (!reference) return finish(state, "BUDGET_YEAR_VALIDITY");
                }
            }
            if (!"FAILED".equals(state.budgetYearValidityStatus)) state.budgetYearValidityStatus = "PASSED";
        }

        return finish(state, null);
    }

    private LineReviewResult finish(ReviewState state, String pauseStep) {
        if (!isBlank(state.supplierName)) {
            blacklistCheckPort.check(new BlacklistCheckPort.CheckCommand(
                    state.taskId.toString(), state.line.rowNumber(), state.projectCode,
                    state.supplierName, null));
        }
        String exceptionType = exceptionType(
                state.matchStatus, state.amountStatus, state.approvalAmount,
                state.amountFormatErrors, state.missingFields, state.codeFormatError,
                state.supplierName, state.approvalSupplierName, state.abnormalReasons);
        if (!state.abnormalReasons.isEmpty()) {
            applyAiAnalysis(state, new BudgetSemanticAnalysisItem(
                    state.itemId,
                    exceptionType == null ? "F01_EXCEPTION" : exceptionType,
                    state.categorySuggestion,
                    "EXCEPTION_DESCRIPTION",
                    state.subjectCode,
                    null,
                    null,
                    null,
                    exceptionType == null ? "F01_EXCEPTION" : exceptionType,
                    exceptionData(state)));
        }
        String risk = riskLevel(
                state.matchStatus, state.amountStatus, state.codeFormatError,
                state.abnormalReasons, state.requiredFieldsStatus,
                state.projectCodeStatus, state.projectDepartmentStatus,
                state.budgetYearValidityStatus);
        return new LineReviewResult(item(
                state.itemId, state.taskId, state.line, state.subjectCode, state.subjectName,
                state.supplierName, state.projectCode, state.approvalSubjectCode,
                state.approvalSubjectName, state.approvalAmount, state.approvalSupplierName,
                state.matchStatus, state.matchMethod, state.matchDistance, state.amountStatus,
                state.differenceAmount, exceptionType, joinReasons(state.abnormalReasons), risk,
                state.missingFields, state.amountFormatErrors, state.codeFormatError,
                state.requiredFieldsStatus, state.amountFormatStatus,
                state.categoryAttributionStatus, state.categorySuggestion,
                state.projectCodeStatus,
                state.projectDepartmentStatus, state.budgetYearValidityStatus,
                state.abnormalReasons, state.createdTime,
                state.aiSuggestedCategory, state.aiHint, state.aiModel, state.aiDurationMs,
                state.aiConfidence, state.aiJudgment, state.aiSimilarityScore,
                state.aiEnhancedDescription, state.aiSuggestion),
                pauseStep != null, pauseStep);
    }

    private static BlacklistCheckPort noOpBlacklistCheckPort() {
        return command -> new BlacklistCheckPort.CheckOutcome(
                command.batchId(), command.supplierName(), command.supplierCode(),
                false, List.of(), null);
    }

    private ApprovalResolution resolveApproval(
            BudgetMaterialLine line, List<BudgetSubjectMapping> mappings) {
        if (isBlank(line.subjectName())) {
            return new ApprovalResolution(null, null, null, null, null, null, List.of(), 0);
        }
        String normalizedSubject = BudgetTextNormalizer.subject(line.subjectName());
        List<MatchCandidate> candidates = mappings.stream()
                .flatMap(mapping -> subjectCandidates(mapping).stream()
                        .map(candidate -> new MatchCandidate(mapping, candidate,
                                levenshtein(normalizedSubject, candidate))))
                .sorted(Comparator.comparingInt(MatchCandidate::distance))
                .toList();
        List<MatchCandidate> exact = candidates.stream()
                .filter(candidate -> candidate.distance() == 0)
                .toList();
        List<MatchCandidate> eligible = exact.isEmpty()
                ? candidates.stream().filter(candidate -> candidate.distance() <= FUZZY_DISTANCE_LIMIT).toList()
                : exact;
        List<BudgetSubjectMapping> uniqueMappings = eligible.stream()
                .map(MatchCandidate::mapping)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(BudgetSubjectMapping::id, Function.identity(), (left, right) -> left),
                        map -> new ArrayList<>(map.values())));
        if (eligible.isEmpty() || uniqueMappings.size() != 1) {
            int distance = eligible.isEmpty() ? 0 : eligible.get(0).distance();
            return new ApprovalResolution(null, null, null, null,
                    eligible.isEmpty() ? null : distance == 0 ? "EXACT" : "FUZZY",
                    distance == 0 && eligible.isEmpty() ? null : distance,
                    eligible, uniqueMappings.size());
        }
        BudgetSubjectMapping mapping = uniqueMappings.get(0);
        int distance = eligible.get(0).distance();
        return new ApprovalResolution(
                mapping.budgetSubjectCode(), mapping.budgetSubjectName(), mapping.budgetAmount(),
                mapping.supplierName(), distance == 0 ? "EXACT" : "FUZZY", distance,
                eligible, uniqueMappings.size());
    }

    private static int stepIndex(String step) {
        return switch (step == null ? "REQUIRED_FIELDS" : step) {
            case "REQUIRED_FIELDS" -> 0;
            case "AMOUNT_FORMAT" -> 1;
            case "SUBJECT_CODE" -> 2;
            case "SUBJECT_NAME" -> 3;
            case "CATEGORY_ATTRIBUTION" -> 4;
            case "BUDGET_COMPARE" -> 5;
            case "SUPPLIER_NAME" -> 6;
            case "PROJECT_CODE" -> 7;
                case "PROJECT_DEPARTMENT" -> 8;
                case "BUDGET_YEAR_VALIDITY" -> 9;
                // 兼容旧版本模块级暂停记录，映射到该模块第一个校验项。
                case "DATA_VALIDATION" -> 0;
                case "SUBJECT_CONSISTENCY" -> 2;
                case "AMOUNT_COMPLIANCE" -> 5;
                case "SUPPLIER_CHECK" -> 6;
                case "PROJECT_COMPLIANCE" -> 7;
                case "COMPLETED" -> 10;
                default -> 0;
            };
    }

    private static int parseYear(String value) {
        try {
            return value == null || value.isBlank() ? java.time.Year.now().getValue() : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return java.time.Year.now().getValue();
        }
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static void addReason(ReviewState state, String reason) {
        if (!state.abnormalReasons.contains(reason)) {
            state.abnormalReasons.add(reason);
        }
    }

    private static String fieldName(String field) {
        return switch (field) {
            case "subjectCode", "科目编码" -> "科目编码";
            case "subjectName", "科目名称" -> "科目名称";
            case "declareAmount", "申报金额" -> "申报金额";
            case "supplierName", "供应商名称" -> "供应商名称";
            case "projectCode", "项目编号" -> "项目编号";
            default -> field;
        };
    }

    private static String exceptionType(
            String matchStatus,
            String amountStatus,
            BigDecimal approvalAmount,
            List<String> amountErrors,
            List<String> missingFields,
            String codeFormatError,
            String supplierName,
            String approvalSupplierName,
            List<String> abnormalReasons) {
        if (!missingFields.isEmpty()) {
            return "REQUIRED_FIELD_MISSING";
        }
        if (approvalSupplierName != null && !trimmedEquals(approvalSupplierName, supplierName)) {
            return "SUPPLIER_MISMATCH";
        }
        if ("OVER_BUDGET".equals(amountStatus)) {
            return "OVER_BUDGET";
        }
        if ("UNMATCHED".equals(matchStatus)) {
            return "SUBJECT_NOT_FOUND";
        }
        if ("SUSPECTED".equals(matchStatus)) {
            return "AMBIGUOUS_MATCH";
        }
        if (approvalAmount == null && "MATCHED".equals(matchStatus)) {
            return "APPROVAL_DATA_NOT_FOUND";
        }
        if (!amountErrors.isEmpty()) {
            return "AMOUNT_FORMAT_EXCEPTION";
        }
        if (codeFormatError != null) {
            return "SUBJECT_CODE_FORMAT_EXCEPTION";
        }
        if (abnormalReasons.stream().anyMatch(reason -> reason.startsWith("项目编号重复"))) {
            return "PROJECT_CODE_DUPLICATE";
        }
        if (abnormalReasons.stream().anyMatch(reason -> reason.startsWith("项目编号不存在")
                || reason.startsWith("项目不属于当前部门"))) {
            return "PROJECT_DEPARTMENT_MISMATCH";
        }
        if (abnormalReasons.stream().anyMatch(reason -> reason.startsWith("科目未配置有效期")
                || reason.contains("尚未启用") || reason.contains("已停用"))) {
            return "BUDGET_YEAR_INVALID";
        }
        if (abnormalReasons.stream().anyMatch(reason -> reason.startsWith("科目分类归属"))) {
            return "CATEGORY_ATTRIBUTION_EXCEPTION";
        }
        return null;
    }

    private static String riskLevel(
            String matchStatus,
            String amountStatus,
            String codeFormatError,
            List<String> abnormalReasons,
            String requiredFieldsStatus,
            String projectCodeStatus,
            String projectDepartmentStatus,
            String budgetYearValidityStatus) {
        if ("OVER_BUDGET".equals(amountStatus)
                || "UNMATCHED".equals(matchStatus)
                || "科目编码为空".equals(codeFormatError)) {
            return "CRITICAL";
        }
        if ("FAILED".equals(projectCodeStatus)
                || "FAILED".equals(projectDepartmentStatus)
                || "FAILED".equals(budgetYearValidityStatus)
                || abnormalReasons.stream().anyMatch(reason -> reason.startsWith("项目编号重复")
                        || reason.startsWith("项目编号不存在")
                        || reason.startsWith("项目不属于当前部门")
                        || reason.startsWith("科目未配置有效期")
                || reason.contains("尚未启用") || reason.contains("已停用"))) {
            return "CRITICAL";
        }
        if (abnormalReasons.stream().anyMatch(reason -> reason.startsWith("科目分类归属不匹配"))) {
            return "CRITICAL";
        }
        return abnormalReasons.isEmpty() ? "INFO" : "WARNING";
    }

    private static BudgetReviewItem item(
            UUID itemId,
            UUID taskId,
            BudgetMaterialLine line,
            String subjectCode,
            String subjectName,
            String supplierName,
            String projectCode,
            String approvalSubjectCode,
            String approvalSubjectName,
            BigDecimal approvalAmount,
            String approvalSupplierName,
            String matchStatus,
            String matchMethod,
            Integer matchDistance,
            String amountStatus,
            BigDecimal differenceAmount,
            String exceptionType,
            String errorDetail,
            String riskLevel,
            List<String> missingFields,
            List<String> amountFormatErrors,
            String codeFormatError,
            String requiredFieldsStatus,
            String amountFormatStatus,
            String categoryAttributionStatus,
            String categorySuggestion,
            String projectCodeStatus,
            String projectDepartmentStatus,
            String budgetYearValidityStatus,
            List<String> abnormalReasons,
            Instant createdTime,
            String aiSuggestedCategory,
            String aiHint,
            String aiModel,
            Long aiDurationMs,
            Double aiConfidence,
            String aiJudgment,
            Double aiSimilarityScore,
            String aiEnhancedDescription,
            String aiSuggestion) {
        return new BudgetReviewItem(
                itemId, taskId, line.rowNumber(), subjectCode, subjectName,
                line.declaredAmount(), supplierName, projectCode,
                approvalSubjectCode, approvalSubjectName, approvalAmount, approvalSupplierName,
                matchStatus, matchMethod, matchDistance, amountStatus, differenceAmount,
                exceptionType, errorDetail, riskLevel,
                join(missingFields), join(amountFormatErrors), codeFormatError,
                requiredFieldsStatus, amountFormatStatus,
                categoryAttributionStatus, categorySuggestion, projectCodeStatus,
                projectDepartmentStatus, budgetYearValidityStatus,
                abnormalReasons, createdTime, aiSuggestedCategory, aiHint, aiModel, aiDurationMs,
                aiConfidence, aiJudgment, aiSimilarityScore, aiEnhancedDescription, aiSuggestion);
    }

    private static List<String> validateAmountFormat(String raw, BigDecimal parsedAmount) {
        List<String> errors = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            errors.add("金额为空");
            return errors;
        }
        var exact = com.fuquan.audit.common.domain.RmbAmountParser.parse(raw);
        if (exact.isPresent() && parsedAmount != null && exact.get().compareTo(parsedAmount) == 0) {
            if (parsedAmount.signum() == 0) errors.add("金额为0");
            return errors;
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace(",", "")
                .replace("，", "")
                .replace("￥", "")
                .replace("¥", "")
                .replace("人民币", "")
                .trim();
        String numericPart = normalized
                .replaceAll("(万元?|千元?|元)$", "")
                .trim();
        if (!NUMERIC_PATTERN.matcher(numericPart).matches()) {
            errors.add("含非数字字符");
            return errors;
        }
        int dot = numericPart.indexOf('.');
        if (dot >= 0 && numericPart.length() - dot - 1 > 2) {
            errors.add("小数位超过2位");
        }
        // Diagnostic only: invalid raw values must never become business amounts.
        BigDecimal diagnosticAmount = new BigDecimal(numericPart);
        if (diagnosticAmount.compareTo(BigDecimal.ZERO) == 0) {
            errors.add("金额为0");
        } else if (diagnosticAmount.compareTo(BigDecimal.ZERO) < 0) {
            errors.add("金额为负数");
        }
        if (errors.isEmpty()) errors.add("金额格式、精度或数值范围无法确认");
        return errors;
    }

    private static void addMissing(List<String> fields, String name, String value) {
        if (isBlank(value)) {
            fields.add(name);
        }
    }

    private static boolean hasMissingField(List<String> fields, String fieldName) {
        return fields.stream().anyMatch(field -> fieldName.equals(fieldName(field)));
    }

    private void debugStep(UUID taskId, int rowNumber, String step) {
        flowDebugLogger.log("F01_REVIEW_STEP", "BUDGET", "APPLICATION", step,
                Map.of("taskId", taskId.toString(), "rowNumber", rowNumber));
    }

    private Set<String> subjectCandidates(BudgetSubjectMapping mapping) {
        Set<String> values = new HashSet<>();
        String budgetName = BudgetTextNormalizer.subject(mapping.budgetSubjectName());
        String systemName = BudgetTextNormalizer.subject(mapping.systemSubjectName());
        if (budgetName != null) {
            values.add(budgetName);
        }
        if (systemName != null) {
            values.add(systemName);
        }
        return values;
    }

    private void markFailed(BudgetReviewTask task, String detail) {
        PipelineDiagnostics.run("BUDGET", "DB_UPLOAD_FAILURE", task.id(), Map.of(),
                () -> { taskRepository.save(task.withStatus("FAILED", 0, detail == null ? "材料解析失败" : detail)); return null; });
    }

    private static void validateFile(String fileName, long fileSize) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "文件名不能为空");
        }
        if (fileSize <= 0) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "文件不能为空");
        }
        if (fileSize > MAX_FILE_SIZE) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "文件大小不能超过 500MB");
        }
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static String projectRowKey(BudgetMaterialLine line, boolean reference) {
        // A project may legitimately have multiple subjects in a reference report.
        // Keep the legacy interactive uniqueness rule unchanged.
        return reference ? java.util.Arrays.asList(clean(line.projectCode()), clean(line.subjectCode()),
                clean(line.supplierName())).toString() : clean(line.projectCode());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean trimmedEquals(String left, String right) {
        if (isBlank(left) || isBlank(right)) {
            return false;
        }
        return BudgetTextNormalizer.subject(left).equals(BudgetTextNormalizer.subject(right));
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    private static List<String> keywords(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[,，;；]"))
                .map(String::trim)
                .filter(keyword -> !keyword.isBlank())
                .toList();
    }

    private static boolean containsNormalized(String text, String keyword) {
        return !isBlank(text) && !isBlank(keyword)
                && BudgetTextNormalizer.subject(text)
                .contains(BudgetTextNormalizer.subject(keyword));
    }

    private static String joinReasons(List<String> values) {
        return join(values);
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private record MatchCandidate(BudgetSubjectMapping mapping, String candidate, int distance) {
    }

    private record ApprovalResolution(
            String subjectCode,
            String subjectName,
            BigDecimal amount,
            String supplierName,
            String method,
            Integer distance,
            List<MatchCandidate> candidates,
            int uniqueMappings) {
    }

    private record LineReviewResult(BudgetReviewItem item, boolean paused, String pauseStep) {
    }

    private static final class ReviewState {
        private final UUID itemId;
        private final UUID taskId;
        private final BudgetMaterialLine line;
        private final Instant createdTime;
        private final List<String> missingFields = new ArrayList<>();
        private final List<String> amountFormatErrors = new ArrayList<>();
        private final List<String> abnormalReasons = new ArrayList<>();
        private String subjectCode;
        private String subjectName;
        private String supplierName;
        private String projectCode;
        private String approvalSubjectCode;
        private String approvalSubjectName;
        private BigDecimal approvalAmount;
        private String approvalSupplierName;
        private String matchStatus = "NOT_CHECKED";
        private String matchMethod;
        private Integer matchDistance;
        private String amountStatus = "NOT_CHECKED";
        private BigDecimal differenceAmount;
        private String codeFormatError;
        private String requiredFieldsStatus = "NOT_CHECKED";
        private String amountFormatStatus = "NOT_CHECKED";
        private String categoryAttributionStatus = "NOT_CHECKED";
        private String categorySuggestion;
        private String supplierNameStatus = "NOT_CHECKED";
        private String projectCodeStatus = "NOT_CHECKED";
        private String projectDepartmentStatus = "NOT_CHECKED";
        private String budgetYearValidityStatus = "NOT_CHECKED";
        private String aiSuggestedCategory;
        private String aiHint;
        private String aiModel;
        private Long aiDurationMs;
        private Double aiConfidence;
        private String aiJudgment;
        private Double aiSimilarityScore;
        private String aiEnhancedDescription;
        private String aiSuggestion;

        private ReviewState(UUID taskId, BudgetMaterialLine line, BudgetReviewItem previous) {
            this.itemId = previous == null ? UUID.randomUUID() : previous.id();
            this.taskId = taskId;
            this.line = line;
            this.createdTime = previous == null ? Instant.now() : previous.createdTime();
            this.subjectCode = clean(line.subjectCode());
            this.subjectName = clean(line.subjectName());
            this.supplierName = clean(line.supplierName());
            this.projectCode = clean(line.projectCode());
            if (previous != null) {
                this.approvalSubjectCode = previous.approvalSubjectCode();
                this.approvalSubjectName = previous.approvalSubjectName();
                this.approvalAmount = previous.approvalAmount();
                this.approvalSupplierName = previous.approvalSupplierName();
                this.matchStatus = previous.matchStatus();
                this.matchMethod = previous.matchMethod();
                this.matchDistance = previous.matchDistance();
                this.amountStatus = previous.amountStatus();
                this.differenceAmount = previous.differenceAmount();
                this.codeFormatError = previous.codeFormatError();
                this.requiredFieldsStatus = previous.requiredFieldsStatus();
                this.amountFormatStatus = previous.amountFormatStatus();
                this.categoryAttributionStatus = previous.categoryAttributionStatus();
                this.categorySuggestion = previous.categorySuggestion();
                this.projectCodeStatus = previous.projectCodeStatus();
                this.projectDepartmentStatus = previous.projectDepartmentStatus();
                this.budgetYearValidityStatus = previous.budgetYearValidityStatus();
                this.aiSuggestedCategory = previous.aiSuggestedCategory();
                this.aiHint = previous.aiHint();
                this.aiModel = previous.aiModel();
                this.aiDurationMs = previous.aiDurationMs();
                this.aiConfidence = previous.aiConfidence();
                this.aiJudgment = previous.aiJudgment();
                this.aiSimilarityScore = previous.aiSimilarityScore();
                this.aiEnhancedDescription = previous.aiEnhancedDescription();
                this.aiSuggestion = previous.aiSuggestion();
                addAll(missingFields, previous.missingFields());
                addAll(amountFormatErrors, previous.amountFormatErrors());
                abnormalReasons.addAll(previous.abnormalReasons());
            }
        }

        private static void addAll(List<String> target, String csv) {
            if (csv != null && !csv.isBlank()) {
                target.addAll(List.of(csv.split(",", -1)));
            }
        }
    }
}
