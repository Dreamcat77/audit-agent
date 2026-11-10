package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.*;
import com.fuquan.audit.budget.domain.*;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class BudgetMaterialService {
    private final BudgetMaterialJobRepository jobs;
    private final BudgetReviewTaskRepository tasks;
    private final BudgetMaterialStoragePort storage;
    private final BudgetMaterialTransactions tx;
    private final BudgetReviewService review;
    private final BudgetMaterialBatchService batches;
    private final BudgetUploadedMaterialReader reader;
    public BudgetMaterialService(BudgetMaterialJobRepository jobs,BudgetReviewTaskRepository tasks,
            BudgetMaterialStoragePort storage,BudgetMaterialTransactions tx,BudgetReviewService review,BudgetMaterialBatchService batches,BudgetUploadedMaterialReader reader) {
        this.jobs=jobs;this.tasks=tasks;this.storage=storage;this.tx=tx;this.review=review;this.batches=batches;this.reader=reader;
    }
    public BudgetMaterialJob upload(String name,String type,long size,InputStream input,String year,String department,String key,CurrentUser user) throws IOException {
        return upload(name,type,size,input,year,department,key,user,null);
    }
    public BudgetMaterialJob upload(String name,String type,long size,InputStream input,String year,String department,String key,CurrentUser user,UUID batchId) throws IOException {
        review.validateMaterial(name,size);
        if(name.length()>255 || year!=null&&year.length()>16 || department!=null&&department.length()>64)
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,"材料名称或预算筛选条件过长");
        var old=jobs.operation(user.userId(),key);
        if(batchId!=null)batches.validateUpload(batchId,name,size,key,year,department,user);
        if(old.isPresent()&&batchId!=null&&!batchId.equals(batches.sourceBatch(old.get().id())))throw new BusinessException(BusinessErrorCode.CONFLICT,"原上传操作属于其他材料批次");
        var digest=sha();
        String reference=null;
        try(var source=new DigestInputStream(input,digest)) {
            if(old.isPresent()) {byte[] buffer=new byte[8192];long count=0;int n;while((n=source.read(buffer))!=-1){count+=n;if(count>BudgetReviewService.MAX_FILE_SIZE)throw new IOException("file too large");}if(count!=size)throw new IOException("file size changed");}
            else reference=storage.store(source,name,BudgetReviewService.MAX_FILE_SIZE).reference();
        }
        String hash=HexFormat.of().formatHex(digest.digest());
        if(old.isPresent()) return validateReplay(old.get(),name,size,year,department,hash);
        UUID id=UUID.randomUUID();Instant now=Instant.now();
        var task=new BudgetReviewTask(id,name,type,size,reference,"QUEUED",0,null,year,department,now,now);
        var job=new BudgetMaterialJob(id,user.userId(),key,hash,"QUEUED",null,null,now);
        if(batchId==null) {
            batchId=UUID.nameUUIDFromBytes((user.userId()+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            batches.register(batchId,List.of(new BudgetMaterialBatch.Member(UUID.fromString(key),name,size)),year,department,user);
        }
        try {tx.startInBatch(job,task,batchId,user);return job;}
        catch(DuplicateKeyException race) {
            var replay=jobs.operation(user.userId(),key).orElseThrow();
            if(!batchId.equals(batches.sourceBatch(replay.id())))throw new BusinessException(BusinessErrorCode.CONFLICT,"原上传操作属于其他材料批次");
            return validateReplay(replay,name,size,year,department,hash);
        }
    }
    private BudgetMaterialJob validateReplay(BudgetMaterialJob job,String name,long size,String year,String department,String hash) {
        var task=tasks.findById(job.id()).orElseThrow();
        if(!job.sha256().equals(hash)||!task.fileName().equals(name)||task.fileSize()!=size||!Objects.equals(year,task.budgetYear())||!Objects.equals(department,task.departmentCode()))
            throw new BusinessException(BusinessErrorCode.CONFLICT,"上传操作与原材料不一致");
        return job;
    }
    public Optional<BudgetMaterialJob> operation(CurrentUser user,String key) {return jobs.operation(user.userId(),key);}
    public BudgetMaterialJob owned(UUID id,CurrentUser user) {
        return jobs.find(id).filter(j->j.actorId().equals(user.userId())).orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));
    }
    public BudgetReviewTask task(UUID id) {return tasks.findById(id).orElseThrow();}
    public InputStream openOriginal(UUID id,CurrentUser actor)throws IOException {
        owned(id,actor);var task=task(id);
        if(task.tempFilePath()==null)throw new BusinessException(BusinessErrorCode.NOT_FOUND);
        return storage.open(task.tempFilePath());
    }
    public void process(UUID id,CurrentUser user,java.util.function.Consumer<String> claimed) {
        String token=UUID.randomUUID().toString();Instant now=Instant.now();
        if(!tx.claim(id,token,now,now.plusSeconds(1200))) return;
        claimed.accept(token);
        if(Thread.currentThread().isInterrupted()) return; // Leave the durable lease for restart recovery.
        try(var span=PipelineDiagnostics.start("BUDGET","BACKGROUND_EXECUTION",id,Map.of())) {
            try {
                var task=task(id);
                UUID batchId=batches.sourceBatch(id);
                if(batchId!=null) {
                    verifyStored(task,jobs.find(id).orElseThrow().sha256());
                    BudgetUploadedFacts.Snapshot facts;
                    try {facts=reader.read(task);}
                    catch(IOException e){throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,"预算材料读取或依据核对未完成",e);}
                    if(Thread.currentThread().isInterrupted())return;
                    tx.extracted(id,token,batches.encode(facts),facts.rows().size(),user);
                    span.success(Map.of("rowCount",facts.rows().size()));
                    return;
                }
                BudgetReviewService.PreparedMaterial prepared=null;
                if(!Set.of("PARSED","REVIEWED","WAITING_CONFIRMATION").contains(task.status())) {
                    verifyStored(task,jobs.find(id).orElseThrow().sha256());
                    try {prepared=review.prepareStored(task);}
                    catch(IOException e) {throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,"预算材料格式无法解析",e);}
                    tx.parsed(prepared.task(),token);
                }
                if(prepared==null)review.reviewReference(id,()->jobs.requireLease(id,token));
                else review.reviewPreparedReference(id,()->jobs.requireLease(id,token),prepared);
                if(Thread.currentThread().isInterrupted()) return;
                tx.finish(id,token,"PARSED",user);
                span.success(Map.of());
            } catch(Exception error) {
                if(error instanceof com.fuquan.audit.review.application.port.OcrCapacityUnavailableException) {
                    span.outcome("QUEUED",Map.of("reason","OCR_CAPACITY_BUSY"));
                    if(!Thread.currentThread().isInterrupted())tx.finish(id,token,"QUEUED","OCR_CAPACITY_BUSY",user);
                    return;
                }
                span.fail(error);
                if(Thread.currentThread().isInterrupted()) return;
                if(error instanceof com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated ended) {
                    tx.terminate(id,token,ended.reasonCode(),user);
                    cleanup(task(id));
                    return;
                }
                // An uncertain filesystem/database outcome is not a proved missing file or failure.
                String status=error instanceof com.fuquan.audit.review.application.port.OcrOutcomeUnprovenException
                    ?"UNKNOWN":error instanceof BusinessException?"FAILED":"UNKNOWN";
                tx.finish(id,token,status,BudgetProcessingOutcome.reason(error),user);
            }
        }
        finally {if(!Thread.currentThread().isInterrupted())processReadyBatch(id);}
    }
    private void processReadyBatch(UUID id) {UUID batchId=batches.sourceBatch(id);if(batchId!=null)batches.process(batchId);}
    public void cleanupTimedOut(){for(var task:tasks.timedOutForCleanup(50))cleanup(task);}
    private void cleanup(BudgetReviewTask task) {
        if(!task.status().equals("FAILED")||task.parsedLineCount()!=0||task.tempFilePath()==null||
            !Set.of("MATERIAL_CALL_TIMEOUT","MATERIAL_TIME_LIMIT","MATERIAL_QUEUE_TIMEOUT").contains(Objects.toString(task.errorDetail(),"")))return;
        try {storage.delete(task.tempFilePath());tx.cleaned(task.id(),task.tempFilePath());}
        catch(IOException|RuntimeException error){try(var span=PipelineDiagnostics.start("BUDGET","TIMEOUT_CLEANUP",task.id(),Map.of())){span.fail(error);}}
    }
    public void processPendingBatch(){batches.processNext();}
    private void verifyStored(BudgetReviewTask task,String expected) throws IOException {
        var digest=sha();long count=0;
        try(var input=new DigestInputStream(storage.open(task.tempFilePath()),digest)) {
            byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){count+=n;if(count>BudgetReviewService.MAX_FILE_SIZE)throw new IOException("stored file too large");}
        }
        if(count!=task.fileSize()||!HexFormat.of().formatHex(digest.digest()).equals(expected))
            throw new BusinessException(BusinessErrorCode.CONFLICT,"已保存的预算材料与上传记录不一致");
    }
    public void rejectOwner(UUID id) {
        String token=UUID.randomUUID().toString();Instant now=Instant.now();
        if(tx.claim(id,token,now,now.plusSeconds(1200))) tx.finish(id,token,"FAILED",null);
    }
    private static MessageDigest sha() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
