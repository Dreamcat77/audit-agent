package com.fuquan.audit.common.material.application;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialIntakeRepository;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.common.material.application.port.DocumentTextPort;
import com.fuquan.audit.common.material.domain.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class MaterialIntakeService {
    private final MaterialIntakeRepository repository;
    private final MaterialIntakeTransactions transactions;
    private final FileStoragePort storage;
    private final DocumentTextPort documentReader;
    private final MaterialFieldNormalizer extractor;
    private final MaterialFieldAiPort ai;
    private final Clock clock;
    private final Semaphore capacity;
    private final Semaphore uploadCapacity;
    private final long maxBytes;
    private final int maxPages;
    private final int maxChars;
    private final Duration lease;
    public MaterialIntakeService(MaterialIntakeRepository repository, MaterialIntakeTransactions transactions,
            FileStoragePort storage, DocumentTextPort documentReader, MaterialFieldNormalizer extractor,
            MaterialFieldAiPort ai, Clock clock, @Value("${audit.procurement.material.max-concurrent:2}") int maxConcurrent,
            @Value("${audit.procurement.material.max-bytes:524288000}") long maxBytes,
            @Value("${audit.procurement.material.max-pages:100}") int maxPages,
            @Value("${audit.procurement.material.max-chars:192000}") int maxChars,
            @Value("${audit.procurement.material.lease-seconds:1200}") long leaseSeconds) {
        if (maxConcurrent < 1 || maxConcurrent > 8 || maxBytes < 1 || maxPages < 1 || maxChars < 1 || leaseSeconds < 300)
            throw new IllegalArgumentException("invalid procurement material resource limits");
        this.repository = repository; this.transactions = transactions; this.storage = storage;
        this.documentReader = documentReader; this.extractor = extractor; this.clock = clock;
        this.ai = ai;
        this.capacity = new Semaphore(maxConcurrent, true); this.uploadCapacity = new Semaphore(maxConcurrent, true); this.maxBytes = maxBytes;
        this.maxPages = maxPages; this.maxChars = maxChars; this.lease = Duration.ofSeconds(leaseSeconds);
    }
    public MaterialIntake upload(String originalName, InputStream input, String key, CurrentUser user) {
        return uploadInternal(originalName, input, key, user, false);
    }
    public MaterialIntake uploadAsync(String originalName, InputStream input, String key, CurrentUser user) {
        return uploadInternal(originalName, input, key, user, true);
    }
    private MaterialIntake uploadInternal(String originalName, InputStream input, String key, CurrentUser user, boolean async) {
        UUID.fromString(key);
        String fileName = safeName(originalName);
        String media = mediaType(fileName);
        if (async) {
            if (!uploadCapacity.tryAcquire()) throw new BusinessException(BusinessErrorCode.CONFLICT, "文件正在保存，请稍后继续原上传操作");
        } else acquire();
        StagedFile staged = null;
        try {
            staged = PipelineDiagnostics.run("PROCUREMENT", "STORAGE_STAGE", null, Map.of("byteLimit", maxBytes),
                    () -> storage.stage(input, maxBytes));
            final StagedFile validating = staged;
            PipelineDiagnostics.run("PROCUREMENT", "FILE_SIGNATURE", null, Map.of("inputBytes", staged.size()),
                    () -> { validateSignature(media, validating.header()); return null; });
            var existing = repository.findOperation(user.userId(), key);
            if (existing.isPresent()) {
                if (!existing.get().sha256().equals(staged.sha256()) || !existing.get().fileName().equals(fileName))
                    throw new BusinessException(BusinessErrorCode.CONFLICT, "同一上传操作不能更换文件，请先核对已有结果");
                return existing.get();
            }
            UUID id = UUID.randomUUID(); Instant now = clock.instant();
            String storageKey = hash("procurement-material-v1:" + id + ":" + staged.sha256());
            var record = new MaterialIntake(id, user.userId(), key, fileName, media, staged.size(),
                    staged.sha256(), storageKey, "PROCESSING", UUID.randomUUID().toString(), now.plus(lease),
                    null, null, null, now, now);
            try { PipelineDiagnostics.run("PROCUREMENT", "DB_START_COMMIT", id, Map.of(),
                    () -> { transactions.start(record, user); return null; }); }
            catch (DuplicateKeyException concurrent) {
                var replay = repository.findOperation(user.userId(), key).orElseThrow(() -> concurrent);
                if (!replay.sha256().equals(staged.sha256()) || !replay.fileName().equals(fileName))
                    throw new BusinessException(BusinessErrorCode.CONFLICT, "上传操作与已有文件不一致");
                return replay;
            }
            final StagedFile promoted = staged;
            try { PipelineDiagnostics.run("PROCUREMENT", "STORAGE_PROMOTE", id, Map.of("inputBytes", staged.size()),
                    () -> { storage.promote(promoted, new StorageKey(storageKey)); return null; }); }
            catch (RuntimeException uncertain) {
                // Promotion may have succeeded before its response failed. Preserve UNKNOWN.
                transactions.complete(record.finish("UNKNOWN", null, null, "STORAGE_OUTCOME_UNKNOWN", clock.instant()), user);
                return find(id);
            }
            if (async) {
                PipelineDiagnostics.run("PROCUREMENT", "DB_QUEUE_COMMIT", id, Map.of(),
                        () -> transactions.enqueue(record.finish("QUEUED", null, null, null, clock.instant()), user));
                return find(id);
            }
            return process(record, user);
        } finally {
            try { if (staged != null) storage.discard(staged); }
            finally { if (async) uploadCapacity.release(); else capacity.release(); }
        }
    }
    public MaterialIntake retry(UUID id, CurrentUser user) {
        var previous = findOwned(id, user);
        if (previous.timedOut() || "PARSED".equals(previous.status()) || previous.active(clock.instant())) return previous;
        acquire();
        try {
            Instant now = clock.instant();
            var record = previous.claim(UUID.randomUUID().toString(), now.plus(lease), now);
            if (!PipelineDiagnostics.run("PROCUREMENT", "DB_RETRY_CLAIM", id, Map.of(),
                    () -> transactions.claim(record, now, user))) return find(id);
            return process(record, user);
        } finally { capacity.release(); }
    }
    public MaterialIntake retryAsync(UUID id, CurrentUser user) {
        var previous = findOwned(id, user);
        if (previous.timedOut() || "PARSED".equals(previous.status()) || "QUEUED".equals(previous.status()) || previous.active(clock.instant())) return previous;
        Instant now = clock.instant();
        var record = previous.claim(UUID.randomUUID().toString(), now.plus(lease), now);
        if (transactions.claim(record, now, user))
            transactions.enqueue(record.finish("QUEUED", null, null, null, now), user);
        return find(id);
    }
    /** Scanner only claims persisted queued/expired records. FAILED/UNKNOWN require explicit retry. */
    public void processPending(UUID id, CurrentUser user, java.util.function.Consumer<MaterialIntake> onClaim) {
        if (!capacity.tryAcquire()) return;
        try {
            var previous = find(id);
            Instant now = clock.instant();
            if (!previous.actorId().equals(user.userId()) || !("QUEUED".equals(previous.status())
                    || ("PROCESSING".equals(previous.status()) && !previous.active(now)))) return;
            var record = previous.claim(UUID.randomUUID().toString(), now.plus(lease), now);
            if (!transactions.claimPending(record, now, user)) return;
            onClaim.accept(record);
            process(record, user);
        } finally { capacity.release(); }
    }
    public boolean renew(MaterialIntake record) {
        return repository.renew(record.id(), record.leaseToken(), clock.instant().plus(lease));
    }
    public void rejectPendingOwner(UUID id) {
        var previous = find(id);
        Instant now = clock.instant();
        if (!("QUEUED".equals(previous.status()) || ("PROCESSING".equals(previous.status()) && !previous.active(now)))) return;
        var record = previous.claim(UUID.randomUUID().toString(), now.plus(lease), now);
        if (transactions.claimPending(record, now, null)) transactions.complete(
                record.finish("FAILED", null, null, "MATERIAL_OWNER_UNAVAILABLE", now), null);
    }
    public MaterialIntake find(UUID id) {
        return repository.find(id).orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "采购材料不存在"));
    }
    /** Upload workspace records are private to their uploader, including for administrators. */
    public MaterialIntake findOwned(UUID id, CurrentUser user) {
        return repository.find(id).filter(record -> record.actorId().equals(user.userId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "采购材料不存在"));
    }
    public Optional<MaterialIntake> operation(CurrentUser user, String key) {
        UUID.fromString(key); return repository.findOperation(user.userId(), key);
    }
    public List<MaterialIntake> recent(CurrentUser user) { return repository.recent(user.userId(), 1000); }
    private MaterialIntake process(MaterialIntake record, CurrentUser user) {
        try (var span = PipelineDiagnostics.start("PROCUREMENT", "MATERIAL_PROCESS", record.id(),
                Map.of("inputBytes", record.fileSize(), "textLimit", maxChars))) {
            try {
                var result = processInternal(record, user);
                span.outcome(result.status(), Map.of("reason", result.errorCode() == null ? "NONE" : result.errorCode()));
                return result;
            } catch (RuntimeException error) { span.fail(error); throw error; }
        }
    }
    private MaterialIntake processInternal(MaterialIntake record, CurrentUser user) {
        if (Thread.currentThread().isInterrupted()) return find(record.id());
        MaterialIntake finished;
        try {
            var inspection = PipelineDiagnostics.run("PROCUREMENT", "STORAGE_INSPECT", record.id(), Map.of(),
                    () -> storage.inspect(new StorageKey(record.storageKey())));
            try (var span = PipelineDiagnostics.start("PROCUREMENT", "STORAGE_STATE", record.id(), Map.of())) {
                span.outcome(inspection.status().name(), Map.of());
            }
            if (inspection.status() == StoredFileInspection.Status.UNKNOWN) {
                finished = record.finish("UNKNOWN", null, null, "STORAGE_OUTCOME_UNKNOWN", clock.instant());
            } else if (inspection.status() == StoredFileInspection.Status.DEFINITELY_MISSING) {
                finished = record.finish("FAILED", null, null, "SOURCE_FILE_MISSING", clock.instant());
            } else if (inspection.file().size() != record.fileSize() || !inspection.file().sha256().equals(record.sha256())) {
                finished = record.finish("FAILED", null, null, "SOURCE_FILE_MISMATCH", clock.instant());
            } else {
                Parsed parsed;
                try (var span = PipelineDiagnostics.start("PROCUREMENT", "DOCUMENT_PARSE", record.id(),
                        Map.of("textLimit", maxChars, "inputBytes", record.fileSize()))) {
                    try {
                        parsed = parse(record);
                        span.success(Map.of("parseMethod", parsed.method(), "segmentCount", parsed.segments().size(),
                                "outputChars", parsed.segments().stream().mapToLong(s -> s.text().length()).sum()));
                    } catch (RuntimeException error) {
                        span.outcome("FAILED", error instanceof ParseFailure p ? Map.of("reason", p.code) : PipelineDiagnostics.failure(error));
                        throw error;
                    }
                }
                MaterialFieldAiPort.Result result;
                try {
                    int inputChars = parsed.segments().stream().mapToInt(s -> s.text().codePointCount(0, s.text().length())).sum();
                    try (var span = PipelineDiagnostics.start("PROCUREMENT", "MODEL_FIELD_EXTRACTION", record.id(),
                            Map.of("businessType", "PROCUREMENT", "inputChars", inputChars,
                                    "segmentCount", parsed.segments().size()))) {
                        try {
                            result = ai.extract("PROCUREMENT", record.id().toString(), record.sha256(), parsed.segments().stream()
                                    .map(s -> new MaterialFieldAiPort.Segment(s.locator(), s.text())).toList());
                            int factCount = result == null || result.rows() == null ? 0
                                    : result.rows().stream().filter(Objects::nonNull).mapToInt(row -> row.facts() == null ? 0 : row.facts().size()).sum();
                            span.outcome(factCount == 0 ? "NO_GROUNDED_FIELDS" : "FIELDS_RETURNED", Map.of(
                                    "factCount", factCount,
                                    "rowCount", result == null || result.rows() == null ? 0 : result.rows().size(),
                                    "evidenceRejectedCount", result == null ? 0 : result.issues().size()));
                        } catch (RuntimeException failure) {
                            span.fail(failure);
                            throw failure;
                        }
                    }
                } catch (com.fuquan.audit.common.material.application.port.MaterialExtractionUnknown unknown) {
                    throw unknown;
                } catch (com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated ended) {
                    throw ended;
                } catch (RuntimeException unavailable) { throw new ParseFailure("AI_EXTRACTION_UNAVAILABLE"); }
                if (result == null || result.rows() == null) throw new ParseFailure("AI_RESULT_INVALID");
                if (result.rows().stream().anyMatch(r -> r == null || r.facts() == null)) throw new ParseFailure("AI_RESULT_INVALID");
                MaterialIntakeFields fields;
                try (var span = PipelineDiagnostics.start("PROCUREMENT", "FIELD_NORMALIZATION", record.id(), Map.of())) {
                    try { fields = extractor.normalize(result, parsed.segments()); }
                    catch (MaterialFieldNormalizer.MultipleProjectMaterial invalid) {
                        span.outcome("FAILED", Map.of("reason", "MULTIPLE_PROJECTS_REQUIRE_SEPARATE_FILES"));
                        throw new ParseFailure("MULTIPLE_PROJECTS_REQUIRE_SEPARATE_FILES");
                    } catch (IllegalArgumentException invalid) {
                        span.outcome("FAILED", Map.of("reason", "AI_EVIDENCE_INVALID"));
                        throw new ParseFailure("AI_EVIDENCE_INVALID");
                    }
                    span.success(Map.of("factCount", result.rows().stream().mapToInt(r -> r.facts().size()).sum(),
                            "rowCount", result.rows().size(), "itemCount", fields.evidence().size(),
                            "evidenceRejectedCount", result.issues().size()));
                }
                // No grounded fields is a completed extraction with an empty projection,
                // not a technical parse failure. This avoids inventing values or exposing
                // an unhelpful unrecognized-field state.
                finished = record.finish("PARSED", fields, parsed.method(), null, clock.instant());
            }
        } catch (com.fuquan.audit.review.application.port.OcrCapacityUnavailableException busy) {
            finished=record.finish("QUEUED",null,null,"OCR_CAPACITY_BUSY",clock.instant());
        } catch (RuntimeException failure) {
            boolean terminated=failure instanceof com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated;
            String code = terminated ? ((com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated)failure).reasonCode() : failure instanceof ParseFailure p ? p.code
                    : failure instanceof com.fuquan.audit.common.material.application.port.MaterialExtractionUnknown
                    ? "AI_CALL_OUTCOME_UNKNOWN" : failure instanceof com.fuquan.audit.review.application.port.OcrOutcomeUnprovenException
                    ? "OCR_OUTCOME_UNPROVEN" : "STORAGE_OUTCOME_UNKNOWN";
            finished = record.finish(terminated || failure instanceof ParseFailure ? "FAILED" : "UNKNOWN", null, null, code, clock.instant());
        }
        // Shutdown is an uncertain outcome. Retain PROCESSING and its durable lease
        // so the next worker can reopen the original operation after expiry.
        if (Thread.currentThread().isInterrupted()) return find(record.id());
        final MaterialIntake completion = finished;
        try (var span = PipelineDiagnostics.start("PROCUREMENT", "DB_COMPLETE_COMMIT", record.id(), Map.of())) {
            try {
                boolean changed = transactions.complete(completion, user);
                span.outcome(changed ? "COMMITTED" : "STALE_LEASE", Map.of());
                if(changed&&completion.timedOut())cleanup(completion);
            } catch (RuntimeException error) { span.fail(error); throw error; }
        }
        return PipelineDiagnostics.run("PROCUREMENT", "DB_REOPEN", record.id(), Map.of(), () -> find(record.id()));
    }
    private Parsed parse(MaterialIntake record) {
        try(var input=storage.open(new StorageKey(record.storageKey())).inputStream()) {
            var result=documentReader.read(input,record.fileName(),record.id().toString(),maxBytes,maxPages,maxChars);
            if(!result.sha256().equals(record.sha256())) throw new ParseFailure("SOURCE_FILE_MISMATCH");
            return new Parsed(result.segments().stream().map(s->new MaterialFieldNormalizer.Segment(s.locator(),s.text())).toList(),result.method());
        } catch(DocumentTextPort.ReadFailure e) {throw new ParseFailure(e.code());}
          catch(IOException e) {throw new ParseFailure("DOCUMENT_READ_FAILED");}
    }
    public void cleanupTimedOut(){for(var record:repository.timedOutForCleanup(50))cleanup(record);}
    private void cleanup(MaterialIntake record) {
        if(!record.status().equals("FAILED")||!record.timedOut()||record.fields()!=null)return;
        try {storage.delete(new StorageKey(record.storageKey()));repository.cleaned(record.id(),record.errorCode());}
        catch(RuntimeException failure){try(var span=PipelineDiagnostics.start("PROCUREMENT","TIMEOUT_CLEANUP",record.id(),Map.of())){span.fail(failure);}}
    }
    private void acquire() {
        if (!capacity.tryAcquire()) {
            try (var span = PipelineDiagnostics.start("PROCUREMENT", "CAPACITY", null, Map.of())) {
                span.outcome("BUSY", Map.of("reason", "MATERIAL_CAPACITY_EXHAUSTED"));
            }
            throw new BusinessException(BusinessErrorCode.CONFLICT, "材料解析正在忙，请稍后继续原操作");
        }
    }
    private static String safeName(String name) {
        if (name == null || name.isBlank()) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "请选择采购材料");
        name = name.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.length() > 255 || name.contains("..") || name.chars().anyMatch(Character::isISOControl))
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "材料文件名无效");
        return name;
    }
    private static String mediaType(String name) {
        String media=OfficeMaterialTypes.mediaType(name);
        if(media==null)throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,"请上传 Word、Excel、PDF、PPT、文本或常见图片材料");
        return media;
    }
    private static void validateSignature(String media, byte[] header) {
        if (!OfficeMaterialTypes.validSignature(media,header))
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "材料内容与文件格式不一致");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private record Parsed(List<MaterialFieldNormalizer.Segment> segments, String method) {}
    private static final class ParseFailure extends RuntimeException {
        final String code; ParseFailure(String code) { super(code); this.code = code; }
    }
}
