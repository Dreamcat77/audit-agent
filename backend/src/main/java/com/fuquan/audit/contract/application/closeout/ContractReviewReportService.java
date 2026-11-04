package com.fuquan.audit.contract.application.closeout;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.contract.application.closeout.port.*;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.domain.closeout.ContractReviewReport;
import com.fuquan.audit.contract.domain.closeout.ContractReviewReport.Status;
import com.fuquan.audit.security.application.AuthContext;
import java.io.*;
import java.security.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ContractReviewReportService {
    private final AuthContext auth;
    private final ContractReviewRunRepository runs;
    private final ContractReviewCloseoutRepository repository;
    private final ReportTransactionCoordinator transactions;
    private final ReportRendererPort renderer;
    private final FileStoragePort storage;
    public ContractReviewReportService(AuthContext auth, ContractReviewRunRepository runs, ContractReviewCloseoutRepository repository,
            ReportTransactionCoordinator transactions, ReportRendererPort renderer, FileStoragePort storage) {
        this.auth = auth; this.runs = runs; this.repository = repository;
        this.transactions = transactions; this.renderer = renderer; this.storage = storage;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ContractReviewReport generate(UUID project, UUID contract, UUID run, String key, ProjectRequestMetadata metadata) {
        ContractReviewReport report = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try { report = transactions.start(project, contract, run, key, actor(), metadata); break; }
            catch (DuplicateKeyException collision) { if (attempt == 4) throw CloseoutOwnership.conflict(); }
        }
        if (report == null) throw CloseoutOwnership.conflict();
        if (report.status() == Status.AVAILABLE || report.status() == Status.FAILED) return report;
        ReportRendererPort.RenderedReport rendered = null;
        if (report.artifactStorageKey() == null) {
            try { rendered = render(report); }
            catch (IllegalArgumentException invalid) { return transactions.fail(report.reportId()); }
            try { report = transactions.prepare(report.reportId(), rendered.mediaType(), rendered.bytes().length, sha(rendered.bytes())); }
            catch (RuntimeException uncertainPrepare) { return transactions.unknown(report.reportId()); }
        }
        var storageKey = new StorageKey(report.artifactStorageKey());
        try {
            var inspection = storage.inspect(storageKey);
            if (inspection.status() == StoredFileInspection.Status.UNKNOWN) return transactions.unknown(report.reportId());
            if (inspection.status() == StoredFileInspection.Status.PRESENT) return completeIfExact(report, inspection.file());
            if (rendered == null) rendered = render(report);
            if (!report.sha256().equals(sha(rendered.bytes())) || report.artifactSize() != rendered.bytes().length
                    || !report.artifactMediaType().equals(rendered.mediaType())) return transactions.unknown(report.reportId());
            StagedFile staged = storage.stage(new ByteArrayInputStream(rendered.bytes()), DeterministicHtmlReportRenderer.MAX_BYTES);
            try { storage.promote(staged, storageKey); }
            finally { storage.discard(staged); }
            inspection = storage.inspect(storageKey);
            if (inspection.status() != StoredFileInspection.Status.PRESENT) return transactions.unknown(report.reportId());
            return completeIfExact(report, inspection.file());
        } catch (RuntimeException uncertain) {
            // A publication or COMPLETE commit may already have happened. Never allocate another version here.
            var durable = repository.findReport(report.reportId()).orElseThrow(CloseoutOwnership::conflict);
            if (durable.status() == Status.AVAILABLE) return durable;
            return transactions.unknown(report.reportId());
        }
    }
    public ContractReviewReport get(UUID project, UUID contract, UUID run, UUID report) {
        ownership(project, contract, run);
        return repository.findReport(report).filter(r -> r.reviewRunId().equals(run)).orElseThrow(CloseoutOwnership::notFound);
    }
    public List<ContractReviewReport> list(UUID project, UUID contract, UUID run) {
        ownership(project, contract, run); return repository.findReportsByRun(run);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReportRendererPort.RenderedReport download(UUID project, UUID contract, UUID run, UUID id, ProjectRequestMetadata metadata) {
        var report = get(project, contract, run, id);
        if (report.status() != Status.AVAILABLE) throw CloseoutOwnership.conflict();
        var inspection = storage.inspect(new StorageKey(report.artifactStorageKey()));
        if (inspection.status() != StoredFileInspection.Status.PRESENT || !matches(report, inspection.file())) throw CloseoutOwnership.conflict();
        var content = storage.open(new StorageKey(report.artifactStorageKey()));
        try (var input = content.inputStream()) {
            byte[] bytes = input.readNBytes(DeterministicHtmlReportRenderer.MAX_BYTES + 1);
            if (bytes.length != report.artifactSize() || !sha(bytes).equals(report.sha256())) throw CloseoutOwnership.conflict();
            transactions.auditDownload(report, actor(), metadata);
            return new ReportRendererPort.RenderedReport(bytes, report.artifactMediaType());
        } catch (IOException unreadable) { throw CloseoutOwnership.conflict(); }
    }
    private ReportRendererPort.RenderedReport render(ContractReviewReport report) {
        var root = CloseoutCanonical.tree(report.snapshotJson());
        if (!root.path("inputIdentity").asText().equals(report.inputSnapshotIdentity())
                || !CloseoutCanonical.sha256(CloseoutCanonical.json(root.path("findings"))).equals(report.findingSnapshotIdentity())
                || !CloseoutCanonical.sha256(CloseoutCanonical.json(root.path("acknowledgements"))).equals(report.acknowledgementSnapshotIdentity()))
            throw new IllegalArgumentException("report snapshot identity mismatch");
        var result = renderer.render(report.snapshotJson());
        if (result.bytes().length == 0 || result.bytes().length > DeterministicHtmlReportRenderer.MAX_BYTES)
            throw new IllegalArgumentException("report size invalid");
        return result;
    }
    private ContractReviewReport completeIfExact(ContractReviewReport report, StoredFileInfo inspected) {
        return matches(report, inspected) ? transactions.complete(report.reportId(), inspected) : transactions.unknown(report.reportId());
    }
    private static boolean matches(ContractReviewReport r, StoredFileInfo i) { return Objects.equals(r.artifactSize(), i.size()) && Objects.equals(r.sha256(), i.sha256()); }
    private void ownership(UUID project, UUID contract, UUID run) {
        var owned = runs.findById(run).filter(r -> r.projectId().equals(project) && r.contractId().equals(contract))
                .orElseThrow(CloseoutOwnership::notFound);
        CloseoutOwnership.requireActor(owned.requestedBy(), actor());
    }
    private String actor() { return auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED)).userId(); }
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
