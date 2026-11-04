package com.fuquan.audit.contract.application.debug;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SanitizedDebugBundleService {
    private final AuthContext auth;
    private final SanitizedDebugBundleRepository bundles;
    private final DebugBundleTransactions transactions;
    private final FileStoragePort storage;
    public SanitizedDebugBundleService(AuthContext auth,SanitizedDebugBundleRepository bundles,DebugBundleTransactions transactions,FileStoragePort storage) {
        this.auth=auth;this.bundles=bundles;this.transactions=transactions;this.storage=storage;
    }
    /** Never hold a caller transaction over FileStorage; START and closure use separate transactions. */
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public SanitizedDebugBundle generate(DebugScope scope,DebugContentLevel level,String key,ProjectRequestMetadata metadata) {
        CurrentUser actor=actor();
        var bundle=transactions.start(scope,level,key,actor,metadata);
        if(bundle.status()==SanitizedDebugBundle.Status.AVAILABLE) return bundle;
        try {
            StorageKey target=new StorageKey(bundle.storageKey());
            var inspection=storage.inspect(target);
            if(inspection.status()==StoredFileInspection.Status.UNKNOWN) return transactions.unknown(bundle.bundleId(),actor,metadata);
            if(inspection.status()==StoredFileInspection.Status.DEFINITELY_MISSING) {
                StagedFile staged=storage.stage(new ByteArrayInputStream(bundle.manifestJson().getBytes(StandardCharsets.UTF_8)),DebugManifestBuilder.MAX_BYTES);
                try {
                    if(staged.size()!=bundle.artifactSize() || !staged.sha256().equals(bundle.artifactSha256())) throw uncertain();
                    storage.promote(staged,target);
                } finally { storage.discard(staged); }
                inspection=storage.inspect(target);
            }
            if(!matches(bundle,inspection)) return transactions.unknown(bundle.bundleId(),actor,metadata);
            return transactions.complete(bundle.bundleId(),actor,metadata);
        } catch(RuntimeException uncertain) {
            // A committed COMPLETE is never downgraded. If DB is unreadable, propagate safe uncertainty.
            try { return transactions.unknown(bundle.bundleId(),actor,metadata); }
            catch(RuntimeException unavailable) { throw uncertain(); }
        }
    }
    public SanitizedDebugBundle get(DebugScope scope,UUID id,ProjectRequestMetadata metadata) {
        var actor=actor();var bundle=owned(scope,id);transactions.auditRead(bundle,actor,metadata,"REOPEN");return bundle;
    }
    public List<SanitizedDebugBundle> list(DebugScope scope,ProjectRequestMetadata metadata) {
        var actor=actor();
        transactions.auditList(scope,actor,metadata);
        var found=bundles.findByRunId(scope.runId()).stream().filter(b -> owner(scope,b.scope())).toList();
        return found;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public byte[] download(DebugScope scope,UUID id,ProjectRequestMetadata metadata) {
        var actor=actor();var bundle=owned(scope,id);
        if(bundle.status()!=SanitizedDebugBundle.Status.AVAILABLE) throw uncertain();
        StorageKey key=new StorageKey(bundle.storageKey());
        if(!matches(bundle,storage.inspect(key))) throw uncertain();
        byte[] bytes;
        try(var input=storage.open(key).inputStream()) { bytes=input.readNBytes(DebugManifestBuilder.MAX_BYTES+1); }
        catch(IOException failure) { throw uncertain(); }
        if(bytes.length!=bundle.artifactSize() || !ContractSnapshotCanonicalizer.sha256(new String(bytes,StandardCharsets.UTF_8)).equals(bundle.artifactSha256())) throw uncertain();
        transactions.auditRead(bundle,actor,metadata,"DOWNLOAD");return bytes;
    }
    private SanitizedDebugBundle owned(DebugScope scope,UUID id) {
        return bundles.findById(id).filter(b -> owner(scope,b.scope())).orElseThrow(DebugBundleTransactions::notFound);
    }
    private static boolean owner(DebugScope requested,DebugScope stored) {
        return requested.projectId().equals(stored.projectId()) && requested.contractId().equals(stored.contractId())
                && requested.runId().equals(stored.runId()) && (requested.stepId()==null || requested.stepId().equals(stored.stepId()))
                && (requested.attemptId()==null || requested.attemptId().equals(stored.attemptId()));
    }
    private CurrentUser actor() {
        var actor=auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        if(!actor.roles().contains("AUDITOR") && !actor.roles().contains("SYSTEM_ADMIN")) throw new BusinessException(BusinessErrorCode.FORBIDDEN);
        return actor;
    }
    private static boolean matches(SanitizedDebugBundle bundle,StoredFileInspection inspection) {
        return inspection.status()==StoredFileInspection.Status.PRESENT && inspection.file().size()==bundle.artifactSize()
                && inspection.file().sha256().equals(bundle.artifactSha256());
    }
    private static BusinessException uncertain() { return new BusinessException(BusinessErrorCode.CONFLICT,"debug artifact outcome cannot be verified; retain original operation key"); }
}
