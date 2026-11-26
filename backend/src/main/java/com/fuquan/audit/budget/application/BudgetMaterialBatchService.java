package com.fuquan.audit.budget.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.port.*;
import com.fuquan.audit.budget.domain.*;
import com.fuquan.audit.budget.domain.BudgetMaterialBatch.*;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class BudgetMaterialBatchService {
    // File workers may finish separate batches together. The semantic endpoint
    // admits one batch at a time; leave other ready batches for durable discovery.
    private final java.util.concurrent.Semaphore batchAnalysis=new java.util.concurrent.Semaphore(1);
    private com.fuquan.audit.common.application.TaskCancellationRegistry cancellationRegistry;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCancellationRegistry(com.fuquan.audit.common.application.TaskCancellationRegistry value){cancellationRegistry=value;}
    public void cancel(UUID id,CurrentUser actor) {
        var ids=tx.cancel(id,actor);
        if(cancellationRegistry!=null){cancellationRegistry.interrupt(id);ids.forEach(cancellationRegistry::interrupt);}
    }
    private final BudgetMaterialBatchRepository batches;
    private final BudgetMaterialJobRepository jobs;
    private final BudgetReviewTaskRepository tasks;
    private final BudgetMaterialBatchTransactions tx;
    private final BudgetUploadedMaterialAnalyzer analyzer;
    private final ObjectMapper json;
    private BudgetBatchReviewService batchReview;
    @org.springframework.beans.factory.annotation.Autowired
    public void setBatchReview(BudgetBatchReviewService batchReview){this.batchReview=batchReview;}
    public BudgetMaterialBatchService(BudgetMaterialBatchRepository batches,BudgetMaterialJobRepository jobs,BudgetReviewTaskRepository tasks,
            BudgetMaterialBatchTransactions tx,BudgetUploadedMaterialAnalyzer analyzer,ObjectMapper json) {
        this.batches=batches;this.jobs=jobs;this.tasks=tasks;this.tx=tx;this.analyzer=analyzer;this.json=json;
    }
    public BudgetMaterialBatch register(UUID id,List<Member> members,String year,String department,CurrentUser user) {
        if(id==null||members==null||members.isEmpty()||members.size()>1000||new HashSet<>(members.stream().map(Member::operationKey).toList()).size()!=members.size()
                ||year!=null&&year.length()>16||department!=null&&department.length()>64)invalid();
        for(var member:members)if(member.operationKey()==null||member.fileName()==null||member.fileName().isBlank()||member.fileName().length()>255
                ||member.fileSize()<=0||member.fileSize()>BudgetReviewService.MAX_FILE_SIZE)invalid();
        var proposed=new BudgetMaterialBatch(id,user.userId(),year,department,members,"OPEN",null,null,Instant.now());
        var old=batches.find(id);
        if(old.isEmpty())try {tx.register(proposed,user);return proposed;}catch(DuplicateKeyException race){old=batches.find(id);}
        var existing=old.orElseThrow();
        if(!existing.actorId().equals(user.userId()))throw new BusinessException(BusinessErrorCode.NOT_FOUND);
        if(!existing.members().equals(members)||!Objects.equals(existing.budgetYear(),year)||!Objects.equals(existing.departmentCode(),department))
            throw new BusinessException(BusinessErrorCode.CONFLICT,"本批上传清单已经确定，不能替换成员或筛选条件");
        return existing;
    }
    public void validateUpload(UUID batchId,String name,long size,String key,String year,String department,CurrentUser user) {
        var b=batches.find(batchId).filter(v->v.actorId().equals(user.userId())).orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));
        if("USER_CANCELLED".equals(b.leaseToken()))throw new BusinessException(BusinessErrorCode.CONFLICT,"该批任务已取消");
        if(!Objects.equals(year,b.budgetYear())||!Objects.equals(department,b.departmentCode())||b.members().stream().noneMatch(m->m.operationKey().toString().equals(key)&&m.fileName().equals(name)&&m.fileSize()==size))
            throw new BusinessException(BusinessErrorCode.CONFLICT,"材料与本批上传清单不一致");
    }
    public UUID sourceBatch(UUID taskId) {return batches.source(taskId).map(Source::batchId).orElse(null);}
    public BatchHistoryPage history(CurrentUser user, BudgetHistoryFilter filter, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (int) Math.min((long) (safePage - 1) * safeSize, Integer.MAX_VALUE - safeSize);
        if (filter.projectName() != null) {
            var matching = batches.findProjectNameCandidates(scope(user), filter).stream()
                    .map(reference -> batches.find(reference.batchId())
                            .filter(batch -> scope(user)==null || batch.actorId().equals(user.userId()))
                            .map(batch -> {
                                var sources=batches.sources(batch.id());
                                return new ProjectHistoryMatch(historyRecord(batch, sources), extractedProjectName(sources));
                            })
                            .orElseThrow(() -> new IllegalStateException("budget history batch missing")))
                    .filter(match -> match.projectName()!=null&&match.projectName().toLowerCase(Locale.ROOT)
                            .contains(filter.projectName().toLowerCase(Locale.ROOT)))
                    .map(ProjectHistoryMatch::record)
                    .toList();
            return new BatchHistoryPage(matching.stream().skip(offset).limit(safeSize).toList(), safePage,
                    safeSize, matching.size());
        }
        var records = batches.findHistoryPage(scope(user), filter, offset, safeSize).stream()
                .map(reference -> reference.batchId() != null ? historyBatch(reference.batchId(), user)
                        : tasks.findById(reference.taskId()).map(task -> new BatchHistoryRecord(null, task.id(),
                                task.status(), task.budgetYear(), task.departmentCode(), task.createdTime(),
                                1, List.of(task.fileName())))
                                .orElseThrow(() -> new IllegalStateException("budget history entry missing")))
                .toList();
        return new BatchHistoryPage(records, safePage, safeSize, batches.countHistory(scope(user), filter));
    }
    private BatchHistoryRecord historyBatch(UUID batchId, CurrentUser user) {
        return batches.find(batchId).filter(batch -> scope(user)==null || batch.actorId().equals(user.userId()))
                .<BatchHistoryRecord>map(this::historyRecord)
                .orElseThrow(() -> new IllegalStateException("budget history batch missing"));
    }
    private BatchHistoryRecord historyRecord(BudgetMaterialBatch batch) {
        return historyRecord(batch, batches.sources(batch.id()));
    }
    private BatchHistoryRecord historyRecord(BudgetMaterialBatch batch, List<Source> sources) {
        String name=extractedProjectName(sources);
        if(name==null)name="预算材料-"+batch.createdAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        return new BatchHistoryRecord(batch.id(),null,historyStatus(batch,sources),batch.budgetYear(),batch.departmentCode(),batch.createdAt(),
                batch.members().size(),batch.members().stream().limit(3).map(Member::fileName).toList(),name);
    }
    private String extractedProjectName(List<Source> sources) {
        String name=BudgetBatchReviewService.projectName(sources.stream().filter(source->source.factsJson()!=null)
                .map(source->new BudgetUploadedFacts.Input(source.taskId(),"", "PARSED",decode(source.factsJson(),BudgetUploadedFacts.Snapshot.class))).toList());
        return hasExtractedProjectName(name)?name:null;
    }
    private static boolean hasExtractedProjectName(String name) {
        return name!=null&&!name.equals("预算材料分析");
    }
    private String historyStatus(BudgetMaterialBatch batch,List<Source> sources) {
        if (uploadNotStartedForLongTime(batch)) return "UPLOAD_INCOMPLETE";
        if (!"REVIEWED".equals(batch.status())) return batch.status();
        if (sources.size() != batch.members().size()) return "UNKNOWN";
        var statuses = sources.stream().map(source -> tasks.findById(source.taskId())
                .map(BudgetReviewTask::status).orElse("UNKNOWN")).toList();
        long endedWithoutResult = statuses.stream().filter(status -> Set.of("FAILED", "UNKNOWN").contains(status)).count();
        if (endedWithoutResult == statuses.size()) return statuses.contains("UNKNOWN") ? "UNKNOWN" : "FAILED";
        if (endedWithoutResult > 0) return "PARTIAL";
        return "REVIEWED";
    }
    // This is a read-only presentation state. Never expire a job, overwrite the
    // manifest, or prevent a late/resumed upload merely because time passed.
    private boolean uploadNotStartedForLongTime(BudgetMaterialBatch batch) {
        if (!"OPEN".equals(batch.status()) || !batch.createdAt().isBefore(Instant.now().minus(Duration.ofHours(24)))) return false;
        return batch.members().stream().allMatch(member -> jobs.operation(batch.actorId(), member.operationKey().toString()).isEmpty());
    }
    /** Read-only batch membership used by the result page; never exposes another actor's files. */
    public BatchView view(UUID id, CurrentUser user) {
        // Result links historically carried a task id. Resolve it to the immutable
        // upload batch so old history links also show the complete batch result.
        var batch = batches.find(id).or(() -> batches.source(id).flatMap(source -> batches.find(source.batchId())))
                .filter(value -> scope(user)==null || value.actorId().equals(user.userId()))
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        var files = batch.members().stream().map(member -> {
            var job = jobs.operation(batch.actorId(), member.operationKey().toString()).orElse(null);
            if (job == null) return new BatchFile(null, member.fileName(), "OPEN".equals(batch.status()) ? "WAITING_UPLOAD" : "UNKNOWN");
            var source = batches.source(job.id()).filter(value -> batch.id().equals(value.batchId())).orElse(null);
            if (source == null) return new BatchFile(null, member.fileName(), job.status());
            var task = tasks.findById(source.taskId()).orElseThrow(() -> new IllegalStateException("budget batch task missing"));
            return new BatchFile(source.taskId(), member.fileName(), task.status());
        }).toList();
        return new BatchView(batch.id(), "USER_CANCELLED".equals(batch.leaseToken()) ? "CANCELLED" : uploadNotStartedForLongTime(batch) ? "UPLOAD_INCOMPLETE" : batch.status(), batch.budgetYear(), batch.departmentCode(),
                batch.createdAt(), files);
    }
    private static String scope(CurrentUser user) { return user.roles().contains("SYSTEM_ADMIN")?null:user.userId(); }
    public record BatchHistoryPage(List<BatchHistoryRecord> records, int page, int size, long total) {}
    private record ProjectHistoryMatch(BatchHistoryRecord record,String projectName) {}
    public record BatchHistoryRecord(UUID batchId, UUID taskId, String status, String budgetYear, String departmentCode,
            Instant createdAt, int fileCount, List<String> previewFileNames,String projectName) {
        public BatchHistoryRecord(UUID batchId,UUID taskId,String status,String budgetYear,String departmentCode,
                Instant createdAt,int fileCount,List<String> previewFileNames) {
            this(batchId,taskId,status,budgetYear,departmentCode,createdAt,fileCount,previewFileNames,null);
        }
    }
    public record BatchView(UUID batchId, String status, String budgetYear, String departmentCode,
            Instant createdAt,
            List<BatchFile> files) {}
    public record BatchFile(UUID taskId, String fileName, String status) {}
    public BudgetUploadedFacts.Snapshot snapshot(UUID taskId) {return batches.source(taskId).filter(s->s.factsJson()!=null).map(s->decode(s.factsJson(),BudgetUploadedFacts.Snapshot.class)).orElse(null);}
    public BudgetUploadedFacts.FileResult result(UUID taskId) {
        var source=batches.source(taskId);
        if(source.isEmpty())return null;
        if(batches.find(source.get().batchId()).filter(b->"USER_CANCELLED".equals(b.leaseToken())).isPresent())
            return new BudgetUploadedFacts.FileResult("CANCELLED","已取消",List.of());
        return source.get().resultJson()==null?new BudgetUploadedFacts.FileResult("PENDING","正在整理同批材料的预算参考结果，完成后会自动显示。",List.of())
            :BudgetAmountThresholdPolicy.current(decode(source.get().resultJson(),BudgetUploadedFacts.FileResult.class));
    }
    public void processNext() {for(var b:batches.pending(Instant.now(),1))process(b.id());}
    public void process(UUID id) {
        if(!batchAnalysis.tryAcquire())return;
        try(com.fuquan.audit.common.application.TaskCancellationRegistry.Registration ignored=cancellationRegistry==null?()->{}:cancellationRegistry.register(id)) {processRegistered(id);}
        finally {batchAnalysis.release();}
    }
    private void processRegistered(UUID id) {
        var batch=batches.find(id).orElseThrow();var sources=batches.sources(id);
        if(sources.size()!=batch.members().size()||sources.stream().anyMatch(s->jobs.find(s.taskId()).map(j->!Set.of("PARSED","FAILED","UNKNOWN").contains(j.status())).orElse(true)))return;
        String token=UUID.randomUUID().toString();Instant now=Instant.now();
        if(!tx.claim(id,token,now,now.plusSeconds(1200)))return;
        if(Thread.currentThread().isInterrupted())return;
        try(var span=PipelineDiagnostics.start("BUDGET","MATERIAL_BATCH_REFERENCE",id,Map.of("itemCount",sources.size()))) {
            try {
                var inputs=new ArrayList<BudgetUploadedFacts.Input>();
                for(var source:sources) {
                    var job=jobs.find(source.taskId()).orElseThrow();var task=tasks.findById(source.taskId()).orElseThrow();
                    if(!job.actorId().equals(batch.actorId()))throw new IllegalStateException("budget batch owner mismatch");
                    var member=batch.members().stream().filter(m->m.operationKey().toString().equals(job.operationKey())).findFirst().orElseThrow();
                    if(!member.fileName().equals(task.fileName())||member.fileSize()!=task.fileSize())throw new IllegalStateException("budget batch member mismatch");
                    var facts=source.factsJson()==null?null:decode(source.factsJson(),BudgetUploadedFacts.Snapshot.class);
                    if(job.status().equals("PARSED")&&facts==null)throw new IllegalStateException("budget snapshot missing");
                    inputs.add(new BudgetUploadedFacts.Input(task.id(),task.fileName(),job.status(),facts));
                }
                var analyzed=analyzer.analyzeBatch(inputs);
                if(batchReview!=null)analyzed=batchReview.review(id,inputs,analyzed);
                var encoded=new LinkedHashMap<UUID,String>();
                for(var entry:analyzed.entrySet())encoded.put(entry.getKey(),encode(entry.getValue()));
                if(Thread.currentThread().isInterrupted())return;
                tx.complete(id,token,analyzed,encoded);
                span.success(Map.of("itemCount",sources.size()));
            }catch(RuntimeException e){span.fail(e);throw e;}
        }
    }
    public String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("budget source serialization failed",e);}}
    private <T>T decode(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("budget source snapshot invalid",e);}}
    private static void invalid(){throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,"预算上传清单不完整或超出支持范围");}
}
