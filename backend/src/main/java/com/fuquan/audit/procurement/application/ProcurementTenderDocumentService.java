package com.fuquan.audit.procurement.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.FileStorageUserRejectionException;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.project.ProjectFileStorageKeyFactory;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.application.project.parse.PdfTextUsefulness;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser;
import com.fuquan.audit.contract.application.project.parse.ProjectDocumentParser;
import com.fuquan.audit.contract.application.project.parse.ProjectParseException;
import com.fuquan.audit.contract.application.project.parse.ProjectPdfTextParser;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.review.application.port.OcrDocumentSource;
import com.fuquan.audit.review.application.port.OcrPort;
import com.fuquan.audit.review.application.port.OcrRequest;
import com.fuquan.audit.review.application.port.OcrResult;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.application.port.ProcurementTenderDocumentRepository;
import com.fuquan.audit.procurement.domain.ProcurementTenderDocument;
import com.fuquan.audit.procurement.domain.ProcurementTenderDocumentStatus;
import com.fuquan.audit.procurement.domain.ProcurementTenderParseMethod;
import com.fuquan.audit.procurement.domain.ProcurementTenderParseResult;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** F10 招标文件安全上传、文本解析和解析结果查询。 */
@Service
public class ProcurementTenderDocumentService {
    private static final Logger LOG = LoggerFactory.getLogger(ProcurementTenderDocumentService.class);
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ProcurementProjectQueryPort projectQueryPort;
    private final ProcurementTenderDocumentRepository repository;
    private final FileStoragePort storage;
    private final ProjectDocxTextParser docxParser;
    private final ProjectPdfTextParser pdfParser;
    private final OcrPort ocr;
    private final ProjectIntakeProperties intakeProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ProjectFileStorageKeyFactory storageKeyFactory = new ProjectFileStorageKeyFactory();
    private final PdfTextUsefulness pdfTextUsefulness = new PdfTextUsefulness();
    private com.fuquan.audit.common.material.application.port.DocumentTextPort sharedReader;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSharedReader(com.fuquan.audit.common.material.application.port.DocumentTextPort sharedReader) {
        this.sharedReader = sharedReader;
    }

    public ProcurementTenderDocumentService(ProcurementProjectQueryPort projectQueryPort,
            ProcurementTenderDocumentRepository repository, FileStoragePort storage,
            ProjectDocxTextParser docxParser, ProjectPdfTextParser pdfParser, OcrPort ocr,
            ProjectIntakeProperties intakeProperties, ObjectMapper objectMapper, Clock clock) {
        this.projectQueryPort = projectQueryPort;
        this.repository = repository;
        this.storage = storage;
        this.docxParser = docxParser;
        this.pdfParser = pdfParser;
        this.ocr = ocr;
        this.intakeProperties = intakeProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public TenderDocumentResult upload(UUID projectId, String originalFilename, String declaredContentType,
            InputStream input, boolean refresh, String operatorUserId) {
        requireProject(projectId);
        String fileName = safeFilename(originalFilename);
        String extension = extension(fileName);
        if (!extension.equals("docx") && !extension.equals("pdf")) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                    "招标文件仅支持 .docx 或 .pdf 格式");
        }
        String contentType = normalizedContentType(declaredContentType, extension);
        StagedFile staged = stage(input);
        try {
            validateSignature(extension, contentType, staged.header());
        } catch (RuntimeException exception) {
            storage.discard(staged);
            throw exception;
        }

        if (!refresh) {
            Optional<ProcurementTenderDocument> latest = repository.findLatestByProjectId(projectId);
            if (latest.isPresent()
                    && latest.get().status() == ProcurementTenderDocumentStatus.AVAILABLE
                    && latest.get().sha256().equals(staged.sha256())) {
                storage.discard(staged);
                return toResult(latest.get());
            }
        }

        UUID documentId = UUID.randomUUID();
        StorageKey storageKey = storageKeyFactory.create(documentId, staged.sha256());
        Instant now = clock.instant();
        ProcurementTenderDocument uploading = new ProcurementTenderDocument(documentId, projectId, fileName,
                extension, contentType, staged.size(), staged.sha256(), storageKey.value(),
                ProcurementTenderDocumentStatus.UPLOADING, null, null, null, null, null, null,
                operatorUserId, now, now);
        try {
            repository.save(uploading);
        } catch (RuntimeException exception) {
            storage.discard(staged);
            throw exception;
        }

        boolean promoted = false;
        try {
            storage.promote(staged, storageKey);
            promoted = true;
            ProcurementTenderDocument completed = parseAndComplete(uploading);
            return toResult(completed);
        } catch (RuntimeException failure) {
            if (!promoted) storage.discard(staged);
            ProcurementTenderDocument failed = failed(uploading, failure);
            try {
                repository.update(failed);
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
            LOG.warn("F10 招标文件解析失败 documentId={} projectId={} reasonCode={}", documentId, projectId,
                    failed.parseError());
            return toResult(failed);
        }
    }

    @Transactional(readOnly = true)
    public Optional<TenderDocumentResult> findLatest(UUID projectId) {
        requireProject(projectId);
        return repository.findLatestByProjectId(projectId).map(this::toResult);
    }

    /** Shared procurement source ingestion, content addressed for multi-file retries. No outer transaction. */
    public TenderDocumentResult uploadMaterial(UUID projectId, String originalFilename, String declaredContentType,
            InputStream input, String operator, ProcurementMaterialTransactions transactions) {
        requireProject(projectId);
        String name = safeFilename(originalFilename);
        String ext = extension(name);
        String mime = com.fuquan.audit.common.material.application.OfficeMaterialTypes.mediaType(name);
        if (mime == null) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                "请选择支持的办公文件或图片扫描件，暂不支持压缩包");
        StagedFile staged = stage(input);
        try {
            if (!com.fuquan.audit.common.material.application.OfficeMaterialTypes.validSignature(mime, staged.header()))
                throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "文件内容与扩展名不匹配");
        }
        catch (RuntimeException ex) { storage.discard(staged); throw ex; }
        UUID id = UUID.nameUUIDFromBytes(("PROCUREMENT_MATERIAL_V1|" + projectId + "|" + staged.sha256())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var key = storageKeyFactory.create(id, staged.sha256());
        Instant now = clock.instant();
        ProcurementTenderDocument source;
        try {
            source = transactions.register(new ProcurementTenderDocument(id, projectId, name, ext, mime,
                    staged.size(), staged.sha256(), key.value(), ProcurementTenderDocumentStatus.UPLOADING,
                    null, "MATERIAL_PENDING", null, null, null, null, operator, now, now));
        } catch (RuntimeException ex) { storage.discard(staged); throw ex; }
        if (source.status() == ProcurementTenderDocumentStatus.AVAILABLE) {
            storage.discard(staged); return toResult(source);
        }
        // Create-only promotion verifies identical content. Never delete a shared winner on uncertain DB outcomes.
        try { storage.promote(staged, key); }
        catch (RuntimeException ex) { storage.discard(staged); throw ex; }
        try { return toResult(sharedReader == null ? parseAndComplete(source) : parseSharedAndComplete(source)); }
        catch (RuntimeException ex) {
            var failed = failed(source, ex); repository.update(failed); return toResult(failed);
        }
    }

    /** F10/F11: reuse an explicitly selected, owned intake without copying its original bytes. */
    public TenderDocumentResult reuseIntake(UUID projectId,
            com.fuquan.audit.common.material.domain.MaterialIntake intake, String actor,
            ProcurementMaterialTransactions transactions) {
        requireProject(projectId);
        if (!actor.equals(intake.actorId()) || !"PARSED".equals(intake.status()))
            throw new BusinessException(BusinessErrorCode.CONFLICT, "采购材料尚未完成提取，不能开始审查");
        var inspection = storage.inspect(new StorageKey(intake.storageKey()));
        if (inspection.status() != com.fuquan.audit.contract.application.port.StoredFileInspection.Status.PRESENT)
            throw new BusinessException(BusinessErrorCode.CONFLICT, "已上传材料的原文件暂时无法确认，请保留当前项目并联系管理员核对");
        if (inspection.file().size() != intake.fileSize() || !intake.sha256().equals(inspection.file().sha256()))
            throw new BusinessException(BusinessErrorCode.CONFLICT, "已上传材料的文件校验不一致，已停止分析");
        var id = UUID.nameUUIDFromBytes(("PROCUREMENT_MATERIAL_V1|" + projectId + "|" + intake.sha256())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var now = clock.instant();
        String ext = extension(intake.fileName());
        boolean spreadsheet = java.util.Set.of("xls", "xlsx").contains(ext);
        var source = transactions.register(new ProcurementTenderDocument(id, projectId, safeFilename(intake.fileName()),
                ext, intake.mediaType(), intake.fileSize(), intake.sha256(), intake.storageKey(),
                ProcurementTenderDocumentStatus.UPLOADING, null, spreadsheet ? "QUOTE_PENDING" : "MATERIAL_PENDING",
                null, null, null, null, actor, now, now));
        // Repeated commands reuse durable content identity and successful parse results.
        if (source.status() == ProcurementTenderDocumentStatus.AVAILABLE) return toResult(source);
        if (spreadsheet) { recordSpreadsheet(projectId, id, "QUOTE_STAGED"); return requireMaterial(projectId, id); }
        try { return toResult(sharedReader == null ? parseAndComplete(source) : parseSharedAndComplete(source)); }
        catch (RuntimeException failure) {
            var failed = failed(source, failure); repository.update(failed); return toResult(failed);
        }
    }

    private ProcurementTenderDocument parseSharedAndComplete(ProcurementTenderDocument source) {
        try (var span = PipelineDiagnostics.start("PROCUREMENT", "TENDER_DOCUMENT_PARSE", source.id(),
                java.util.Map.of("inputBytes", source.fileSize()))) {
          try (var input = storage.open(new StorageKey(source.storageKey())).inputStream()) {
            var text = sharedReader.read(input, source.fileName(), source.id().toString(),
                    com.fuquan.audit.common.material.application.RepeatableMaterialInput.MAX_BYTES, 300, 192000);
            if (!source.sha256().equals(text.sha256())) throw new TenderParseFailure("SOURCE_HASH_MISMATCH", null);
            if (text.segments().isEmpty()) throw new TenderParseFailure("DOCUMENT_TEXT_EMPTY", null);
            int outputChars = text.segments().stream().mapToInt(s -> s.text().codePointCount(0, s.text().length())).sum();
            var parsed = new ProcurementTenderParseResult(String.join("\n", text.segments().stream()
                    .map(com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment::text).toList()),
                    text.segments().stream().map(s -> new ProcurementTenderParseResult.Segment(
                            s.locator(), s.text(), null, null)).toList());
            var method = text.method().contains("OCR") ? ProcurementTenderParseMethod.OCR
                    : source.fileExtension().equals("pdf") ? ProcurementTenderParseMethod.PDF_TEXT
                    : source.fileExtension().equals("docx") ? ProcurementTenderParseMethod.DOCX_TEXT
                    : null; // V035 permits three historical methods; engine records other Office readers accurately.
            var completed = new ProcurementTenderDocument(source.id(), source.projectId(), source.fileName(),
                    source.fileExtension(), source.contentType(), source.fileSize(), source.sha256(), source.storageKey(),
                    ProcurementTenderDocumentStatus.AVAILABLE, method, "MATERIAL_" + text.method(), null, null,
                    serialize(parsed), null, source.createdBy(), source.createdTime(), clock.instant());
            repository.update(completed);
            span.success(java.util.Map.of("parseMethod", text.method(), "segmentCount", text.segments().size(),
                    "outputChars", outputChars));
            return completed;
          } catch (RuntimeException failure) {
              span.fail(failure);
              throw failure;
          }
        } catch (com.fuquan.audit.common.material.application.port.DocumentTextPort.ReadFailure failure) {
            throw new TenderParseFailure(failure.code(), failure);
        } catch (IOException failure) {
            throw new TenderParseFailure("MATERIAL_READ_FAILED", failure);
        }
    }

    public TenderDocumentResult requireMaterial(UUID projectId, UUID id) {
        requireProject(projectId);
        var source = repository.findById(id).filter(d -> d.projectId().equals(projectId)).orElseThrow(() ->
                new BusinessException(BusinessErrorCode.NOT_FOUND, "采购材料不存在"));
        return toResult(source);
    }

    public StoredFileContent openMaterial(UUID projectId, UUID id) {
        requireProject(projectId);
        var source = repository.findById(id).filter(d -> d.projectId().equals(projectId)).orElseThrow(() ->
                new BusinessException(BusinessErrorCode.NOT_FOUND, "采购材料不存在"));
        return storage.open(new StorageKey(source.storageKey()));
    }

    public List<TenderDocumentResult> currentMaterials(UUID projectId) {
        requireProject(projectId);
        var all = repository.findByProjectId(projectId);
        if (all.size() > 200) throw new BusinessException(BusinessErrorCode.CONFLICT, "项目材料过多，请联系管理员整理后继续");
        var names = new java.util.HashSet<String>();
        var hashes = new java.util.HashSet<String>();
        return all.stream().filter(d -> names.add(d.fileName()) && hashes.add(d.sha256())).map(this::toResult).toList();
    }

    public TenderDocumentResult storeSpreadsheet(UUID projectId, String originalName, String contentType, InputStream input,
            String actor, ProcurementMaterialTransactions transactions) {
        requireProject(projectId);
        String name = safeFilename(originalName); String ext = extension(name);
        var staged = stage(input);
        var id = UUID.nameUUIDFromBytes(("PROCUREMENT_MATERIAL_V1|" + projectId + "|" + staged.sha256())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var key = storageKeyFactory.create(id, staged.sha256());
        var now = clock.instant();
        ProcurementTenderDocument source;
        try { source = transactions.register(new ProcurementTenderDocument(id, projectId, name, ext,
                contentType == null ? "application/octet-stream" : contentType, staged.size(), staged.sha256(), key.value(),
                ProcurementTenderDocumentStatus.UPLOADING, null, "QUOTE_PENDING", null, null, null, null, actor, now, now)); }
        catch (RuntimeException ex) { storage.discard(staged); throw ex; }
        try { storage.promote(staged, key); } catch (RuntimeException ex) { storage.discard(staged); throw ex; }
        return toResult(source);
    }

    public void recordSpreadsheet(UUID projectId, UUID id, String outcome) {
        recordSpreadsheet(projectId,id,outcome,null,null);
    }

    public void recordSpreadsheet(UUID projectId, UUID id, String outcome, ProcurementTenderParseResult parsed, String reason) {
        var d = repository.findById(id).filter(s -> s.projectId().equals(projectId)).orElseThrow();
        repository.update(new ProcurementTenderDocument(d.id(), d.projectId(), d.fileName(), d.fileExtension(), d.contentType(),
                d.fileSize(), d.sha256(), d.storageKey(), "QUOTE_PARSE_FAILED".equals(outcome)
                        ? ProcurementTenderDocumentStatus.PARSE_FAILED : ProcurementTenderDocumentStatus.AVAILABLE,
                null, outcome, null, null, parsed==null ? d.parsedContentJson() : serialize(parsed), reason,
                d.createdBy(), d.createdTime(), clock.instant()));
    }

    /**
     * Persist evidence-backed fields discovered after text parsing. The text and
     * source locators remain immutable; only the optional candidate projection is
     * replaced, so a model outage never destroys a usable parsed document.
     */
    public TenderDocumentResult recordExtractedFields(UUID projectId, UUID id,
            List<com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact> fields) {
        return recordFieldExtraction(projectId, id, fields, null);
    }

    public TenderDocumentResult recordFieldExtraction(UUID projectId, UUID id,
            List<com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact> fields,
            ProcurementTenderParseResult.FieldExtractionState state) {
        var d = repository.findById(id).filter(s -> s.projectId().equals(projectId)).orElseThrow();
        if (d.parsedContentJson() == null || d.parsedContentJson().isBlank()) return toResult(d);
        try {
            var parsed = objectMapper.readValue(d.parsedContentJson(), ProcurementTenderParseResult.class);
            var updated = new ProcurementTenderDocument(d.id(), d.projectId(), d.fileName(), d.fileExtension(),
                    d.contentType(), d.fileSize(), d.sha256(), d.storageKey(), d.status(), d.parseMethod(),
                    d.parseEngine(), d.parseConfidence(), d.pageCount(), serialize(new ProcurementTenderParseResult(
                            parsed.fullText(), parsed.segments(), fields,
                            state == null ? parsed.fieldExtraction() : state)), d.parseError(), d.createdBy(),
                    d.createdTime(), clock.instant());
            repository.update(updated);
            return toResult(updated);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "招标文件解析结果格式无效，未保存模型字段", failure);
        }
    }

    private ProcurementTenderDocument parseAndComplete(ProcurementTenderDocument uploading) {
        ProjectFile source = new ProjectFile(uploading.id(), uploading.projectId(), null, null,
                uploading.fileName(), uploading.contentType(), ProjectFileCategory.TENDER_DOCUMENT,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PENDING,
                uploading.storageKey(), uploading.sha256(), uploading.fileSize(), null, null,
                uploading.createdTime(), uploading.updatedTime());
        ParsedExtraction extraction;
        try {
            if (uploading.fileExtension().equals("docx")) {
                extraction = fromTextParser(docxParser, source, ProcurementTenderParseMethod.DOCX_TEXT,
                        "APACHE_POI_4.1.2");
            } else {
                ParsedProjectDocument parsed = parseStored(pdfParser, source);
                if (pdfTextUsefulness.isUseful(parsed.fullText())) {
                    extraction = fromParsed(parsed, ProcurementTenderParseMethod.PDF_TEXT, "PDFBOX_2.0.30", 1.0);
                } else {
                    extraction = fromOcr(uploading);
                }
            }
        } catch (ProjectParseException exception) {
            throw new TenderParseFailure(exception.reasonCode(), exception);
        } catch (TenderParseFailure exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TenderParseFailure("DOCUMENT_PARSE_FAILED", exception);
        }
        String json = serialize(extraction.result());
        Instant now = clock.instant();
        ProcurementTenderDocument completed = new ProcurementTenderDocument(uploading.id(), uploading.projectId(),
                uploading.fileName(), uploading.fileExtension(), uploading.contentType(), uploading.fileSize(),
                uploading.sha256(), uploading.storageKey(), ProcurementTenderDocumentStatus.AVAILABLE,
                extraction.method(), uploading.parseEngine() != null && uploading.parseEngine().startsWith("MATERIAL_")
                        ? "MATERIAL_" + extraction.engine() : extraction.engine(), extraction.confidence(), extraction.pageCount(), json,
                null, uploading.createdBy(), uploading.createdTime(), now);
        repository.update(completed);
        return completed;
    }

    private ParsedExtraction fromTextParser(ProjectDocumentParser parser, ProjectFile file,
            ProcurementTenderParseMethod method, String engine) {
        ParsedProjectDocument parsed = parseStored(parser, file);
        if (!pdfTextUsefulness.isUseful(parsed.fullText())) {
            throw new TenderParseFailure("DOCUMENT_TEXT_EMPTY", null);
        }
        return fromParsed(parsed, method, engine, 1.0);
    }

    private ParsedProjectDocument parseStored(ProjectDocumentParser parser, ProjectFile file) {
        StoredFileContent content = storage.open(new StorageKey(file.storageKey()));
        try (InputStream input = content.inputStream()) {
            return parser.parse(file, input);
        } catch (IOException exception) {
            throw new TenderParseFailure("DOCUMENT_READ_FAILED", exception);
        }
    }

    private ParsedExtraction fromOcr(ProcurementTenderDocument document) {
        OcrResult result;
        try (var span = PipelineDiagnostics.start("PROCUREMENT", "TENDER_OCR", document.id(),
                java.util.Map.of("inputBytes", document.fileSize()))) {
          try {
            OcrDocumentSource source = new OcrDocumentSource() {
                @Override public InputStream openStream() throws IOException {
                    return storage.open(new StorageKey(document.storageKey())).inputStream();
                }
                @Override public long contentLength() { return document.fileSize(); }
            };
            result = ocr.recognize(new OcrRequest(document.id().toString(), document.fileName(),
                    document.contentType(), source));
            int outputChars = result == null ? 0 : result.text().codePointCount(0, result.text().length());
            span.outcome(result == null || result.text().isBlank() ? "EMPTY_RESULT" : "TEXT_RETURNED",
                    java.util.Map.of("pageCount", result == null ? 0 : result.pages().size(), "outputChars", outputChars));
          } catch (RuntimeException exception) {
            span.fail(exception);
            throw new TenderParseFailure("OCR_FAILED", exception);
          }
        }
        if (result.simulated() || result.pages().isEmpty() || result.text().isBlank()) {
            throw new TenderParseFailure("OCR_NOT_AVAILABLE", null);
        }
        List<ProcurementTenderParseResult.Segment> segments = result.pages().stream()
                .map(page -> new ProcurementTenderParseResult.Segment("ocr/page:" + page.pageNumber(),
                        page.text(), page.pageNumber(), page.confidence()))
                .toList();
        return new ParsedExtraction(new ProcurementTenderParseResult(result.text(), segments),
                ProcurementTenderParseMethod.OCR, result.engine(), result.confidence(), result.pages().size());
    }

    private ParsedExtraction fromParsed(ParsedProjectDocument parsed, ProcurementTenderParseMethod method,
            String engine, double confidence) {
        List<ProcurementTenderParseResult.Segment> segments = parsed.segments().stream()
                .map(segment -> new ProcurementTenderParseResult.Segment(segment.sourceLocator(), segment.text(),
                        segment.pageNumber(), confidence))
                .toList();
        return new ParsedExtraction(new ProcurementTenderParseResult(parsed.fullText(), segments), method, engine,
                confidence, method == ProcurementTenderParseMethod.PDF_TEXT ? segments.size() : null);
    }

    private ProcurementTenderDocument failed(ProcurementTenderDocument uploading, RuntimeException exception) {
        String reason = exception instanceof TenderParseFailure parseFailure
                ? parseFailure.reason : "STORAGE_OR_PARSE_FAILED";
        return new ProcurementTenderDocument(uploading.id(), uploading.projectId(), uploading.fileName(),
                uploading.fileExtension(), uploading.contentType(), uploading.fileSize(), uploading.sha256(),
                uploading.storageKey(), ProcurementTenderDocumentStatus.PARSE_FAILED, null, uploading.parseEngine(), null, null,
                null, reason, uploading.createdBy(), uploading.createdTime(), clock.instant());
    }

    private TenderDocumentResult toResult(ProcurementTenderDocument document) {
        ProcurementTenderParseResult parsed = null;
        if (document.parsedContentJson() != null) {
            try {
                parsed = objectMapper.readValue(document.parsedContentJson(), ProcurementTenderParseResult.class);
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                        "招标文件解析结果格式无效，请重新上传", exception);
            }
        }
        return new TenderDocumentResult(document.id(), document.projectId(), document.fileName(),
                document.fileExtension(), document.contentType(), document.fileSize(), document.sha256(),
                document.status().name(), document.parseMethod() == null ? null : document.parseMethod().name(),
                document.parseEngine(), document.parseConfidence(), document.pageCount(), document.parseError(),
                parsed, document.createdTime(), document.updatedTime());
    }

    private StagedFile stage(InputStream input) {
        try {
            return storage.stage(input, intakeProperties.maxUploadSize().toBytes());
        } catch (FileStorageUserRejectionException exception) {
            String message = exception.reason() == FileStorageUserRejectionException.Reason.SIZE_LIMIT
                    ? "招标文件超过上传大小限制" : "招标文件不能为空";
            throw new BusinessException(exception.reason() == FileStorageUserRejectionException.Reason.SIZE_LIMIT
                    ? BusinessErrorCode.PAYLOAD_TOO_LARGE : BusinessErrorCode.INVALID_ARGUMENT, message, exception);
        }
    }

    private void validateSignature(String extension, String contentType, byte[] header) {
        byte[] expected = extension.equals("pdf")
                ? "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
                : new byte[]{'P', 'K', 3, 4};
        if (!startsWith(header, expected)) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                    "文件内容与扩展名不匹配，无法识别为有效的 " + extension + " 文件");
        }
        if (extension.equals("pdf") && !contentType.equals("application/pdf")
                && !contentType.equals("application/octet-stream")) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "PDF 文件类型不正确");
        }
        if (extension.equals("docx") && !contentType.equals(DOCX_MIME)
                && !contentType.equals("application/octet-stream") && !contentType.equals("application/zip")) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "Word 文件类型不正确");
        }
    }

    private void requireProject(UUID projectId) {
        if (projectId == null) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "项目ID不能为空");
        projectQueryPort.findById(projectId).orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                "采购项目不存在: " + projectId));
    }

    private static String safeFilename(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                "招标文件名不能为空");
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isBlank() || name.contains("..")) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                "招标文件名不合法");
        return name;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizedContentType(String declared, String extension) {
        if (declared == null || declared.isBlank()) return extension.equals("pdf") ? "application/pdf" : DOCX_MIME;
        return declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] actual, byte[] expected) {
        if (actual == null || actual.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (actual[i] != expected[i]) return false;
        return true;
    }

    private String serialize(ProcurementTenderParseResult result) {
        try { return objectMapper.writeValueAsString(result); }
        catch (JsonProcessingException exception) { throw new TenderParseFailure("PARSE_RESULT_SERIALIZE_FAILED", exception); }
    }

    private record ParsedExtraction(ProcurementTenderParseResult result,
                                    ProcurementTenderParseMethod method, String engine,
                                    Double confidence, Integer pageCount) {}

    private static final class TenderParseFailure extends RuntimeException {
        private final String reason;
        private TenderParseFailure(String reason, Throwable cause) { super(reason, cause); this.reason = reason; }
    }

    public record TenderDocumentResult(UUID id, UUID projectId, String fileName, String fileExtension,
            String contentType, long fileSize, String sha256, String status, String parseMethod,
            String parseEngine, Double parseConfidence, Integer pageCount, String parseError,
            ProcurementTenderParseResult parseResult, Instant createdTime, Instant updatedTime) {}
}
