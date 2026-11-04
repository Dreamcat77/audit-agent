package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.contract.application.project.ProjectFileIngestionTransactionCoordinator.*;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.application.project.parse.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.review.application.port.OcrDocumentSource;
import com.fuquan.audit.review.application.port.OcrPage;
import com.fuquan.audit.review.application.port.OcrPort;
import com.fuquan.audit.review.application.port.OcrRequest;
import com.fuquan.audit.review.application.port.OcrResult;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProjectFileIngestionService {
    private final ProjectApplicationService projects;
    private final FileStoragePort storage;
    private final ProjectFilePolicy policy;
    private final ProjectFileClassifier classifier;
    private final List<ProjectDocumentParser> parsers;
    private final OcrPort ocr;
    private final ProjectFactExtractor extractor;
    private final ProjectArchiveProcessingService archives;
    private final ProjectFileIngestionTransactionCoordinator transactions;
    private final ProjectIntakeProgressionService progression;
    private final AutomaticProjectFileClassificationService automaticClassification;
    private final AuthContext authContext;
    private final Clock clock;
    private final java.util.function.LongSupplier monotonicNanos;
    private final ProjectFileStorageKeyFactory storageKeys = new ProjectFileStorageKeyFactory();
    private final PdfTextUsefulness pdfTextUsefulness = new PdfTextUsefulness();
    private com.fuquan.audit.contract.application.analysis.ContractAnalysisRegistry analysisRegistry;
    @Autowired(required=false)
    public void setAnalysisRegistry(com.fuquan.audit.contract.application.analysis.ContractAnalysisRegistry value){this.analysisRegistry=value;}

    private com.fuquan.audit.common.material.application.port.MaterialParsePort sharedParser;
    @Autowired
    public ProjectFileIngestionService(ProjectApplicationService projects, FileStoragePort storage,
            ProjectFilePolicy policy, ProjectFileClassifier classifier, List<ProjectDocumentParser> parsers,
            OcrPort ocr, ProjectFactExtractor extractor, ContractCandidateAssembler assembler,
            ProjectArchiveProcessingService archives, ProjectFileIngestionTransactionCoordinator transactions,
            ProjectIntakeProgressionService progression, AutomaticProjectFileClassificationService automaticClassification,
            AuthContext authContext, Clock clock,
            com.fuquan.audit.common.material.application.port.MaterialParsePort sharedParser) {
        this(projects,storage,policy,classifier,parsers,ocr,extractor,assembler,archives,transactions,
                progression,automaticClassification,authContext,clock);
        this.sharedParser=Objects.requireNonNull(sharedParser);
    }
    public ProjectFileIngestionService(ProjectApplicationService projects, FileStoragePort storage,
            ProjectFilePolicy policy, ProjectFileClassifier classifier, List<ProjectDocumentParser> parsers,
            OcrPort ocr,
            ProjectFactExtractor extractor, ContractCandidateAssembler assembler,
            ProjectArchiveProcessingService archives, ProjectFileIngestionTransactionCoordinator transactions,
            ProjectIntakeProgressionService progression,
            AutomaticProjectFileClassificationService automaticClassification,
            AuthContext authContext, Clock clock) {
        this(projects, storage, policy, classifier, parsers, ocr, extractor, assembler, archives,
                transactions, progression, automaticClassification, authContext, clock, System::nanoTime);
    }

    public ProjectFileIngestionService(ProjectApplicationService projects, FileStoragePort storage,
            ProjectFilePolicy policy, ProjectFileClassifier classifier, List<ProjectDocumentParser> parsers,
            OcrPort ocr, ProjectFactExtractor extractor, ContractCandidateAssembler assembler,
            ProjectArchiveProcessingService archives, ProjectFileIngestionTransactionCoordinator transactions,
            ProjectIntakeProgressionService progression, AuthContext authContext, Clock clock) {
        this(projects, storage, policy, classifier, parsers, ocr, extractor, assembler, archives,
                transactions, progression, null, authContext, clock, System::nanoTime);
    }

    ProjectFileIngestionService(ProjectApplicationService projects, FileStoragePort storage,
            ProjectFilePolicy policy, ProjectFileClassifier classifier, List<ProjectDocumentParser> parsers,
            OcrPort ocr, ProjectFactExtractor extractor, ContractCandidateAssembler assembler,
            ProjectArchiveProcessingService archives, ProjectFileIngestionTransactionCoordinator transactions,
            ProjectIntakeProgressionService progression, AuthContext authContext, Clock clock,
            java.util.function.LongSupplier monotonicNanos) {
        this(projects, storage, policy, classifier, parsers, ocr, extractor, assembler, archives,
                transactions, progression, null, authContext, clock, monotonicNanos);
    }

    ProjectFileIngestionService(ProjectApplicationService projects, FileStoragePort storage,
            ProjectFilePolicy policy, ProjectFileClassifier classifier, List<ProjectDocumentParser> parsers,
            OcrPort ocr, ProjectFactExtractor extractor, ContractCandidateAssembler assembler,
            ProjectArchiveProcessingService archives, ProjectFileIngestionTransactionCoordinator transactions,
            ProjectIntakeProgressionService progression,
            AutomaticProjectFileClassificationService automaticClassification,
            AuthContext authContext, Clock clock,
            java.util.function.LongSupplier monotonicNanos) {
        this.projects=projects; this.storage=storage; this.policy=policy; this.classifier=classifier;
        this.parsers=List.copyOf(parsers); this.ocr=ocr; this.extractor=extractor;
        this.archives=archives; this.transactions=transactions; this.progression=progression;
        this.automaticClassification=automaticClassification;
        this.authContext=authContext; this.clock=clock;
        this.monotonicNanos=Objects.requireNonNull(monotonicNanos);
    }

    /** Compatibility constructor for focused parser/storage tests. */
    public ProjectFileIngestionService(ProjectApplicationService projects, FileStoragePort storage,
            ProjectFilePolicy policy, ProjectFileClassifier classifier, List<ProjectDocumentParser> parsers,
            OcrPort ocr,
            ProjectFactExtractor extractor, ContractCandidateAssembler assembler,
            ProjectArchiveProcessingService archives, ProjectFileIngestionTransactionCoordinator transactions,
            AuthContext authContext, Clock clock) {
        this(projects, storage, policy, classifier, parsers, ocr, extractor, assembler, archives,
                transactions, null, null, authContext, clock, System::nanoTime);
    }

    public ProjectFile upload(ProjectFileUploadCommand command) {
        return upload(command, null);
    }

    /** Byte publication and durable admission only; parser/OCR never runs on this request. */
    public ProjectFile acceptAsync(ProjectFileUploadCommand command,
            com.fuquan.audit.contract.application.project.port.ProjectFileJobRepository jobs) {
        return upload(command, Objects.requireNonNull(jobs));
    }

    private ProjectFile upload(ProjectFileUploadCommand command,
            com.fuquan.audit.contract.application.project.port.ProjectFileJobRepository jobs) {
        CurrentUser actor = actor();
        projects.get(command.projectId());
        String filename = safeFilename(command.filename());
        StagedFile staged;
        try {
            staged = PipelineDiagnostics.run("CONTRACT", "STORAGE_STAGE", command.projectId(),
                    Map.of("byteLimit", policy.properties().maxUploadSize().toBytes()),
                    () -> storage.stage(command.input(), policy.properties().maxUploadSize().toBytes()));
        } catch (FileStorageUserRejectionException rejection) {
            BusinessErrorCode code = rejection.reason() == FileStorageUserRejectionException.Reason.SIZE_LIMIT
                    ? BusinessErrorCode.PAYLOAD_TOO_LARGE : BusinessErrorCode.INVALID_ARGUMENT;
            throw new BusinessException(code, rejection.reason() == FileStorageUserRejectionException.Reason.SIZE_LIMIT
                    ? "project file exceeds upload size limit" : "empty project file is not allowed");
        }
        UploadStart start = null;
        boolean completionAttempted = false;
        try {
            if (command.declaredSize() > 0 && command.declaredSize() != staged.size()) {
                throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "declared upload size mismatch");
            }
            ProjectFileCategory category = rootCategory(filename);
            if (policy.decide(filename, command.mediaType(), false, staged.size(), staged.header())
                    == ProjectFilePolicy.Decision.REJECTED) {
                throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "project file type is not allowed");
            }
            UUID fileId = UUID.randomUUID();
            StorageKey storageKey = storageKeys.create(fileId, staged.sha256());
            ProjectFile pending = new ProjectFile(fileId, command.projectId(), null, null, filename,
                    normalizedMediaType(command.mediaType()), category, ProjectFileIngestionStatus.UPLOAD_PENDING,
                    category == ProjectFileCategory.PACKAGE ? ProjectFileParseStatus.NOT_APPLICABLE
                            : ProjectFileParseStatus.PENDING,
                    storageKey.value(), staged.sha256(), staged.size(), null, null, clock.instant(), clock.instant());
            String fingerprint = fingerprint(actor.userId(), command.projectId().toString(), filename,
                    normalizedMediaType(command.mediaType()), staged.sha256(), Long.toString(staged.size()));
            start = PipelineDiagnostics.run("CONTRACT", "DB_UPLOAD_START_COMMIT", fileId, Map.of(),
                    () -> jobs == null ? transactions.startUpload(pending, actor, command.idempotencyKey(), fingerprint, command.metadata())
                            : transactions.startAsyncUpload(pending, actor, command.idempotencyKey(), fingerprint, command.metadata()));
            if (start.replay()) {
                storage.discard(staged);
                if (jobs == null) advance(start.file().projectId(), command.metadata(), actor);
                return start.file();
            }
            if (start.inProgress()) {
                storage.discard(staged);
                throw new BusinessException(BusinessErrorCode.CONFLICT, "project file upload is in progress");
            }
            PipelineDiagnostics.run("CONTRACT", "STORAGE_PROMOTE", fileId, Map.of("inputBytes", staged.size()),
                    () -> { storage.promote(staged, storageKey); return null; });
            ProcessingOutcome outcome = jobs == null ? processPersisted(start.file())
                    : new ProcessingOutcome(start.file().complete(ProjectFileParseStatus.PENDING,null,null,clock.instant()),
                    List.of(),List.of(),List.of(),List.of());
            completionAttempted = true;
            final UploadStart committing = start;
            ProjectFile completed = PipelineDiagnostics.run("CONTRACT", "DB_UPLOAD_COMPLETE_COMMIT", fileId, Map.of(),
                    () -> jobs == null ? transactions.completeUpload(committing, outcome, actor, command.metadata())
                            : transactions.acceptForProcessing(committing, jobs, actor, command.metadata()));
            if (jobs == null) advance(completed.projectId(), command.metadata(), actor);
            return completed;
        } catch (FileStorageException storageFailure) {
            if (start == null) {
                storage.discard(staged);
            } else if (completionAttempted) {
                return reconcileCompleteFailure(start, actor, command.metadata(), storageFailure,
                        "project file upload outcome is uncertain");
            } else if (!completionAttempted && !start.replay() && !start.inProgress()
                    && isTerminal(storageFailure)) {
                storage.discard(staged);
                transactions.failUpload(start, actor, storageFailure.reason().name(), command.metadata());
                throw new BusinessException(BusinessErrorCode.CONFLICT, "project file upload failed");
            }
            throw new BusinessException(BusinessErrorCode.CONFLICT, "project file storage outcome is uncertain");
        } catch (RuntimeException failure) {
            if (start == null) {
                storage.discard(staged);
            } else if (completionAttempted) {
                return reconcileCompleteFailure(start, actor, command.metadata(), failure,
                        "project file upload outcome is uncertain");
            } else if (!completionAttempted && !start.replay() && !start.inProgress()) {
                transactions.failUpload(start, actor, safeReason(failure), command.metadata());
            }
            throw failure;
        }
    }

    void advance(UUID projectId, ProjectRequestMetadata metadata) {
        CurrentUser actor = authContext.currentUser().orElse(null);
        if (actor != null) advance(projectId, metadata, actor);
    }

    void advance(UUID projectId, ProjectRequestMetadata metadata, CurrentUser actor) {
        if(analysisRegistry!=null&&analysisRegistry.contains(projectId))return;
        if (progression != null) progression.ensureRoleAssessments(projectId, metadata, actor);
        if (automaticClassification != null) automaticClassification.advance(projectId, metadata, actor);
    }

    boolean automaticProgressionEnabled() {
        return automaticClassification != null && automaticClassification.enabled();
    }

    public Optional<com.fuquan.audit.common.application.port.IdempotencyRecord> asyncOperation(String key) {
        return transactions.findAsyncOperation(key);
    }

    ProjectFile reconcileCompleteFailure(UploadStart start, CurrentUser actor,
                                         ProjectRequestMetadata metadata, RuntimeException failure,
                                         String uncertainMessage) {
        com.fuquan.audit.common.application.port.IdempotencyRecord durable = readAfterCompleteFailure(
                start, failure, uncertainMessage);
        if (durable.status() == com.fuquan.audit.common.application.port.IdempotencyStatus.SUCCEEDED) {
            return transactions.replayCompleted(start);
        }
        if (durable.status() == com.fuquan.audit.common.application.port.IdempotencyStatus.FAILED) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "idempotent upload previously failed", failure);
        }
        if (!(failure instanceof ProjectFactIdentityConflictException integrityFailure)) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, uncertainMessage, failure);
        }

        try {
            transactions.failUpload(start, actor, integrityFailure.reasonCode(), metadata);
        } catch (RuntimeException closureFailure) {
            failure.addSuppressed(closureFailure);
        }
        durable = readAfterCompleteFailure(start, failure, uncertainMessage);
        if (durable.status() == com.fuquan.audit.common.application.port.IdempotencyStatus.SUCCEEDED) {
            return transactions.replayCompleted(start);
        }
        if (durable.status() == com.fuquan.audit.common.application.port.IdempotencyStatus.FAILED) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "project file upload failed", failure);
        }
        throw new BusinessException(BusinessErrorCode.CONFLICT, uncertainMessage, failure);
    }

    private com.fuquan.audit.common.application.port.IdempotencyRecord readAfterCompleteFailure(
            UploadStart start, RuntimeException failure, String uncertainMessage) {
        try {
            return transactions.readCurrent(start);
        } catch (RuntimeException readFailure) {
            failure.addSuppressed(readFailure);
            throw new BusinessException(BusinessErrorCode.CONFLICT, uncertainMessage, failure);
        }
    }

    ProcessingOutcome processPersisted(ProjectFile root) {
        if (root.category() == ProjectFileCategory.PACKAGE) return processArchive(root);
        ParseOutcome direct = parse(root);
        return new ProcessingOutcome(direct.file(), List.of(), direct.candidateList(), direct.extractions(),
                direct.parseResultList());
    }

    private ProcessingOutcome processArchive(ProjectFile root) {
        return processArchive(root,true);
    }

    ProcessingOutcome processQueued(ProjectFile root) {
        return root.category() == ProjectFileCategory.PACKAGE ? processArchive(root,false) : processPersisted(root);
    }

    private ProcessingOutcome processArchive(ProjectFile root, boolean parseChildren) {
        ProjectArchiveProcessingService.ArchiveOutcome staged;
        StoredFileContent content = storage.open(new StorageKey(root.storageKey()));
        try (java.io.InputStream input = content.inputStream()) {
            staged = archives.stageChildren(root, input);
        } catch (java.io.IOException exception) {
            throw new FileStorageException("stored package close failed", exception);
        }
        List<ProjectFile> children = new ArrayList<>();
        List<ContractCandidate> candidates = new ArrayList<>();
        List<ExtractionResult> extractions = new ArrayList<>();
        List<ProjectFileParseResult> parseResults = new ArrayList<>();
        for (ProjectArchiveProcessingService.StagedChild child : staged.children()) {
            storage.promote(child.stagedFile(), child.storageKey());
            String name = child.normalizedPath().substring(child.normalizedPath().lastIndexOf('/') + 1);
            ProjectFile candidate = new ProjectFile(UUID.randomUUID(), root.projectId(), root.id(),
                    child.normalizedPath(), name, child.mediaType(), child.category(),
                    ProjectFileIngestionStatus.AVAILABLE,
                    policy.isParseSupported(child.category(), name) ? ProjectFileParseStatus.PENDING
                            : ProjectFileParseStatus.UNSUPPORTED,
                    child.storageKey().value(), child.stagedFile().sha256(), child.stagedFile().size(),
                    null, null, clock.instant(), clock.instant());
            ParseOutcome parsed = parseChildren ? parse(candidate) : new ParseOutcome(candidate,null,List.of(),null);
            children.add(parsed.file()); candidates.addAll(parsed.candidateList());
            extractions.addAll(parsed.extractions()); parseResults.addAll(parsed.parseResultList());
        }
        ProjectFile completedRoot = root.complete(ProjectFileParseStatus.NOT_APPLICABLE, null, null, clock.instant());
        return new ProcessingOutcome(completedRoot, children, candidates, extractions, parseResults);
    }

    private ParseOutcome parse(ProjectFile file) {
        Optional<ProjectDocumentParser> parser = parsers.stream().filter(value -> value.supports(file)).findFirst();
        if (parser.isEmpty() && !isOcrImage(file)) {
            return new ParseOutcome(file.complete(ProjectFileParseStatus.UNSUPPORTED, null,
                    "PARSER_UNSUPPORTED", clock.instant()), null, List.of(), null);
        }
        try {
            ProjectDocumentExtraction extraction = PipelineDiagnostics.run("CONTRACT", "DOCUMENT_PARSE", file.id(),
                    Map.of("inputBytes", file.sizeBytes()), () -> extractDocument(file, parser.orElse(null)));
            ParsedProjectDocument parsed = extraction.document();
            var sharedParse = ContractMaterialParseAdapter.adapt(file, extraction);
            String canonicalText = sharedParse.canonicalText();
            try (var span = PipelineDiagnostics.start("CONTRACT", "PARSED_TEXT_READY", file.id(), Map.of())) {
                span.success(Map.of("parseMethod", extraction.provenance().method().name(), "outputChars", canonicalText.length(),
                        "segmentCount", parsed.segments().size()));
            }
            if (canonicalText.isBlank()) {
                throw new ProjectParseException("PARSE_EMPTY_RESULT");
            }
            Instant completedAt = clock.instant();
            ProjectFile completed = file.complete(ProjectFileParseStatus.PARSED, canonicalText, null,
                    extraction.provenance(), completedAt);
            ProjectFileParseResult parseResult = ProjectFileParseResultFactory.currentPipeline(
                    completed, parsed, sharedParser!=null ? com.fuquan.audit.common.material.application.port.MaterialParsePort.PARSER_VERSION : parser.filter(ProjectDocxTextParser.class::isInstance).isPresent()
                            ? (extraction.provenance().method()==ProjectFileParseMethod.OCR
                                ? ProjectDocxTextParser.IMAGE_LAYOUT_VERSION : ProjectDocxTextParser.CANONICAL_LAYOUT_VERSION)
                            : parser.filter(com.fuquan.audit.contract.application.project.parse.ProjectDocTextParser.class::isInstance).isPresent()
                            ? com.fuquan.audit.contract.application.project.parse.ProjectDocTextParser.CANONICAL_LAYOUT_VERSION : null,
                    1, null, completedAt, completedAt);
            List<ExtractionResult> facts = analysisRegistry!=null&&analysisRegistry.contains(file.projectId())?List.of():extractor.extractPotential(
                    completed, parsed, parseResult.parseResultId());
            return new ParseOutcome(completed, null, facts, parseResult);
        } catch (ProjectParseException parseFailure) {
            return new ParseOutcome(file.complete(ProjectFileParseStatus.FAILED, null,
                    parseFailure.reasonCode(), clock.instant()), null, List.of(), null);
        }
    }

    private ProjectDocumentExtraction extractDocument(ProjectFile file, ProjectDocumentParser parser) {
        if(sharedParser!=null) {
            long started=System.nanoTime();
            var content=storage.open(new StorageKey(file.storageKey()));
            try(InputStream input=content.inputStream()) {
                var result=sharedParser.parse(new com.fuquan.audit.common.material.application.port.MaterialParsePort.ParseRequest(
                    file.id().toString(),file.originalFilename(),file.mediaType(),input,
                    policy.properties().maxUploadSize().toBytes(),512,1_000_000,Map.of()));
                if(!result.usable())throw new ProjectParseException(result.errorCode()==null?"DOCUMENT_PARSE_FAILED":result.errorCode());
                if(!file.sha256().equals(result.fileSha256()))throw new ProjectParseException("SOURCE_HASH_MISMATCH");
                return SharedProjectMaterialReader.convert(file,result,Math.max(0,(System.nanoTime()-started)/1_000_000));
            } catch(IOException failure) {throw new ProjectParseException("DOCUMENT_READ_FAILED",failure);}
        }
        if (isOcrImage(file)) {
            return recognizeStored(file);
        }
        if (isPdf(file) && parser instanceof ProjectPdfTextParser) return extractPdfPages(file);
        if(parser instanceof ProjectDocxTextParser docx){
            var content=storage.open(new StorageKey(file.storageKey()));
            try(InputStream input=content.inputStream()){return docx.extract(file,input);}
            catch(IOException failure){throw new FileStorageException("stored file close failed",failure);}
        }
        long started = System.nanoTime();
        ParsedProjectDocument parsed = parseStored(file, parser);
        if (isPdf(file) && !pdfTextUsefulness.isUseful(parsed.fullText())) {
            return recognizeStored(file);
        }
        ProjectFileParseMethod method = isPdf(file)
                ? ProjectFileParseMethod.PDF_TEXT : parser instanceof com.fuquan.audit.contract.application.project.parse.ProjectDocTextParser
                ? ProjectFileParseMethod.DOC_TEXT : ProjectFileParseMethod.DOCX_TEXT;
        String engine = isPdf(file) ? "PDFBOX_2.0.30" : "APACHE_POI_4.1.2";
        Integer pageCount = isPdf(file) ? parsed.segments().size() : null;
        return new ProjectDocumentExtraction(parsed, new ProjectFileParseProvenance(
                method, engine, null, pageCount, elapsedMs(started)));
    }

    private ParsedProjectDocument parseStored(ProjectFile file, ProjectDocumentParser parser) {
        StoredFileContent content = storage.open(new StorageKey(file.storageKey()));
        try (InputStream input = content.inputStream()) {
            return parser.parse(file, input);
        } catch (IOException exception) {
            throw new FileStorageException("stored file close failed", exception);
        }
    }

    /** Preserve native/scan/blank page order; an incomplete OCR page is never a complete document. */
    private ProjectDocumentExtraction extractPdfPages(ProjectFile file) {
        long started=monotonicNanos.getAsLong();
        var content=storage.open(new StorageKey(file.storageKey()));
        try(InputStream input=content.inputStream();var document=org.apache.pdfbox.pdmodel.PDDocument.load(input)) {
            int count=document.getNumberOfPages();
            if(count>512)throw new ProjectParseException("PDF_PAGE_LIMIT");
            var stripper=new org.apache.pdfbox.text.PDFTextStripper();
            List<ParsedProjectDocument.Segment> segments=new ArrayList<>();
            Set<String> engines=new LinkedHashSet<>();long reportedOcrMs=0;
            boolean recognized=false;BigDecimal confidence=BigDecimal.ZERO;int recognizedCount=0;
            for(int pageNumber=1;pageNumber<=count;pageNumber++) {
                requirePdfBudget(started);
                stripper.setStartPage(pageNumber);stripper.setEndPage(pageNumber);
                String text=stripper.getText(document).strip();
                if(pdfTextUsefulness.isUseful(text)||!document.getPage(pageNumber-1).hasContents()) {
                    if(!text.isBlank())engines.add("PDFBOX_2.0.30");
                    segments.add(new ParsedProjectDocument.Segment("pdf/page:"+pageNumber,text,pageNumber,null));
                    continue;
                }
                // A single scanned page already is the exact required OCR payload.
                // Keep its original storage identity/bytes instead of reserializing it.
                if(count==1) {
                    ProjectDocumentExtraction original=recognizeStored(file);
                    requirePdfBudget(started);
                    if(!Integer.valueOf(1).equals(original.provenance().pageCount())
                            || original.document().segments().size()!=1
                            || !Integer.valueOf(1).equals(original.document().segments().get(0).pageNumber())) {
                        throw new ProjectParseException("OCR_PAGE_1_COVERAGE_UNPROVEN");
                    }
                    return original;
                }
                byte[] bytes;
                try(var selected=new org.apache.pdfbox.pdmodel.PDDocument();var output=new java.io.ByteArrayOutputStream()) {
                    selected.importPage(document.getPage(pageNumber-1));selected.save(output);bytes=output.toByteArray();
                }
                if(bytes.length>50L*1024*1024)throw new ProjectParseException("OCR_PAGE_BYTE_LIMIT");
                final byte[] payload=bytes;
                OcrResult result;
                try {
                    result=ocr.recognize(new OcrRequest(file.id().toString(),file.originalFilename(),"application/pdf",
                            new OcrDocumentSource() {
                                public InputStream openStream(){return new java.io.ByteArrayInputStream(payload);}
                                public long contentLength(){return payload.length;}
                            }));
                }catch(com.fuquan.audit.review.application.port.OcrCapacityUnavailableException busy){throw busy;}
                catch(com.fuquan.audit.common.exception.ExternalServiceException uncertain){
                    throw new com.fuquan.audit.review.application.port.OcrOutcomeUnprovenException(uncertain);
                }
                catch(RuntimeException failure){throw new ProjectParseException("OCR_PAGE_"+pageNumber+"_FAILED",failure);}
                requirePdfBudget(started);
                if(result.simulated())throw new ProjectParseException("OCR_NOT_AVAILABLE");
                if(result.pages().size()!=1||result.pages().get(0).pageNumber()!=1) {
                    throw new ProjectParseException("OCR_PAGE_"+pageNumber+"_COVERAGE_UNPROVEN");
                }
                if(result.text().isBlank())throw new ProjectParseException("OCR_PAGE_"+pageNumber+"_EMPTY");
                segments.add(new ParsedProjectDocument.Segment("ocr/page:"+pageNumber,result.text(),pageNumber,null));
                recognized=true;recognizedCount++;engines.add(result.engine());
                reportedOcrMs+=Math.max(0,result.durationMs());
                confidence=confidence.add(BigDecimal.valueOf(result.confidence()));
            }
            if(engines.isEmpty())engines.add("PDFBOX_2.0.30");
            requirePdfBudget(started);
            return new ProjectDocumentExtraction(new ParsedProjectDocument(segments),new ProjectFileParseProvenance(
                    recognized?ProjectFileParseMethod.OCR:ProjectFileParseMethod.PDF_TEXT,String.join("+",engines),
                    recognized?confidence.divide(BigDecimal.valueOf(recognizedCount),4,RoundingMode.HALF_UP):null,
                    count,Math.max(reportedOcrMs,pdfElapsedMs(started))));
        }catch(ProjectParseException failure){throw failure;}
        catch(IOException failure){throw new ProjectParseException("PDF_PARSE_FAILED",failure);}
    }

    private ProjectDocumentExtraction recognizeStored(ProjectFile file) {
        long started = System.nanoTime();
        StorageKey key = new StorageKey(file.storageKey());
        OcrDocumentSource source = new OcrDocumentSource() {
            @Override
            public InputStream openStream() {
                return storage.open(key).inputStream();
            }

            @Override
            public long contentLength() {
                return file.sizeBytes();
            }
        };
        OcrResult result;
        try {
            result = ocr.recognize(new OcrRequest(file.id().toString(), file.originalFilename(),
                    file.mediaType(), source));
        } catch (com.fuquan.audit.review.application.port.OcrCapacityUnavailableException busy) {
            throw busy;
        } catch (com.fuquan.audit.common.exception.ExternalServiceException uncertain) {
            throw new com.fuquan.audit.review.application.port.OcrOutcomeUnprovenException(uncertain);
        } catch (RuntimeException failure) {
            throw new ProjectParseException("OCR_FAILED", failure);
        }
        if (result.simulated()) {
            throw new ProjectParseException("OCR_NOT_AVAILABLE");
        }
        if (result.text().isBlank()) {
            throw new ProjectParseException("OCR_EMPTY_RESULT");
        }
        List<ParsedProjectDocument.Segment> segments = result.pages().stream()
                .filter(page -> !page.text().isBlank())
                .map(this::ocrSegment)
                .toList();
        if (segments.isEmpty()) {
            segments = List.of(new ParsedProjectDocument.Segment("ocr/document", result.text()));
        }
        BigDecimal confidence = BigDecimal.valueOf(result.confidence()).setScale(4, RoundingMode.HALF_UP);
        long durationMs = result.durationMs() > 0 ? result.durationMs() : elapsedMs(started);
        return new ProjectDocumentExtraction(new ParsedProjectDocument(segments),
                new ProjectFileParseProvenance(ProjectFileParseMethod.OCR, result.engine(), confidence,
                        result.pages().isEmpty() ? null : result.pages().size(), durationMs));
    }

    private ParsedProjectDocument.Segment ocrSegment(OcrPage page) {
        return new ParsedProjectDocument.Segment("ocr/page:" + page.pageNumber(),
                page.text(), page.pageNumber(), null);
    }

    private static boolean isPdf(ProjectFile file) {
        return extension(file.originalFilename()).equals("pdf");
    }

    private static boolean isOcrImage(ProjectFile file) {
        return Set.of("png", "jpg", "jpeg").contains(extension(file.originalFilename()));
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private long pdfElapsedMs(long started) {
        return Math.max(0L, (monotonicNanos.getAsLong() - started) / 1_000_000L);
    }

    private void requirePdfBudget(long started) {
        if(pdfElapsedMs(started)>1_800_000L)throw new ProjectParseException("PDF_PROCESSING_DEADLINE");
    }

    private CurrentUser actor() {
        return authContext.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }
    private ProjectFileCategory rootCategory(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(".zip") ? ProjectFileCategory.PACKAGE : classifier.classify(filename);
    }
    private static String normalizedMediaType(String value) {
        return value == null || value.isBlank() ? "application/octet-stream"
                : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
    private static String safeFilename(String value) {
        if (value == null) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid filename");
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid filename");
        }
        return name;
    }
    private static String safeReason(RuntimeException failure) {
        if (failure instanceof com.fuquan.audit.contract.application.project.archive.ProjectArchiveSecurityException archive) return archive.reasonCode();
        if (failure instanceof BusinessException business) return business.errorCode().name();
        return failure.getClass().getSimpleName().substring(0, Math.min(64, failure.getClass().getSimpleName().length()));
    }
    private static boolean isTerminal(FileStorageException failure) {
        return failure.reason() == StorageFailureReason.PUBLICATION_UNSUPPORTED
                || failure.reason() == StorageFailureReason.CONTENT_CONFLICT;
    }
    private static String fingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) { byte[] bytes=value.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record ParseOutcome(ProjectFile file, ContractCandidate candidate, List<ExtractionResult> extractions,
                                ProjectFileParseResult parseResult) {
        List<ContractCandidate> candidateList() { return candidate == null ? List.of() : List.of(candidate); }
        List<ProjectFileParseResult> parseResultList() {
            return parseResult == null ? List.of() : List.of(parseResult);
        }
    }
}
