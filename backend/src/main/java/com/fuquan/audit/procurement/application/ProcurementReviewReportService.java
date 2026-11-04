package com.fuquan.audit.procurement.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.blacklist.application.BlacklistQueryService;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementReviewReportRepository;
import com.fuquan.audit.procurement.application.port.ProcurementTenderDocumentRepository;
import com.fuquan.audit.procurement.application.port.ProcurementTenderFindingRepository;
import com.fuquan.audit.procurement.application.port.ProcurementCollusionRepository;
import com.fuquan.audit.procurement.domain.ProcurementCollusionAssessment;
import com.fuquan.audit.procurement.domain.ProcurementTenderFinding;
import com.fuquan.audit.procurement.domain.ProcurementSplitPurchaseAssessment;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import com.fuquan.audit.procurement.domain.ProcurementReviewReport;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 汇总采购项目已有审查事实，生成采购审查报告。 */
@Service
public class ProcurementReviewReportService {

    private static final DateTimeFormatter REPORT_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final ProcurementProjectQueryService projectQueryService;
    private final ProcurementAgentReviewSnapshotService agentReviewSnapshotService;
    private final ProcurementQuoteCheckService quoteCheckService;
    private final BlacklistQueryService blacklistQueryService;
    private final ProcurementReviewReportRepository reportRepository;
    private final ProcurementTenderDocumentRepository tenderDocumentRepository;
    private final ProcurementTenderFindingRepository tenderFindingRepository;
    private final ProcurementCollusionRepository collusionRepository;
    private final ProcurementReviewWorkflowService workflowService;
    private final ObjectMapper objectMapper;

    public ProcurementReviewReportService(
            ProcurementProjectQueryService projectQueryService,
            ProcurementAgentReviewSnapshotService agentReviewSnapshotService,
            ProcurementQuoteCheckService quoteCheckService,
            BlacklistQueryService blacklistQueryService,
            ProcurementReviewReportRepository reportRepository,
            ProcurementTenderDocumentRepository tenderDocumentRepository,
            ProcurementTenderFindingRepository tenderFindingRepository,
            ProcurementCollusionRepository collusionRepository,
            ProcurementReviewWorkflowService workflowService,
            ObjectMapper objectMapper) {
        this.projectQueryService = projectQueryService;
        this.agentReviewSnapshotService = agentReviewSnapshotService;
        this.quoteCheckService = quoteCheckService;
        this.blacklistQueryService = blacklistQueryService;
        this.reportRepository = reportRepository;
        this.tenderDocumentRepository = tenderDocumentRepository;
        this.tenderFindingRepository = tenderFindingRepository;
        this.collusionRepository = collusionRepository;
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReportData generate(UUID projectId) {
        return generateSnapshot(projectId, false);
    }

    private ReportData generateSnapshot(UUID projectId, boolean verifiedMaterialScope) {
        // Serialize report snapshot against material registration without holding locks during OCR/AI.
        workflowService.lockForSnapshot(projectId);
        ProcurementProject project = projectQueryService.requireById(projectId);
        ensureMaterialsReady(projectId, verifiedMaterialScope);
        ReportData data = assemble(project, null);
        if (verifiedMaterialScope) workflowService.recordVerifiedMaterialReport(projectId, "已根据已完成处理的材料生成参考报告");
        else workflowService.recordReportGeneratedIfStarted(projectId, "已生成采购审查报告");
        Instant now = Instant.now();
        ProcurementReviewReport metadata = new ProcurementReviewReport(
                UUID.randomUUID(), projectId,
                "PCR-" + REPORT_DATE.format(now) + "-"
                        + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                data.riskLevel(), now, data.overallConclusion(), serializeAgentReview(data.agentReview()), serializeSnapshot(data));
        reportRepository.save(metadata);
        return data.withMetadata(metadata);
    }

    @Transactional
    public ReportData generateForMaterials(UUID projectId, List<UUID> sourceIds) {
        workflowService.lockForSnapshot(projectId);
        var names = new java.util.HashSet<String>();
        var hashes = new java.util.HashSet<String>();
        var current = tenderDocumentRepository.findByProjectId(projectId).stream()
                .filter(d -> names.add(d.fileName()) && hashes.add(d.sha256())).map(d -> d.id())
                .collect(java.util.stream.Collectors.toSet());
        if (current.isEmpty() || sourceIds == null || !current.equals(new java.util.HashSet<>(sourceIds))) throw new BusinessException(
                BusinessErrorCode.CONFLICT, "材料清单已变化，请核对当前清单后重新分析");
        if (workflowService.require(projectId).steps().stream().anyMatch(s -> s.stepCode().equals("REPORT")
                && s.status().equals("COMPLETED"))) {
            try { return requireLatestForMaterials(projectId, sourceIds); }
            catch (BusinessException staleOrMissingSnapshot) {
                if (staleOrMissingSnapshot.errorCode() != BusinessErrorCode.NOT_FOUND
                        && staleOrMissingSnapshot.errorCode() != BusinessErrorCode.CONFLICT) throw staleOrMissingSnapshot;
            }
        }
        return generateSnapshot(projectId, true);
    }

    /** Read an existing report only when it is a snapshot of this exact material set. */
    @Transactional(readOnly = true)
    public ReportData requireLatestForMaterials(UUID projectId, List<UUID> sourceIds) {
        ReportData latest = requireLatest(projectId);
        if (sourceIds == null || sourceIds.isEmpty() || sourceIds.size() > 200
                || new java.util.HashSet<>(sourceIds).size() != sourceIds.size()
                || latest.metadata().reportSnapshotJson() == null) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "现有报告不是当前材料批次的结果，请重新整理本批材料");
        }
        try {
            ReportSnapshot snapshot = objectMapper.readValue(latest.metadata().reportSnapshotJson(), ReportSnapshot.class);
            var expected = sourceIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet());
            var actual = snapshot.materials().stream().map(material -> material.id().toString())
                    .collect(java.util.stream.Collectors.toSet());
            if (!expected.equals(actual) || actual.size() != snapshot.materials().size()) {
                throw new BusinessException(BusinessErrorCode.CONFLICT,
                        "现有报告对应的材料与当前批次不同，请重新整理本批材料");
            }
            return latest;
        } catch (JsonProcessingException | NullPointerException failure) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "现有报告未保存当前材料清单，请重新整理本批材料", failure);
        }
    }

    @Transactional(readOnly = true)
    public ReportData requireLatest(UUID projectId) {
        workflowService.ensureReportCompletedIfStarted(projectId);
        projectQueryService.requireById(projectId); // Preserve access/existence checks, never substitute current facts.
        ProcurementReviewReport metadata = reportRepository.findLatestByProjectId(projectId)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "采购审查报告不存在: " + projectId));
        if (metadata.agentReviewJson() == null || metadata.agentReviewJson().isBlank()) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "采购审查报告缺少已保存的预审快照，请重新生成报告");
        }
        return restoreSnapshot(metadata);
    }

    private String serializeSnapshot(ReportData data) {
        try {
            var materials = tenderDocumentRepository.findByProjectId(data.project().id()).stream()
                    .map(d -> new MaterialVersion(d.id(), d.sha256())).toList();
            return objectMapper.writeValueAsString(new ReportSnapshot(1, data.withMetadata(null), materials));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("采购报告快照序列化失败", exception);
        }
    }

    private String serializeAgentReview(ProcurementAgentReviewService.ReviewResult data) {
        try {return objectMapper.writeValueAsString(data);}
        catch(JsonProcessingException failure){throw new IllegalStateException("采购预审快照序列化失败",failure);}
    }

    private ReportData restoreSnapshot(ProcurementReviewReport metadata) {
        try {
            if (metadata.reportSnapshotJson() != null) {
                // Read typed decimals from the original JSON tokens. A tree can
                // normalize 150000.00 to 150000.0 and destroy persisted scale.
                var snapshot = objectMapper.readValue(metadata.reportSnapshotJson(), ReportSnapshot.class);
                if(snapshot == null)throw new IllegalArgumentException("invalid report shape");
                var data = snapshot.data();
                if (snapshot.snapshotVersion() != 1 || snapshot.materials() == null || data == null
                        || data.metadata() != null || data.project() == null || data.agentReview() == null
                        || !metadata.projectId().equals(data.project().id())
                        || !metadata.projectId().equals(data.agentReview().projectId())
                        || !java.util.Objects.equals(metadata.riskLevel(), data.riskLevel())
                        || !java.util.Objects.equals(metadata.overallConclusion(), data.overallConclusion()))
                    throw new IllegalArgumentException("report snapshot identity mismatch");
                return data.withMetadata(metadata);
            }
            // Old releases persisted only this section. Keep it readable without
            // manufacturing historical chapters from today's mutable repositories.
            var agent = objectMapper.readValue(metadata.agentReviewJson(), ProcurementAgentReviewService.ReviewResult.class);
            if(agent==null||agent.items()==null||!metadata.projectId().equals(agent.projectId()))
                throw new IllegalArgumentException("invalid legacy report identity");
            var historicalProject = new ProcurementProject(metadata.projectId(), null, "历史采购项目",
                    null, null, null, null, null, metadata.generatedTime());
            return new ReportData(metadata, historicalProject, agent, null, List.of(), List.of(), null, null,
                    metadata.riskLevel(), "旧版报告仅保存了预审结果，其余章节未保存历史快照，不能还原当时的完整报告。"
                    + "原报告结论：" + java.util.Objects.toString(metadata.overallConclusion(), "未保存"));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "采购审查报告快照无效，未读取当前数据替代历史结果，请核对报告", exception);
        }
    }

    public record MaterialVersion(UUID id, String sha256) {}
    public record ReportSnapshot(int snapshotVersion, ReportData data, List<MaterialVersion> materials) {}

    private ReportData assemble(ProcurementProject project, ProcurementReviewReport metadata) {
        ProcurementAgentReviewService.ReviewResult agentReview = agentReviewSnapshotService.find(project.id())
                .orElseGet(() -> agentReviewSnapshotService.refresh(project.id()));
        ProcurementQuoteCheck quoteCheck = quoteCheckService.findByBatchId(project.id().toString()).orElse(null);
        // F03 批量检查最多接收 1000 行，使用有界查询避免 DM8 分页参数溢出。
        List<BlacklistAlert> alerts = blacklistQueryService.findAlertPage(
                project.id().toString(), null, null, null, 1, 1000).items();
        List<ProcurementTenderFinding> tenderFindings = tenderFindings(project.id());
        ProcurementCollusionAssessment collusion = collusionRepository.findLatestAssessment(project.id()).orElse(null);
        ProcurementSplitPurchaseAssessment splitPurchase = collusionRepository
                .findLatestSplitPurchaseAssessment(project.id()).orElse(null);
        String riskLevel = highestRisk(agentReview.riskLevel(),
                quoteCheck == null ? null : quoteCheck.riskLevel(),
                alerts.stream().map(BlacklistAlert::alertLevel).toList(),
                tenderFindings.stream().map(ProcurementTenderFinding::riskLevel).toList(),
                collusion == null ? null : collusion.riskLevel(),
                splitPurchase == null ? null : splitPurchase.riskLevel());
        String conclusion = buildConclusion(agentReview, quoteCheck, alerts, tenderFindings, collusion, splitPurchase);
        if (quoteCheck == null && tenderDocumentRepository.findByProjectId(project.id()).stream()
                .anyMatch(d -> "NON_QUOTATION".equals(d.parseEngine())))
            conclusion = "已提取材料中的可用信息；材料未提供可核实的供应商报价，本次未执行比价，以下内容仅供参考。" + conclusion;
        if (tenderDocumentRepository.findByProjectId(project.id()).stream().anyMatch(d -> d.parseEngine() != null
                && d.parseEngine().contains("_REVIEWED_") && !d.parseEngine().endsWith("_REVIEWED_AVAILABLE")))
            conclusion = "招标文件的 AI 语义审查未完成，当前报告仅汇总已完成的规则检查及其他分析，不能据此认定招标文件不存在风险。" + conclusion;
        if (hasMaterialCoverageGap(project.id()))
            conclusion = "部分材料未能形成可核实内容，本报告只汇总已取得的依据；未分析部分不代表没有风险。" + conclusion;
        return new ReportData(metadata, project, agentReview, quoteCheck, alerts, tenderFindings, collusion,
                splitPurchase, riskLevel, conclusion);
    }

    private List<ProcurementTenderFinding> tenderFindings(UUID projectId) {
        var documents = tenderDocumentRepository.findByProjectId(projectId);
        // Compatibility for custom/test repositories predating multi-material retrieval.
        if (documents.isEmpty()) return tenderDocumentRepository.findLatestByProjectId(projectId)
                .map(document -> tenderFindingRepository.findByReviewId(projectId, document.id())).orElseGet(List::of);
        if (documents.size() > 200) throw new BusinessException(BusinessErrorCode.CONFLICT, "项目材料过多，不能生成不完整报告");
        var names = new java.util.HashSet<String>();
        var hashes = new java.util.HashSet<String>();
        return documents.stream().filter(d -> names.add(d.fileName()) && hashes.add(d.sha256()))
                .filter(d -> {
                    if (d.parsedContentJson() == null) return true;
                    try {
                        var parsed = objectMapper.readValue(d.parsedContentJson(), com.fuquan.audit.procurement.domain.ProcurementTenderParseResult.class);
                        return ProcurementMaterialClassifier.classify(d.fileName(), parsed.fullText()).kind().equals("TENDER");
                    } catch (JsonProcessingException ex) { throw new IllegalStateException("材料解析结果无法读取", ex); }
                }).flatMap(d -> tenderFindingRepository.findByReviewId(projectId, d.id()).stream()).toList();
    }

    private void ensureMaterialsReady(UUID projectId, boolean allowPartial) {
        var names = new java.util.HashSet<String>();
        var docs = tenderDocumentRepository.findByProjectId(projectId);
        if (docs.isEmpty()) return;
        if (docs.size() > 200) throw new BusinessException(BusinessErrorCode.CONFLICT, "项目材料过多，不能生成不完整报告");
        var assigned = collusionRepository.findDocuments(projectId).stream().map(com.fuquan.audit.procurement.domain.ProcurementCollusionDocument::id)
                .collect(java.util.stream.Collectors.toSet());
        for (var d : docs) {
            if (!names.add(d.fileName())) continue;
            if (d.status() != com.fuquan.audit.procurement.domain.ProcurementTenderDocumentStatus.AVAILABLE) {
                if (allowPartial && d.status() == com.fuquan.audit.procurement.domain.ProcurementTenderDocumentStatus.PARSE_FAILED) continue;
                throw new BusinessException(BusinessErrorCode.CONFLICT, "部分材料尚未完成处理，请稍后查看");
            }
            if (java.util.Set.of("xls","xlsx").contains(d.fileExtension())) {
                String engine = java.util.Objects.toString(d.parseEngine(), "");
                boolean terminalFailure = allowPartial && java.util.Set.of("QUOTE_PARSE_FAILED",
                        "QUOTE_ANALYSIS_FAILED", "QUOTE_UNKNOWN").contains(engine);
                if(!terminalFailure && !java.util.Set.of("QUOTE_ANALYZED","NON_QUOTATION","NOT_APPLICABLE").contains(engine))
                    throw new BusinessException(BusinessErrorCode.CONFLICT,"Excel 材料尚未完成分析，请核对材料状态");
                continue;
            }
            if (d.parsedContentJson() == null) continue;
            try {
                var parsed = objectMapper.readValue(d.parsedContentJson(), com.fuquan.audit.procurement.domain.ProcurementTenderParseResult.class);
                var kind = ProcurementMaterialClassifier.classify(d.fileName(), parsed.fullText()).kind();
                if (kind.startsWith("BIDDER") && !assigned.contains(d.id())) {
                    if (allowPartial) continue;
                    throw new BusinessException(BusinessErrorCode.CONFLICT,
                            "投标材料所属单位无法确认，已跳过相关单位比对");
                }
                if (kind.equals("TENDER") && d.parseEngine() != null && d.parseEngine().startsWith("MATERIAL_")
                        && !d.parseEngine().contains("_REVIEWED_")) {
                    if (allowPartial) continue;
                    throw new BusinessException(BusinessErrorCode.CONFLICT, "材料审查尚未完成");
                }
            } catch (JsonProcessingException ex) { throw new IllegalStateException("材料解析结果无法读取", ex); }
        }
        var scoring = ProcurementCollusionReviewService.scoringDocuments(collusionRepository.findDocuments(projectId));
        if (scoring.size() >= 2) {
            var assessment = collusionRepository.findLatestAssessment(projectId).orElseThrow(() ->
                    new BusinessException(BusinessErrorCode.CONFLICT, "投标材料已齐备，围串标分析尚未完成"));
            var ids = assessment.similarities().stream().flatMap(s -> java.util.stream.Stream.of(s.documentAId(), s.documentBId()))
                    .collect(java.util.stream.Collectors.toSet());
            if (!ids.equals(scoring.stream().map(com.fuquan.audit.procurement.domain.ProcurementCollusionDocument::id).collect(java.util.stream.Collectors.toSet())))
                throw new BusinessException(BusinessErrorCode.CONFLICT, "投标材料已更新，请先完成当前材料分析");
            var cv = ProcurementCollusionReviewService.matchingQuoteCv(quoteCheckService.findByBatchId(projectId.toString()).orElse(null), scoring);
            if (cv == null ? assessment.quoteCoefficientOfVariation() != null : assessment.quoteCoefficientOfVariation() == null
                    || cv.compareTo(assessment.quoteCoefficientOfVariation()) != 0)
                throw new BusinessException(BusinessErrorCode.CONFLICT, "报价已更新，请先完成围串标分析");
        }
    }

    private boolean hasMaterialCoverageGap(UUID projectId) {
        var assigned = collusionRepository.findDocuments(projectId).stream()
                .map(com.fuquan.audit.procurement.domain.ProcurementCollusionDocument::id)
                .collect(java.util.stream.Collectors.toSet());
        for (var document : tenderDocumentRepository.findByProjectId(projectId)) {
            if (document.status() == com.fuquan.audit.procurement.domain.ProcurementTenderDocumentStatus.PARSE_FAILED)
                return true;
            String engine = java.util.Objects.toString(document.parseEngine(), "");
            if (java.util.Set.of("QUOTE_PARSE_FAILED", "QUOTE_ANALYSIS_FAILED", "QUOTE_UNKNOWN")
                    .contains(engine)) return true;
            if (java.util.Set.of("xls", "xlsx").contains(document.fileExtension())) continue;
            if (document.parsedContentJson() == null) return true;
            try {
                var parse = objectMapper.readValue(document.parsedContentJson(),
                        com.fuquan.audit.procurement.domain.ProcurementTenderParseResult.class);
                var kind = ProcurementMaterialClassifier.classify(document.fileName(), parse.fullText()).kind();
                if (kind.startsWith("BIDDER") && !assigned.contains(document.id())) return true;
                if (kind.equals("TENDER") && engine.startsWith("MATERIAL_")
                        && !engine.contains("_REVIEWED_")) return true;
            } catch (JsonProcessingException invalid) {
                return true;
            }
        }
        return false;
    }

    private static String highestRisk(String agentRisk, String quoteRisk, List<String> blacklistRisks,
                                      List<String> tenderRisks, String collusionRisk, String splitPurchaseRisk) {
        String result = "INFO";
        List<String> levels = new java.util.ArrayList<>();
        levels.add(agentRisk);
        levels.add(quoteRisk);
        levels.addAll(blacklistRisks);
        levels.addAll(tenderRisks);
        levels.add(collusionRisk);
        levels.add(splitPurchaseRisk);
        for (String level : levels) {
            if (level == null || level.isBlank()) continue;
            if (riskRank(level) > riskRank(result)) result = level;
        }
        return result;
    }

    private static int riskRank(String level) {
        return switch (level) {
            case "CRITICAL" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }

    static String buildConclusion(
            ProcurementAgentReviewService.ReviewResult agentReview,
            ProcurementQuoteCheck quoteCheck,
            List<BlacklistAlert> alerts, List<ProcurementTenderFinding> tenderFindings,
            ProcurementCollusionAssessment collusion, ProcurementSplitPurchaseAssessment splitPurchase) {
        long criticalCount = 0;
        long warningCount = 0;
        if ("CRITICAL".equals(agentReview.riskLevel())) criticalCount++;
        else if ("WARNING".equals(agentReview.riskLevel())) warningCount++;
        if (quoteCheck != null) {
            if ("CRITICAL".equals(quoteCheck.riskLevel())) criticalCount++;
            else if ("WARNING".equals(quoteCheck.riskLevel())) warningCount++;
        }
        criticalCount += alerts.stream().filter(alert -> "CRITICAL".equals(alert.alertLevel())).count();
        warningCount += alerts.stream().filter(alert -> "WARNING".equals(alert.alertLevel())).count();
        criticalCount += tenderFindings.stream().filter(item -> "CRITICAL".equals(item.riskLevel())).count();
        warningCount += tenderFindings.stream().filter(item -> "WARNING".equals(item.riskLevel())).count();
        if (collusion != null) {
            if ("CRITICAL".equals(collusion.riskLevel())) criticalCount++;
            else if ("WARNING".equals(collusion.riskLevel())) warningCount++;
        }
        if (splitPurchase != null) {
            if ("CRITICAL".equals(splitPurchase.riskLevel())) criticalCount++;
            else if ("WARNING".equals(splitPurchase.riskLevel())) warningCount++;
        }

        if (criticalCount > 0 || warningCount > 0) {
            var focus = new java.util.LinkedHashSet<String>();
            if (riskRank(agentReview.riskLevel()) > 1) focus.add("采购基础信息");
            if (quoteCheck != null && riskRank(quoteCheck.riskLevel()) > 1) focus.add("询比价及报价异常");
            if (alerts.stream().anyMatch(alert -> riskRank(alert.alertLevel()) > 1)) focus.add("供应商黑名单命中");
            tenderFindings.stream().filter(item -> riskRank(item.riskLevel()) > 1)
                    .map(item -> "招标文件合规审查（" + item.riskCategory() + "）").forEach(focus::add);
            if (collusion != null && riskRank(collusion.riskLevel()) > 1) focus.add("围串标辅助识别线索");
            if (splitPurchase != null && riskRank(splitPurchase.riskLevel()) > 1) focus.add("拆分采购辅助识别线索");
            return "本项目存在" + (criticalCount > 0 ? "需重点关注的风险" : "需核对的一般风险")
                    + "，请核对：" + String.join("、", focus) + "。请结合原始材料核实，分析结果供工作参考。";
        }
        return "已执行的审查未发现需要关注的问题；未提供材料或数据不足的部分，请结合原始材料核实。本结果供工作参考。";
    }

    public record ReportData(
            ProcurementReviewReport metadata,
            ProcurementProject project,
            ProcurementAgentReviewService.ReviewResult agentReview,
            ProcurementQuoteCheck quoteCheck,
            List<BlacklistAlert> blacklistAlerts,
            List<ProcurementTenderFinding> tenderFindings,
            ProcurementCollusionAssessment collusionAssessment,
            ProcurementSplitPurchaseAssessment splitPurchaseAssessment,
            String riskLevel,
            String overallConclusion) {

        public ReportData {
            blacklistAlerts = List.copyOf(blacklistAlerts);
            tenderFindings = List.copyOf(tenderFindings);
        }

        private ReportData withMetadata(ProcurementReviewReport value) {
            return new ReportData(value, project, agentReview, quoteCheck, blacklistAlerts, tenderFindings, collusionAssessment, splitPurchaseAssessment, riskLevel,
                    overallConclusion);
        }
    }
}
