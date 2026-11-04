package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.project.ProjectFileStorageKeyFactory;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser;
import com.fuquan.audit.contract.application.project.parse.ProjectDocumentParser;
import com.fuquan.audit.contract.application.project.parse.ProjectPdfTextParser;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.procurement.application.port.ProcurementCollusionRepository;
import com.fuquan.audit.procurement.application.port.ProcurementQuoteCheckRepository;
import com.fuquan.audit.procurement.domain.ProcurementCollusionAssessment;
import com.fuquan.audit.procurement.domain.ProcurementCollusionDocument;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.procurement.domain.ProcurementSplitPurchaseAssessment;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic project-level F11 review. AI is intentionally not used to calculate the score. */
@Service
public class ProcurementCollusionReviewService {
    private static final Logger log = LoggerFactory.getLogger(ProcurementCollusionReviewService.class);
    private static final List<SplitThreshold> SPLIT_THRESHOLDS = List.of(
            new SplitThreshold(new BigDecimal("2000000.00"), "市局领导审批门槛", 90, 65),
            new SplitThreshold(new BigDecimal("200000.00"), "财处审批门槛", 80, 60),
            new SplitThreshold(new BigDecimal("50000.00"), "总支会审议门槛", 70, 55));
    private final ProcurementProjectQueryService projectQueryService;
    private final ProcurementQuoteCheckService quoteCheckService;
    private final ProcurementReviewWorkflowService workflowService;
    private final ProcurementCollusionRepository repository;
    private final FileStoragePort storage;
    private final ProjectDocxTextParser docxParser;
    private final ProjectPdfTextParser pdfParser;
    private final Clock clock;
    private final ProcurementCollusionProperties properties;
    private final ProcurementCollusionTransactionCoordinator transactionCoordinator;
    private final ProjectFileStorageKeyFactory storageKeyFactory = new ProjectFileStorageKeyFactory();

    @Autowired
    public ProcurementCollusionReviewService(ProcurementProjectQueryService projectQueryService,
            ProcurementQuoteCheckService quoteCheckService, ProcurementReviewWorkflowService workflowService,
            ProcurementCollusionRepository repository, FileStoragePort storage, ProjectDocxTextParser docxParser,
            ProjectPdfTextParser pdfParser, Clock clock, ProcurementCollusionProperties properties,
            ProcurementCollusionTransactionCoordinator transactionCoordinator) {
        this.projectQueryService = projectQueryService; this.quoteCheckService = quoteCheckService;
        this.workflowService = workflowService; this.repository = repository; this.storage = storage;
        this.docxParser = docxParser; this.pdfParser = pdfParser; this.clock = clock;
        this.properties = properties; this.transactionCoordinator = transactionCoordinator;
    }

    /** Test and non-Spring compatibility constructor. */
    public ProcurementCollusionReviewService(ProcurementProjectQueryService projectQueryService,
            ProcurementQuoteCheckService quoteCheckService, ProcurementReviewWorkflowService workflowService,
            ProcurementCollusionRepository repository, FileStoragePort storage, ProjectDocxTextParser docxParser,
            ProjectPdfTextParser pdfParser, Clock clock) {
        this(projectQueryService, quoteCheckService, workflowService, repository, storage, docxParser, pdfParser,
                clock, ProcurementCollusionProperties.defaults(),
                new ProcurementCollusionTransactionCoordinator(repository, workflowService));
    }

    public ProcurementCollusionDocument uploadDocument(UUID projectId, String bidderName, String ipAddress,
            String handlerName, String originalFilename, String contentType, long declaredSize, InputStream input) {
        projectQueryService.requireById(projectId);
        String fileName = requireFileName(originalFilename);
        String extension = extension(fileName);
        if (!"docx".equals(extension) && !"pdf".equals(extension)) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "投标文件仅支持 .docx 或 .pdf 格式");
        }
        String normalizedBidder = requireLength(requireBidder(bidderName), 500, "投标人名称");
        String normalizedIpAddress = optionalLength(ipAddress, 64, "IP 地址");
        String normalizedHandlerName = optionalLength(handlerName, 200, "经办人名称");
        String normalizedContentType = optionalLength(contentType, 200, "文件 Content-Type");
        if (declaredSize <= 0 || declaredSize > properties.maxUploadSize().toBytes()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                    "投标文件大小必须大于 0 且不超过 " + properties.maxUploadSize());
        }
        StagedFile staged = storage.stage(input, properties.maxUploadSize().toBytes());
        if (staged.size() != declaredSize) {
            storage.discard(staged);
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "投标文件实际大小与声明大小不一致");
        }
        UUID id = UUID.randomUUID();
        StorageKey storageKey = storageKeyFactory.create(id, staged.sha256());
        boolean promoted = false;
        try {
            storage.promote(staged, storageKey); promoted = true;
            normalizedContentType = normalizedContentType == null ? "application/octet-stream" : normalizedContentType;
            ProjectFile source = new ProjectFile(id, projectId, null, null, fileName, normalizedContentType,
                    ProjectFileCategory.TENDER_DOCUMENT, ProjectFileIngestionStatus.AVAILABLE,
                    ProjectFileParseStatus.PENDING, storageKey.value(), staged.sha256(), staged.size(), null, null,
                    clock.instant(), clock.instant());
            String text = parse(source, "docx".equals(extension) ? docxParser : pdfParser);
            ProcurementCollusionDocument document = new ProcurementCollusionDocument(id, projectId,
                    normalizedBidder, normalizedIpAddress, normalizedHandlerName, fileName, extension,
                    normalizedContentType, staged.size(), staged.sha256(), storageKey.value(), text, clock.instant());
            repository.saveDocument(document);
            return document;
        } catch (RuntimeException exception) {
            if (promoted) {
                try {
                    storage.delete(storageKey);
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            } else {
                storage.discard(staged);
            }
            throw exception;
        }
    }

    @Transactional
    public ReviewData requireLatest(UUID projectId) {
        ProcurementProject project = projectQueryService.requireById(projectId);
        requireMatchingProjectId(projectId, project);
        ProcurementCollusionAssessment assessment = repository.findLatestAssessment(projectId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "围串标评分尚未执行"));
        ProcurementSplitPurchaseAssessment splitPurchase = repository.findLatestSplitPurchaseAssessment(projectId)
                .orElseGet(() -> backfillSplitPurchaseAssessment(projectId, project));
        var ids = assessment.similarities().stream().flatMap(s -> java.util.stream.Stream.of(s.documentAId(), s.documentBId()))
                .collect(Collectors.toSet());
        return new ReviewData(assessment, repository.findDocuments(projectId).stream().filter(d -> ids.contains(d.id())).toList(), splitPurchase);
    }

    public ReviewData score(UUID projectId) {
        workflowService.require(projectId);
        ProcurementProject project = projectQueryService.requireById(projectId);
        requireMatchingProjectId(projectId, project);
        ProcurementQuoteCheck quote = quoteCheckService.findByBatchId(projectId.toString()).orElse(null);
        List<ProcurementCollusionDocument> documents = scoringDocuments(repository.findDocuments(projectId));
        if (documents.size() < 2) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, "至少需要两家不同投标人的有效投标文件才能进行围串标分析");
        }
        List<ProcurementCollusionAssessment.PairSimilarity> similarities = similarities(documents);
        BigDecimal maxSimilarity = similarities.stream().map(ProcurementCollusionAssessment.PairSimilarity::similarity)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        int duplicateIpGroups = duplicateGroups(documents.stream().map(ProcurementCollusionDocument::ipAddress).toList());
        int duplicateHandlerGroups = duplicateGroups(documents.stream().map(ProcurementCollusionDocument::handlerName).toList());
        BigDecimal cv = matchingQuoteCv(quote, documents);
        int score = score(cv, maxSimilarity, duplicateIpGroups, duplicateHandlerGroups);
        String riskLevel = score >= 70 ? "CRITICAL" : score >= 40 ? "WARNING" : "INFO";
        ProcurementSplitPurchaseAssessment splitPurchase = detectSplitPurchase(projectId, project);
        String signature = "F11_MATERIAL_V1|" + projectId + "|" + documents.stream().map(d -> d.id().toString()).sorted().toList()
                + "|" + (quote == null ? "NONE" : quote.id()) + "|" + splitPurchase.findings();
        UUID identity = UUID.nameUUIDFromBytes(signature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProcurementCollusionAssessment assessment = new ProcurementCollusionAssessment(identity, projectId,
                score, riskLevel, cv, maxSimilarity, duplicateIpGroups,
                duplicateHandlerGroups, similarities, clock.instant());
        log.info("F11 围串标评分完成，准备提交审查结果: projectId={}, riskLevel={}, findingCount={}",
                projectId, splitPurchase.riskLevel(), splitPurchase.findings().size());
        transactionCoordinator.saveValidated(projectId, assessment, splitPurchase,
                "围串标风险评分 " + score + " 分（" + riskLevel + "）",
                documents.stream().map(ProcurementCollusionDocument::id).collect(Collectors.toSet()), quote == null ? null : quote.id());
        log.info("F11 审查结果已提交: projectId={}, splitRiskScore={}", projectId, splitPurchase.riskScore());
        if (repository.findLatestAssessment(projectId).filter(a -> a.id().equals(identity)).isPresent()) return requireLatest(projectId);
        return new ReviewData(assessment, documents, splitPurchase);
    }

    static List<ProcurementCollusionDocument> scoringDocuments(List<ProcurementCollusionDocument> source) {
        if (source.size() > 200) throw new BusinessException(BusinessErrorCode.CONFLICT, "投标材料数量超过分析上限");
        var bidders = new java.util.HashSet<String>();
        // One latest document per bidder; identical bytes attributed to DIFFERENT bidders are valuable evidence.
        var selected = source.stream().sorted(Comparator.comparing(ProcurementCollusionDocument::createdTime).reversed()
                        .thenComparing(ProcurementCollusionDocument::id))
                .filter(d -> bidders.add(d.bidderName().replaceAll("\\s+", "").toLowerCase(Locale.ROOT))).toList();
        if (selected.size() > 20 || selected.stream().anyMatch(d -> d.parsedText().length() > 500_000))
            throw new BusinessException(BusinessErrorCode.CONFLICT, "围串标分析最多支持 20 家投标人，每份解析文本不超过 50 万字");
        return selected;
    }

    static BigDecimal matchingQuoteCv(ProcurementQuoteCheck quote, List<ProcurementCollusionDocument> documents) {
        if (quote == null) return null;
        var names = documents.stream().map(d -> normalizedBidder(d.bidderName())).collect(Collectors.toSet());
        var suppliers = quote.quotes().stream().map(q -> normalizedBidder(q.supplierName())).collect(Collectors.toSet());
        return suppliers.size() >= 3 && names.equals(suppliers) ? quote.coefficientOfVariation() : null;
    }
    private static String normalizedBidder(String name) { return name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }

    private String parse(ProjectFile file, ProjectDocumentParser parser) {
        StoredFileContent content = storage.open(new StorageKey(file.storageKey()));
        try (InputStream stream = content.inputStream()) {
            ParsedProjectDocument parsed = parser.parse(file, stream);
            if (parsed.fullText() == null || parsed.fullText().isBlank()) {
                throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, "投标文件未提取到可用于相似度分析的文本");
            }
            return parsed.fullText();
        } catch (IOException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, "投标文件读取失败", exception);
        }
    }

    private static List<ProcurementCollusionAssessment.PairSimilarity> similarities(List<ProcurementCollusionDocument> docs) {
        List<Map<String, Double>> vectors = tfIdf(docs.stream().map(ProcurementCollusionDocument::parsedText).toList());
        List<ProcurementCollusionAssessment.PairSimilarity> results = new ArrayList<>();
        for (int left = 0; left < docs.size(); left++) for (int right = left + 1; right < docs.size(); right++) {
            results.add(new ProcurementCollusionAssessment.PairSimilarity(docs.get(left).id(), docs.get(right).id(),
                    docs.get(left).bidderName(), docs.get(right).bidderName(),
                    BigDecimal.valueOf(cosine(vectors.get(left), vectors.get(right)))
                            .setScale(4, RoundingMode.HALF_UP)));
        }
        return List.copyOf(results);
    }

    /** Chinese text is represented by overlapping two-character terms before standard TF-IDF weighting. */
    private static List<Map<String, Double>> tfIdf(List<String> texts) {
        List<Map<String, Integer>> counts = texts.stream().map(ProcurementCollusionReviewService::terms).toList();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (Map<String, Integer> count : counts) for (String term : count.keySet()) documentFrequency.merge(term, 1, Integer::sum);
        List<Map<String, Double>> vectors = new ArrayList<>();
        for (Map<String, Integer> count : counts) {
            int total = count.values().stream().mapToInt(Integer::intValue).sum(); Map<String, Double> vector = new HashMap<>();
            for (Map.Entry<String, Integer> entry : count.entrySet()) {
                double tf = (double) entry.getValue() / Math.max(total, 1);
                double idf = Math.log((double) (counts.size() + 1) / (documentFrequency.get(entry.getKey()) + 1)) + 1;
                vector.put(entry.getKey(), tf * idf);
            }
            vectors.add(vector);
        }
        return vectors;
    }
    private static Map<String, Integer> terms(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        Map<String, Integer> terms = new HashMap<>();
        for (int index = 0; index + 1 < normalized.length(); index++) {
            String term = normalized.substring(index, index + 2);
            if (term.matches("[\\p{Punct}，。；：！？、]+")) continue;
            terms.merge(term, 1, Integer::sum);
        }
        return terms;
    }
    private static double cosine(Map<String, Double> left, Map<String, Double> right) {
        Set<String> common = left.keySet().stream().filter(right::containsKey).collect(Collectors.toSet());
        double dot = common.stream().mapToDouble(term -> left.get(term) * right.get(term)).sum();
        double leftLength = Math.sqrt(left.values().stream().mapToDouble(value -> value * value).sum());
        double rightLength = Math.sqrt(right.values().stream().mapToDouble(value -> value * value).sum());
        return leftLength == 0 || rightLength == 0 ? 0 : dot / (leftLength * rightLength);
    }
    private static int duplicateGroups(List<String> values) {
        return (int) values.stream().filter(value -> value != null && !value.isBlank()).collect(Collectors.groupingBy(value -> value, Collectors.counting())).values().stream().filter(count -> count > 1).count();
    }
    private static int score(BigDecimal cv, BigDecimal similarity, int ips, int handlers) {
        int score = 0;
        if (similarity.compareTo(new BigDecimal("0.85")) >= 0) score += 40;
        else if (similarity.compareTo(new BigDecimal("0.70")) >= 0) score += 25;
        else if (similarity.compareTo(new BigDecimal("0.50")) >= 0) score += 10;
        if (cv != null && cv.compareTo(new BigDecimal("0.05")) < 0) score += 25;
        else if (cv != null && cv.compareTo(new BigDecimal("0.10")) < 0) score += 10;
        if (ips > 0) score += 20;
        if (handlers > 0) score += 15;
        return Math.min(score, 100);
    }

    private ProcurementSplitPurchaseAssessment detectSplitPurchase(UUID projectId, ProcurementProject project) {
        Instant now = clock.instant();
        if (project.subjectCode() == null || project.subjectCode().isBlank()) {
            return new ProcurementSplitPurchaseAssessment(UUID.randomUUID(), projectId, "INFO", 0, List.of(), now);
        }
        int year = LocalDate.ofInstant(now, ZoneOffset.UTC).getYear();
        Instant fiscalYearStart = LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant fiscalYearEnd = LocalDate.of(year + 1, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<ProcurementQuoteCheckRepository.SupplierQuoteTimeline> records = quoteCheckService
                .findLatestSupplierQuotesBySubject(project.subjectCode(), fiscalYearStart, fiscalYearEnd);
        List<ProcurementSplitPurchaseAssessment.Finding> findings = splitFindings(projectId, records);
        int score = findings.stream().mapToInt(ProcurementSplitPurchaseAssessment.Finding::riskScore).max().orElse(0);
        String riskLevel = score >= 80 ? "CRITICAL" : score > 0 ? "WARNING" : "INFO";
        return new ProcurementSplitPurchaseAssessment(UUID.randomUUID(), projectId, riskLevel, score, findings, now);
    }

    private ProcurementSplitPurchaseAssessment backfillSplitPurchaseAssessment(UUID projectId, ProcurementProject project) {
        log.info("F11 历史围串标评分缺少拆分采购识别结果，准备回填: projectId={}", projectId);
        ProcurementSplitPurchaseAssessment splitPurchase = detectSplitPurchase(projectId, project);
        repository.saveSplitPurchaseAssessment(splitPurchase);
        log.info("F11 历史拆分采购识别结果已回填: projectId={}, riskLevel={}, findingCount={}",
                projectId, splitPurchase.riskLevel(), splitPurchase.findings().size());
        return splitPurchase;
    }

    private static void requireMatchingProjectId(UUID requestedProjectId, ProcurementProject project) {
        if (!requestedProjectId.equals(project.id())) {
            throw new IllegalStateException("采购项目查询结果与请求项目 ID 不一致: requested=" + requestedProjectId
                    + ", actual=" + project.id());
        }
    }

    static List<ProcurementSplitPurchaseAssessment.Finding> splitFindings(
            UUID currentProjectId, List<ProcurementQuoteCheckRepository.SupplierQuoteTimeline> source) {
        return source.stream().collect(Collectors.groupingBy(ProcurementQuoteCheckRepository.SupplierQuoteTimeline::supplierName))
                .entrySet().stream().map(entry -> bestSplitWindow(currentProjectId, entry.getKey(), entry.getValue()))
                .flatMap(Optional::stream).sorted(Comparator.comparing(
                        ProcurementSplitPurchaseAssessment.Finding::riskScore).reversed()).toList();
    }

    private static Optional<ProcurementSplitPurchaseAssessment.Finding> bestSplitWindow(UUID currentProjectId,
            String supplierName, List<ProcurementQuoteCheckRepository.SupplierQuoteTimeline> records) {
        List<ProcurementQuoteCheckRepository.SupplierQuoteTimeline> sorted = records.stream()
                .sorted(Comparator.comparing(ProcurementQuoteCheckRepository.SupplierQuoteTimeline::quoteTime)).toList();
        ProcurementSplitPurchaseAssessment.Finding best = null;
        for (int start = 0; start < sorted.size(); start++) {
            Instant startTime = sorted.get(start).quoteTime();
            Instant endTime = startTime.plus(java.time.Duration.ofDays(30));
            List<ProcurementQuoteCheckRepository.SupplierQuoteTimeline> window = sorted.subList(start, sorted.size()).stream()
                    .takeWhile(item -> !item.quoteTime().isAfter(endTime)).toList();
            if (window.size() < 2 || window.stream().noneMatch(item -> currentProjectId.equals(item.projectId()))) continue;
            BigDecimal total = window.stream().map(ProcurementQuoteCheckRepository.SupplierQuoteTimeline::quoteAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            SplitThreshold threshold = SPLIT_THRESHOLDS.stream()
                    .filter(item -> total.compareTo(item.amount().multiply(new BigDecimal("0.90"))) >= 0)
                    .findFirst().orElse(null);
            if (threshold == null) continue;
            boolean reached = total.compareTo(threshold.amount()) >= 0;
            int score = reached ? threshold.reachedScore() : threshold.nearScore();
            String risk = score >= 80 ? "CRITICAL" : "WARNING";
            List<ProcurementSplitPurchaseAssessment.Record> items = window.stream().map(item ->
                    new ProcurementSplitPurchaseAssessment.Record(item.projectId(), item.projectCode(),
                            item.projectName(), item.quoteAmount(), item.quoteTime())).toList();
            ProcurementSplitPurchaseAssessment.Finding finding = new ProcurementSplitPurchaseAssessment.Finding(
                    supplierName, startTime, window.get(window.size() - 1).quoteTime(), total, threshold.amount(),
                    threshold.name(), score, risk, items);
            if (best == null || finding.riskScore() > best.riskScore()
                    || (finding.riskScore() == best.riskScore()
                    && finding.totalAmount().compareTo(best.totalAmount()) > 0)) best = finding;
        }
        return Optional.ofNullable(best);
    }
    private static String requireBidder(String value) { if (value == null || value.isBlank()) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "投标人名称不能为空"); return value.trim(); }
    private static String requireFileName(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "投标文件名不能为空");
        String normalized = value.replace('\\', '/');
        return requireLength(normalized.substring(normalized.lastIndexOf('/') + 1).trim(), 500, "投标文件名");
    }
    private static String extension(String value) { int index = value.lastIndexOf('.'); return index < 0 ? "" : value.substring(index + 1).toLowerCase(Locale.ROOT); }
    private static String requireLength(String value, int maximum, String label) {
        if (value.length() > maximum) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, label + "长度不能超过 " + maximum + " 个字符");
        }
        return value;
    }
    private static String optionalLength(String value, int maximum, String label) {
        return value == null || value.isBlank() ? null : requireLength(value.trim(), maximum, label);
    }

    public record ReviewData(ProcurementCollusionAssessment assessment, List<ProcurementCollusionDocument> documents,
                             ProcurementSplitPurchaseAssessment splitPurchaseAssessment) {
        public ReviewData { documents = List.copyOf(documents); }
    }
    private record SplitThreshold(BigDecimal amount, String name, int reachedScore, int nearScore) {}
}
