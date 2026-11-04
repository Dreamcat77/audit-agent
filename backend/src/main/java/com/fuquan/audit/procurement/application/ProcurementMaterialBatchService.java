package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.common.domain.RmbAmountParser;
import com.fuquan.audit.procurement.application.port.*;
import com.fuquan.audit.procurement.domain.*;
import com.fuquan.audit.blacklist.application.BlacklistBatchCheckService;
import java.io.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

/** F10/F11 material orchestration. Per-file durable content identities are also the retry identities. */
@Service
public class ProcurementMaterialBatchService {
    private static final Set<String> REVIEW_FIELDS = Set.of("projectName", "projectNo", "departmentName",
            "procurementType", "budgetAmount", "subjectCode", "subjectName", "procurementMethod",
            "requirementDescription", "applicantName", "purchaserName", "agencyName", "supplierName",
            "quoteAmount", "ceilingAmount", "productDescription");
    private final ProcurementTenderDocumentService documents;
    private final ProcurementTenderRuleScanService scans;
    private final ProcurementReviewMaterialService sheets;
    private final ProcurementCollusionReviewService collusion;
    private final ProcurementCollusionRepository bidders;
    private final ProcurementTenderDocumentRepository sources;
    private final ProcurementQuoteCheckService quotes;
    private final ProcurementReviewBlacklistService blacklist;
    private final ProcurementReviewWorkflowService workflow;
    private final ProcurementMaterialTransactions transactions;
    private final Clock clock;
    private final ProcurementReviewReportService reports;
    private final Semaphore capacity = new Semaphore(2);
    private final Set<UUID> activeProjects = ConcurrentHashMap.newKeySet();
    private MaterialFieldAiPort fieldAi;

    public ProcurementMaterialBatchService(ProcurementTenderDocumentService documents, ProcurementTenderRuleScanService scans,
            ProcurementReviewMaterialService sheets, ProcurementCollusionReviewService collusion, ProcurementCollusionRepository bidders,
            ProcurementTenderDocumentRepository sources, ProcurementQuoteCheckService quotes,
            ProcurementReviewBlacklistService blacklist, ProcurementReviewWorkflowService workflow,
            ProcurementMaterialTransactions transactions, Clock clock, ProcurementReviewReportService reports) {
        this.documents=documents; this.scans=scans; this.sheets=sheets; this.collusion=collusion; this.bidders=bidders;
        this.sources=sources; this.quotes=quotes; this.blacklist=blacklist; this.workflow=workflow;
        this.transactions=transactions; this.clock=clock;
        this.reports=reports;
    }

    /** Optional on local profiles; customer/dev profiles wire the durable AI adapter. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFieldAi(MaterialFieldAiPort fieldAi) { this.fieldAi = fieldAi; }
    public record Upload(String name, String contentType, long size, StreamSource source) {}
    @FunctionalInterface public interface StreamSource { InputStream open() throws IOException; }
    public record FileResult(String fileName, String kind, String status, String message,
            ProcurementTenderDocumentService.TenderDocumentResult document,
            ProcurementTenderRuleScanService.ScanResult scan) {}
    public record Result(List<FileResult> files, ProcurementQuoteCheck quote,
            ProcurementCollusionReviewService.ReviewData collusion,
            BlacklistBatchCheckService.BatchResult blacklist, List<String> notices,
            ProcurementReviewReportService.ReportData report) {
        public Result(List<FileResult> files, ProcurementQuoteCheck quote, ProcurementCollusionReviewService.ReviewData collusion,
                BlacklistBatchCheckService.BatchResult blacklist, List<String> notices) {
            this(files, quote, collusion, blacklist, notices, null);
        }
    }

    /** Save the whole submitted batch, then automatically analyze the durable project scope. */
    public Result stage(UUID projectId, List<Upload> uploads, String actor, boolean canQuote) {
        validate(uploads, canQuote);
        enter(projectId);
        try {
            workflow.start(projectId);
            var failures = new ArrayList<FileResult>();
            for (var upload : uploads) {
                try {
                    validateFile(upload, canQuote);
                    try (var input = upload.source().open()) {
                    if (Set.of("xls", "xlsx").contains(extension(upload.name()))) {
                        var source = documents.storeSpreadsheet(projectId, upload.name(), upload.contentType(), input, actor, transactions);
                        if (!"AVAILABLE".equals(source.status())) documents.recordSpreadsheet(projectId, source.id(), "QUOTE_STAGED");
                    } else documents.uploadMaterial(projectId, upload.name(), upload.contentType(), input, actor, transactions);
                    }
                } catch (BusinessException | IOException ex) {
                    failures.add(new FileResult(upload.name(), "UNKNOWN", "INCOMPLETE",
                            ex instanceof BusinessException business ? business.getMessage() : "此文件保存状态待核对，请重试同一文件", null, null));
                }
            }
            var saved = documents.currentMaterials(projectId);
            if (saved.isEmpty()) return new Result(List.copyOf(failures), null, null,
                    blacklist.findStoredResult(projectId), List.of("材料处理结果：暂未识别"));
            var ids = saved.stream().map(ProcurementTenderDocumentService.TenderDocumentResult::id).toList();
            var processed = analyzeSavedWithin(projectId, ids, actor, canQuote);
            var results = new ArrayList<>(processed.files());
            results.addAll(failures);
            return new Result(List.copyOf(results), processed.quote(), processed.collusion(),
                    processed.blacklist(), processed.notices(), processed.report());
        } finally { leave(projectId); }
    }

    /** F10/F11: transfer durable intake references after explicit user confirmation; no second upload. */
    public List<UUID> reuseIntakes(UUID projectId,
            List<com.fuquan.audit.common.material.domain.MaterialIntake> intakes, String actor) {
        if (intakes.isEmpty() || intakes.size() > 100)
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "请先上传采购材料");
        for (var intake : intakes) {
            if (!actor.equals(intake.actorId()) || !"PARSED".equals(intake.status()))
                throw new BusinessException(BusinessErrorCode.CONFLICT, "材料归属或提取状态尚未确认");
            validateFile(new Upload(intake.fileName(), intake.mediaType(), intake.fileSize(), () -> null), true);
        }
        enter(projectId);
        try {
            workflow.start(projectId);
            var ids = new ArrayList<UUID>();
            for (var intake : intakes)
                ids.add(documents.reuseIntake(projectId, intake, actor, transactions).id());
            var scope = documents.currentMaterials(projectId).stream()
                    .map(ProcurementTenderDocumentService.TenderDocumentResult::id).toList();
            // A retry must finish importing ALL original materials, even after partial
            // registration. Keep subsequently appended files in this explicit command's scope.
            if (!scope.containsAll(ids)) throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "部分新建材料未出现在当前清单中，请核对同名材料，已停止分析");
            requireScope(documents.currentMaterials(projectId), scope);
            return scope;
        } finally { leave(projectId); }
    }

    /** The submitted source IDs freeze the user's scope; a concurrent upload must never be silently included. */
    public Result analyzeSaved(UUID projectId, List<UUID> sourceIds, String actor, boolean canQuote) {
        enter(projectId);
        try { return analyzeSavedWithin(projectId, sourceIds, actor, canQuote); }
        finally { leave(projectId); }
    }

    private Result analyzeSavedWithin(UUID projectId, List<UUID> sourceIds, String actor, boolean canQuote) {
            var saved = documents.currentMaterials(projectId);
            requireScope(saved, sourceIds);
            if (!canQuote && saved.stream().anyMatch(d -> Set.of("xls", "xlsx").contains(d.fileExtension())))
                throw new BusinessException(BusinessErrorCode.FORBIDDEN, "当前账号没有报价分析权限");
            try {
                var report = reports.requireLatestForMaterials(projectId, sourceIds);
                var result = reopen(projectId);
                return new Result(result.files(), result.quote(), result.collusion(), result.blacklist(), result.notices(), report);
            } catch (BusinessException ex) {
                if (ex.errorCode() != BusinessErrorCode.NOT_FOUND && ex.errorCode() != BusinessErrorCode.CONFLICT) throw ex;
            }
            workflow.start(projectId);
            transactions.prepare(projectId);
            var results = new ArrayList<FileResult>();
            boolean spreadsheet = false;
            for (var source : saved) {
                if (Set.of("xls", "xlsx").contains(source.fileExtension())) {
                    spreadsheet = true;
                    results.add(analyzeSpreadsheet(projectId,source,actor));
                } else results.add(analyze(projectId, source, actor));
            }
            var quoteNotices = canQuote && !spreadsheet ? analyzeDocumentQuotes(projectId, results) : List.<String>of();
            var result = finish(projectId, results);
            var notices = new ArrayList<>(result.notices());
            notices.addAll(quoteNotices);
            requireScope(documents.currentMaterials(projectId), sourceIds);
            if (results.stream().anyMatch(r -> "PROCESSING".equals(r.status()))) {
                notices.add("部分材料仍在处理中；已完成内容会保留，处理结束后可查看完整结果。");
                return new Result(result.files(),result.quote(),result.collusion(),result.blacklist(),List.copyOf(notices));
            }
            var report = reports.generateForMaterials(projectId, sourceIds);
            if (results.stream().anyMatch(r -> Set.of("PARSE_FAILED", "ANALYSIS_FAILED",
                    "ANALYSIS_UNCONFIRMED", "INCOMPLETE", "PARSE_INCOMPLETE").contains(r.status()))) {
                notices.add("报告已生成；未能读取或确认的内容没有纳入判断，也不会据此认定为没有风险。");
            }
            return new Result(result.files(), result.quote(), result.collusion(), result.blacklist(),
                    List.copyOf(notices), report);
    }

    static void requireScope(List<ProcurementTenderDocumentService.TenderDocumentResult> saved, List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 200 || new HashSet<>(ids).size() != ids.size())
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "请先上传采购材料");
        if (!new HashSet<>(ids).equals(saved.stream().map(ProcurementTenderDocumentService.TenderDocumentResult::id).collect(java.util.stream.Collectors.toSet())))
            throw new BusinessException(BusinessErrorCode.CONFLICT, "材料清单已变化，请核对当前清单后开始分析");
    }

    public Result upload(UUID projectId, List<Upload> uploads, String actor, boolean canQuote) {
        return stage(projectId, uploads, actor, canQuote);
    }

    private FileResult analyze(UUID projectId, ProcurementTenderDocumentService.TenderDocumentResult source, String actor) {
        if (!"AVAILABLE".equals(source.status()) || source.parseResult() == null)
            return unreadable(source);
        source = enrichFields(projectId, source);
        final UUID sourceId = source.id();
        var kind = ProcurementMaterialClassifier.classify(source.fileName(), source.parseResult().fullText());
        if (kind.kind().equals("QUOTATION")) {
            return new FileResult(source.fileName(), "QUOTATION", "COMPLETED_WITH_PLACEHOLDER",
                    quotationSummary(source), source, null);
        }
        if (kind.kind().equals("GENERAL") || kind.kind().equals("OTHER")) {
            return new FileResult(source.fileName(), kind.kind(), "COMPLETED_WITH_PLACEHOLDER",
                    materialSummary(source), source, null);
        }
        if (kind.kind().equals("BIDDER_UNCONFIRMED")) {
            var assigned = bidders.findDocuments(projectId).stream().filter(d -> d.id().equals(sourceId)).findFirst();
            if (assigned.isPresent()) return new FileResult(source.fileName(), "BIDDER", "BIDDER_RECOGNIZED",
                    "投标人：" + assigned.get().bidderName(), source, null);
            String supplier = recognizedSupplier(source);
            if (supplier == null) return new FileResult(source.fileName(), "OTHER", "COMPLETED_WITH_PLACEHOLDER",
                    "材料：其他；投标人：暂未识别；围串标信息：暂未识别", source, null);
            registerBidder(projectId, source, supplier, kind.ipAddress(), kind.handlerName(), actor);
            return new FileResult(source.fileName(), "BIDDER", "BIDDER_RECOGNIZED",
                    "投标人：" + supplier, source, null);
        }
        if (kind.kind().equals("BIDDER")) {
            registerBidder(projectId, source, kind.bidderName(), kind.ipAddress(), kind.handlerName(), actor);
            return new FileResult(source.fileName(), kind.kind(), "BIDDER_RECOGNIZED", "已识别投标人：" + kind.bidderName(), source, null);
        }
        // Reopening stays read-only; the upload command persists the completed result.
        if (source.parseEngine()!=null && source.parseEngine().endsWith("_REVIEWED_AVAILABLE")) {
            var findings=scans.findFindings(projectId,source.id());
            var scan=new ProcurementTenderRuleScanService.ScanResult(projectId,source.id(),findings.size(),findings,
                    ProcurementTenderRuleScanService.AiStatus.valueOf(source.parseEngine().split("_REVIEWED_")[1]));
            return new FileResult(source.fileName(),"TENDER","DOCUMENT_REVIEWED","已重新打开保存的审查结果",source,scan);
        }
        var scan = scans.scan(projectId, source.id());
        boolean complete = scan != null && scan.aiStatus() == ProcurementTenderRuleScanService.AiStatus.AVAILABLE;
        return new FileResult(source.fileName(), "TENDER", complete ? "DOCUMENT_REVIEWED" : "INCOMPLETE",
                complete ? "已完成规则及 AI 材料审查" : "规则检查结果已保存，AI 语义审查尚未完成", source, scan);
    }

    private ProcurementTenderDocumentService.TenderDocumentResult enrichFields(UUID projectId,
            ProcurementTenderDocumentService.TenderDocumentResult source) {
        var current = source.parseResult();
        if (current == null || current.fieldExtraction() != null) return source;
        if (fieldAi == null) {
            return persistExtractionState(projectId, source, current.extractedFields(),
                    new ProcurementTenderParseResult.FieldExtractionState("UNAVAILABLE", null, null,
                            "AI_NOT_CONFIGURED", 0, 0));
        }
        int inputChars = source.parseResult().segments().stream()
                .mapToInt(s -> s.text().codePointCount(0, s.text().length())).sum();
        try (var span = PipelineDiagnostics.start("PROCUREMENT", "MATERIAL_FIELD_ENRICHMENT", source.id(),
                Map.of("businessType", "PROCUREMENT_REVIEW", "inputChars", inputChars,
                        "segmentCount", source.parseResult().segments().size()))) {
            List<MaterialFieldAiPort.Fact> mergedFacts;
            ProcurementTenderParseResult.FieldExtractionState state;
            int evidenceRejected;
            try {
                var result = fieldAi.extract("PROCUREMENT_REVIEW", source.id().toString(), source.sha256(),
                        source.parseResult().segments().stream()
                                .map(s -> new MaterialFieldAiPort.Segment(s.sourceLocator(), s.text())).toList());
                var sourceText = source.parseResult().segments().stream().collect(java.util.stream.Collectors.toMap(
                        ProcurementTenderParseResult.Segment::sourceLocator, ProcurementTenderParseResult.Segment::text,
                        (left, right) -> left));
                var candidates = new ArrayList<MaterialFieldAiPort.Fact>();
                if (current.extractedFields() != null) candidates.addAll(current.extractedFields());
                if (result != null && result.rows() != null) result.rows().stream().filter(Objects::nonNull)
                        .filter(row -> row.facts() != null).flatMap(row -> row.facts().stream())
                        .filter(Objects::nonNull).forEach(candidates::add);
                var facts = candidates.stream().filter(fact -> REVIEW_FIELDS.contains(fact.field())
                        && fact.locator() != null && sourceText.containsKey(fact.locator())
                        && fact.value() != null && !fact.value().isBlank()
                        && fact.excerpt() != null && !fact.excerpt().isBlank() && fact.excerpt().length() <= 2400
                        && sourceText.get(fact.locator()).contains(fact.excerpt())
                        && fact.excerpt().contains(fact.value())
                        && amountRoleConsistent(fact)).toList();
                mergedFacts = facts.stream().distinct().toList();
                int rejected = candidates.size() - mergedFacts.size();
                int issueCount = result == null || result.issues() == null ? 0 : result.issues().size();
                state = new ProcurementTenderParseResult.FieldExtractionState(
                        mergedFacts.isEmpty() ? "EMPTY" : "AVAILABLE",
                        result == null ? null : result.model(),
                        result == null ? null : result.promptVersion(),
                        mergedFacts.isEmpty() && rejected + issueCount > 0 ? "EVIDENCE_FILTERED" : null,
                        candidates.size(), mergedFacts.size());
                evidenceRejected = rejected + issueCount;
            } catch (RuntimeException failure) {
                span.fail(failure);
                String status = failure instanceof com.fuquan.audit.common.material.application.port.MaterialExtractionUnknown
                        || failure instanceof com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated
                        ? "UNKNOWN" : "UNAVAILABLE";
                String reason = failure instanceof com.fuquan.audit.common.material.application.port.MaterialExtractionUnknown unknown
                        ? unknown.reasonCode()
                        : failure instanceof com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated terminated
                            ? terminated.reasonCode() : "AI_EXTRACTION_UNAVAILABLE";
                return persistExtractionState(projectId, source, current.extractedFields(),
                        new ProcurementTenderParseResult.FieldExtractionState(status, null, null, reason, 0, 0));
            }
            // Persistence failures are not provider failures and must never be hidden or retried as AI work.
            var saved = persistExtractionState(projectId, source, mergedFacts, state);
            span.outcome(mergedFacts.isEmpty() ? "NO_GROUNDED_FIELDS" : "FIELDS_SAVED", Map.of(
                    "factCount", mergedFacts.size(), "evidenceRejectedCount", evidenceRejected,
                    "model", Objects.toString(state.model(), "UNAVAILABLE"),
                    "promptVersion", Objects.toString(state.promptVersion(), "UNAVAILABLE")));
            return saved;
        }
    }

    private ProcurementTenderDocumentService.TenderDocumentResult persistExtractionState(UUID projectId,
            ProcurementTenderDocumentService.TenderDocumentResult source,
            List<MaterialFieldAiPort.Fact> fields, ProcurementTenderParseResult.FieldExtractionState state) {
        try (var span = PipelineDiagnostics.start("PROCUREMENT", "MATERIAL_FIELDS_PERSIST", source.id(),
                Map.of("factCount", fields == null ? 0 : fields.size()))) {
            try {
                var saved = documents.recordFieldExtraction(projectId, source.id(), fields, state);
                span.outcome("SAVED", Map.of());
                return saved;
            } catch (RuntimeException failure) {
                span.fail(failure);
                throw failure;
            }
        }
    }

    private static FileResult unreadable(ProcurementTenderDocumentService.TenderDocumentResult source) {
        if ("PARSE_FAILED".equals(source.status()))
            return new FileResult(source.fileName(), "UNKNOWN", "PARSE_FAILED",
                    "文件读取未完成，原文件已保留；其他可读取的材料仍会参与分析", source, null);
        if ("UPLOADING".equals(source.status()))
            return new FileResult(source.fileName(), "UNKNOWN", "PROCESSING",
                    "文件正在保存或解析，请稍候", source, null);
        return new FileResult(source.fileName(), "UNKNOWN", "PARSE_INCOMPLETE",
                "此文件尚无可用正文，未将其作为无风险材料", source, null);
    }

    static boolean amountRoleConsistent(MaterialFieldAiPort.Fact fact) {
        if (fact == null || fact.field() == null || fact.value() == null || fact.excerpt() == null) return false;
        String excerpt = java.text.Normalizer.normalize(fact.excerpt(), java.text.Normalizer.Form.NFKC);
        if (Set.of("purchaserName", "agencyName").contains(fact.field())) {
            String value = java.text.Normalizer.normalize(fact.value(), java.text.Normalizer.Form.NFKC);
            Set<String> purchaser = Set.of("采购人", "招标人", "采购单位");
            Set<String> agency = Set.of("招标代理机构", "采购代理机构", "代理机构");
            return fact.field().equals("purchaserName")
                    ? textHasRoleForValue(excerpt, value, purchaser, agency)
                    : textHasRoleForValue(excerpt, value, agency, purchaser);
        }
        if ("supplierName".equals(fact.field())) {
            String value = java.text.Normalizer.normalize(fact.value(), java.text.Normalizer.Form.NFKC)
                    .replaceAll("[\\s。；;，,、:：]+$", "");
            Set<String> supplier = Set.of("供应商", "供应商名称", "报价单位", "报价单位名称", "报价人",
                    "报价人名称", "投标人", "投标单位", "响应供应商");
            Set<String> otherRole = Set.of("采购人", "招标人", "采购单位", "中标人", "中标单位",
                    "项目名称", "经办部门");
            return textHasRoleForValue(excerpt, value, supplier, otherRole);
        }
        if (!Set.of("budgetAmount", "quoteAmount", "ceilingAmount").contains(fact.field())) return true;
        String value = java.text.Normalizer.normalize(fact.value(), java.text.Normalizer.Form.NFKC);
        Set<String> budget = Set.of("预算金额", "采购预算", "采购预算金额", "批复参考金额", "批复金额");
        Set<String> ceiling = Set.of("最高限价", "采购控制价", "预算控制价");
        Set<String> quote = Set.of("投标报价", "投标总价", "响应报价", "响应总价", "报价总额", "报价金额",
                "报价合计", "含税报价合计", "含税报价总额", "含税总价", "价税合计");
        return switch (fact.field()) {
            case "budgetAmount" -> amountHasRole(excerpt, value, budget, ceiling, quote);
            case "ceilingAmount" -> amountHasRole(excerpt, value, ceiling, budget, quote);
            case "quoteAmount" -> amountHasRole(excerpt, value, quote, budget, ceiling);
            default -> true;
        };
    }

    private static boolean amountHasRole(String excerpt, String value, Set<String> required,
            Set<String> excludedA, Set<String> excludedB) {
        return textHasRoleForValue(excerpt, value, required, union(excludedA, excludedB));
    }

    private static boolean textHasRoleForValue(String excerpt, String value, Set<String> required,
            Set<String> excluded) {
        if (value.isBlank()) return false;
        int from = 0;
        while ((from = excerpt.indexOf(value, from)) >= 0) {
            int start = from;
            while (start > 0 && !isAmountClauseBoundary(excerpt.charAt(start - 1))) start--;
            int end = from + value.length();
            while (end < excerpt.length() && !isAmountClauseBoundary(excerpt.charAt(end))) end++;
            String clause = excerpt.substring(start, end);
            if (required.stream().anyMatch(clause::contains)
                    && excluded.stream().noneMatch(clause::contains)) return true;
            from += value.length();
        }
        return false;
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        var values = new HashSet<>(left);
        values.addAll(right);
        return values;
    }

    private static boolean isAmountClauseBoundary(char value) {
        return switch (value) {
            case '\r', '\n', '。', '；', ';', '，', ',', '、' -> true;
            default -> false;
        };
    }

    private String recognizedSupplier(ProcurementTenderDocumentService.TenderDocumentResult source) {
        var fields = source.parseResult().extractedFields().stream()
                .filter(f -> "supplierName".equals(f.field()) && amountRoleConsistent(f))
                .map(MaterialFieldAiPort.Fact::value).map(this::cleanSupplier).filter(Objects::nonNull)
                .distinct().toList();
        if (fields.size() == 1) return fields.get(0);
        return cleanSupplier(ProcurementMaterialClassifier.field(source.parseResult().fullText(),
                "供应商(?:名称)?|报价单位(?:名称)?|报价人(?:名称)?|投标人(?:名称)?|投标单位(?:名称)?|响应供应商", 500));
    }

    private BigDecimal recognizedQuoteAmount(ProcurementTenderDocumentService.TenderDocumentResult source) {
        var values = source.parseResult().extractedFields().stream()
                .filter(f -> "quoteAmount".equals(f.field()) && amountRoleConsistent(f))
                .map(MaterialFieldAiPort.Fact::value)
                .map(com.fuquan.audit.common.domain.RmbAmountParser::parse)
                .filter(Optional::isPresent).map(Optional::get).map(BigDecimal::stripTrailingZeros).distinct().toList();
        if (values.size() == 1) return values.get(0);
        return ProcurementBidQuotationExtractor.extract(source.parseResult().fullText());
    }

    private String cleanSupplier(String value) {
        if (value == null) return null;
        var cleaned = value.replaceAll("[\\s。；;，,、:：]+$", "").trim();
        return cleaned.isBlank() || cleaned.length() > 500 ? null : cleaned;
    }

    private String quotationSummary(ProcurementTenderDocumentService.TenderDocumentResult source) {
        String supplier = recognizedSupplier(source);
        BigDecimal amount = recognizedQuoteAmount(source);
        return "供应商：" + Objects.toString(supplier, "暂未识别") + "；报价金额："
                + (amount == null ? "暂未识别" : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "元");
    }

    private String materialSummary(ProcurementTenderDocumentService.TenderDocumentResult source) {
        var fields = source.parseResult().extractedFields();
        String project = fields.stream().filter(f -> "projectName".equals(f.field()))
                .map(MaterialFieldAiPort.Fact::value).findFirst().orElse("暂未识别");
        return "材料：其他；项目名称：" + project + "；供应商：暂未识别；报价金额：暂未识别";
    }

    private List<String> analyzeDocumentQuotes(UUID projectId, List<FileResult> results) {
        var quotationResults = results.stream().filter(r -> "QUOTATION".equals(r.kind()))
                .filter(r -> r.document() != null && r.document().parseResult() != null).toList();
        if (quotationResults.isEmpty()) {
            analyzeBidPrices(projectId);
            return quotes.findByBatchId(projectId.toString()).isPresent()
                    ? List.of() : List.of("询比价：暂未识别");
        }
        var inputs = new ArrayList<ProcurementQuoteCheck.QuoteInput>();
        var materials = new ArrayList<ProcurementQuoteCheckService.MaterialQuoteSource>();
        var seenSuppliers = new HashSet<String>();
        int row = 1;
        boolean incomplete = false;
        for (var result : quotationResults) {
            var source = result.document();
            String supplier = recognizedSupplier(source);
            BigDecimal amount = recognizedQuoteAmount(source);
            if (supplier == null || amount == null || !seenSuppliers.add(supplier)) {
                incomplete = true;
                continue;
            }
            inputs.add(new ProcurementQuoteCheck.QuoteInput(row++, supplier, amount, null));
            materials.add(new ProcurementQuoteCheckService.MaterialQuoteSource(source.id(), source.sha256()));
        }
        if (inputs.isEmpty()) return List.of("询比价：暂未识别；供应商或报价金额缺失或存在重复");
        try {
            quotes.analyzeMaterialQuoteBatch(projectId, materials, inputs);
            var notices = new ArrayList<String>();
            if (incomplete || inputs.size() != quotationResults.size())
                notices.add("部分报价缺少可核实的供应商或金额，已显示为暂未识别");
            if (inputs.size() == 1) notices.add("有效供应商报价不足两家，横向比价：暂未识别");
            return List.copyOf(notices);
        } catch (BusinessException failure) {
            return List.of("询比价：暂未识别；分析结果暂未生成");
        }
    }

    private FileResult analyzeSpreadsheet(UUID projectId, ProcurementTenderDocumentService.TenderDocumentResult source, String actor) {
        try {
            var result=sheets.analyzeSaved(projectId,source.id(),actor);
            return new FileResult(source.fileName(),"NON_QUOTATION".equals(result.status())?"GENERAL":"QUOTATION",
                    result.status(),result.message(),result.document(),null);
        } catch(IOException failure) {
            var saved=documents.requireMaterial(projectId,source.id());
            return new FileResult(source.fileName(),"UNKNOWN","PARSE_FAILED",spreadsheetFailure(saved.parseError()),saved,null);
        } catch(com.fuquan.audit.common.material.application.port.MaterialExtractionUnknown unknown) {
            var saved=documents.requireMaterial(projectId,source.id());
            return new FileResult(source.fileName(),"UNKNOWN","ANALYSIS_UNCONFIRMED","模型调用结果尚未确认，原文件已保留；请核对服务日志，不会自动重复调用",saved,null);
        } catch(BusinessException failure) {
            if(Set.of(BusinessErrorCode.FORBIDDEN,BusinessErrorCode.UNAUTHENTICATED).contains(failure.errorCode()))throw failure;
            var saved=documents.requireMaterial(projectId,source.id());
            return new FileResult(source.fileName(),"UNKNOWN","ANALYSIS_FAILED","报价分析失败，请核对报价是否完整及项目预算是否已填写；原文件已保留",saved,null);
        }
    }

    static String spreadsheetFailure(String code) {
        if(code==null)return "解析失败：无法完整读取工作表，原文件已保留";
        if(code.contains("LIMIT"))return "解析失败：文件内容超过安全处理上限，尚未完成全文分析；请将材料按工作表或内容拆分";
        if(code.equals("QUOTATION_FIELDS_INCOMPLETE")||code.equals("QUOTATION_ROLE_UNPROVEN"))
            return "解析失败：材料含有报价线索，但尚不能完整对应供应商与报价金额，请核对原表";
        if(code.equals("QUOTATION_AMOUNT_INVALID"))return "解析失败：报价金额存在歧义或无法准确识别，请核对原表";
        if(code.startsWith("MODEL_"))return "解析失败：模型返回的信息未通过原文核对，未将其作为报价使用";
        return "解析失败：工作表内容损坏、受保护或不能完整读取，原文件已保留";
    }

    private void registerBidder(UUID projectId, ProcurementTenderDocumentService.TenderDocumentResult parsed,
            String bidder, String ip, String handler, String actor) {
        var stored = sources.findById(parsed.id()).filter(d -> d.projectId().equals(projectId)).orElseThrow();
        transactions.registerBidder(new ProcurementCollusionDocument(parsed.id(), projectId, bidder, ip, handler,
                parsed.fileName(), parsed.fileExtension(), parsed.contentType(), parsed.fileSize(), parsed.sha256(),
                stored.storageKey(), parsed.parseResult().fullText(), stored.createdTime()), actor);
    }

    public Result confirmBidder(UUID projectId, UUID sourceId, String bidder, String actor) {
        if (bidder == null || bidder.isBlank() || bidder.length() > 500) throw new BusinessException(
                BusinessErrorCode.INVALID_ARGUMENT, "请填写有效的投标人名称");
        enter(projectId);
        try {
            var source = documents.requireMaterial(projectId, sourceId);
            if (source.parseResult() == null || !"AVAILABLE".equals(source.status())) throw new BusinessException(
                    BusinessErrorCode.CONFLICT, "材料尚未完成解析");
            var kind = ProcurementMaterialClassifier.classify(source.fileName(), source.parseResult().fullText());
            if (!kind.kind().equals("BIDDER_UNCONFIRMED")) throw new BusinessException(
                    BusinessErrorCode.CONFLICT, "此材料不需要确认投标人");
            transactions.prepare(projectId);
            registerBidder(projectId, source, bidder.trim(), kind.ipAddress(), kind.handlerName(), actor);
            return reopen(projectId);
        } finally { leave(projectId); }
    }

    /** Read existing facts only. Refresh never starts AI work, generates keys, or writes blacklist misses. */
    public Result reopen(UUID projectId) {
        var records = current(projectId);
        var assessment = bidders.findLatestAssessment(projectId).isPresent() ? collusion.requireLatest(projectId) : null;
        return new Result(records, quotes.findByBatchId(projectId.toString()).orElse(null), assessment, blacklist.findStoredResult(projectId), List.of());
    }

    private List<FileResult> current(UUID projectId) {
        var assigned = bidders.findDocuments(projectId).stream().collect(java.util.stream.Collectors.toMap(
                ProcurementCollusionDocument::id, ProcurementCollusionDocument::bidderName, (a,b) -> a));
        return documents.currentMaterials(projectId).stream().map(d -> {
            if (Set.of("xls", "xlsx").contains(d.fileExtension())) {
                String engine=Objects.toString(d.parseEngine(),"");
                if(engine.equals("QUOTE_PARSE_FAILED") || d.status().equals("PARSE_FAILED"))
                    return new FileResult(d.fileName(),"UNKNOWN","PARSE_FAILED",spreadsheetFailure(d.parseError()),d,null);
                if(engine.equals("QUOTE_PROCESSING"))
                    return new FileResult(d.fileName(),"UNKNOWN","PROCESSING","正在读取材料并分析，请稍候",d,null);
                if(engine.equals("QUOTE_UNKNOWN"))
                    return new FileResult(d.fileName(),"UNKNOWN","ANALYSIS_UNCONFIRMED","模型调用结果尚未确认，原文件已保留；不会自动重复调用",d,null);
                if(Set.of("QUOTE_STAGED","QUOTE_PENDING").contains(engine))
                    return new FileResult(d.fileName(),"UNKNOWN","READY","文件已保存，等待分析",d,null);
                if(engine.equals("QUOTE_ANALYSIS_FAILED"))
                    return new FileResult(d.fileName(),"UNKNOWN","ANALYSIS_FAILED","报价分析失败，请核对报价及项目预算；原文件已保留",d,null);
                if(engine.equals("NON_QUOTATION"))return new FileResult(d.fileName(),"GENERAL","NON_QUOTATION",ProcurementSpreadsheetMaterialService.message(engine),d,null);
                if(engine.equals("QUOTE_ANALYZED"))return new FileResult(d.fileName(),"QUOTATION",engine,"报价材料及分析结果已保存",d,null);
                return new FileResult(d.fileName(),"OTHER","COMPLETED_WITH_PLACEHOLDER","材料：其他；报价信息：暂未识别",d,null);
            }
            if (!"AVAILABLE".equals(d.status()) || d.parseResult() == null)
                return unreadable(d);
            var c = ProcurementMaterialClassifier.classify(d.fileName(), d.parseResult().fullText());
            if (assigned.containsKey(d.id())) return new FileResult(d.fileName(), "BIDDER", "BIDDER_RECOGNIZED", "投标人：" + assigned.get(d.id()), d, null);
            if (c.kind().equals("BIDDER_UNCONFIRMED")) return new FileResult(d.fileName(), "OTHER", "COMPLETED_WITH_PLACEHOLDER", "材料：其他；投标人：暂未识别", d, null);
            if (c.kind().equals("BIDDER")) return new FileResult(d.fileName(), c.kind(), "READY", "已上传，待统一分析", d, null);
            if (!c.kind().equals("TENDER")) return new FileResult(d.fileName(), c.kind(),
                    "COMPLETED_WITH_PLACEHOLDER", materialSummary(d), d, null);
            var findings = scans.findFindings(projectId, d.id());
            boolean attempted = d.parseEngine() != null && d.parseEngine().contains("_REVIEWED_");
            boolean complete = attempted && d.parseEngine().endsWith("_REVIEWED_AVAILABLE");
            boolean legacy = d.parseEngine() == null || !d.parseEngine().startsWith("MATERIAL_");
            var ai = attempted ? ProcurementTenderRuleScanService.AiStatus.valueOf(d.parseEngine().split("_REVIEWED_")[1]) : null;
            return new FileResult(d.fileName(), "TENDER", attempted && !complete ? "INCOMPLETE" : complete || legacy ? "SAVED" : "READY",
                    attempted && !complete ? "规则检查结果已保存，AI 语义审查尚未完成"
                            : complete || legacy ? "已保存材料及已有审查结果" : "已上传，待统一分析", d,
                    attempted || legacy ? new ProcurementTenderRuleScanService.ScanResult(projectId, d.id(), findings.size(), findings,
                            ai == null ? ProcurementTenderRuleScanService.AiStatus.AI_UNAVAILABLE : ai) : null);
        }).toList();
    }

    private Result finish(UUID projectId, List<FileResult> results) {
        var notices = new ArrayList<String>();
        ProcurementCollusionReviewService.ReviewData assessment = null;
        var inputs = ProcurementCollusionReviewService.scoringDocuments(bidders.findDocuments(projectId));
        if (inputs.size() >= 2) {
            try { assessment = collusion.score(projectId); }
            catch (BusinessException ex) { notices.add("围串标分析未完成，请重新上传同一批材料继续处理"); }
        } else if (!inputs.isEmpty() || results.stream().anyMatch(r -> r.kind().startsWith("BIDDER")))
            notices.add("围串标分析需至少两家不同投标人的有效文件，目前材料不足，尚未执行");
        var quote = quotes.findByBatchId(projectId.toString()).orElse(null);
        BlacklistBatchCheckService.BatchResult checked = null;
        if (quote != null) {
            try { checked = blacklist.check(projectId); }
            catch (BusinessException ex) { notices.add("供应商黑名单比对尚未完成"); }
        }
        return new Result(List.copyOf(results), quote, assessment, checked, List.copyOf(notices));
    }

    private void enter(UUID projectId) {
        if (!capacity.tryAcquire()) throw new BusinessException(BusinessErrorCode.CONFLICT, "材料处理中，请稍后重试同一批文件");
        if (!activeProjects.add(projectId)) { capacity.release(); throw new BusinessException(BusinessErrorCode.CONFLICT, "此项目的材料正在处理，请稍后核对结果"); }
    }

    private void analyzeBidPrices(UUID projectId) {
        var inputs=ProcurementCollusionReviewService.scoringDocuments(bidders.findDocuments(projectId));
        if(inputs.size()<3) return;
        var amounts=inputs.stream().map(d->ProcurementBidQuotationExtractor.extract(d.parsedText())).toList();
        if(amounts.stream().anyMatch(Objects::isNull)) return;
        quotes.analyzeSourceTotals(projectId, inputs);
    }
    private void leave(UUID projectId) { activeProjects.remove(projectId); capacity.release(); }
    static void validate(List<Upload> files, boolean canQuote) {
        if (files == null || files.isEmpty() || files.stream().anyMatch(Objects::isNull))
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "请选择采购材料");
        if (!canQuote && files.stream().allMatch(f -> f.name() != null && Set.of("xls", "xlsx").contains(extension(f.name()))))
            throw new BusinessException(BusinessErrorCode.FORBIDDEN, "当前账号没有报价分析权限");
    }
    static void validateFile(Upload f, boolean canQuote) {
            if (!com.fuquan.audit.common.material.application.OfficeMaterialTypes.supports(f.name())
                    || f.size() <= 0 || f.size() > 500L*1024*1024)
                throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "请选择非空的办公文件或图片扫描件，单文件最大 500 MB，暂不支持压缩包");
            if (!canQuote && Set.of("xls", "xlsx").contains(extension(f.name()))) throw new BusinessException(BusinessErrorCode.FORBIDDEN, "当前账号没有报价分析权限");
    }
    private static String extension(String name) { return name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT); }
}
