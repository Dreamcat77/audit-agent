package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.common.material.application.OfficeMaterialTypes;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

@Service
public class AuditCaseService {
    private final AuditCaseRepository repository;
    private final AuditCaseTransactions tx;
    private final FileStoragePort storage;
    private final DocumentTextPort reader;
    private final MaterialParsePort parser;
    private final MaterialFieldAiPort ai;
    private final Clock clock;
    private final CaseReviewEngine reviews;
    private final AuditCaseMaterialLimits limits;
    private CaseBlacklistReview blacklist;
    @org.springframework.beans.factory.annotation.Autowired
    public void setBlacklist(CaseBlacklistReview blacklist) { this.blacklist=blacklist; }
    private final java.util.concurrent.Semaphore uploads=new java.util.concurrent.Semaphore(2);
    public AuditCaseService(AuditCaseRepository repository,AuditCaseTransactions tx,FileStoragePort storage,
            DocumentTextPort reader,MaterialFieldAiPort ai,Clock clock) {
        this(repository,tx,storage,reader,ai,clock,null);
    }
    public AuditCaseService(AuditCaseRepository repository,AuditCaseTransactions tx,FileStoragePort storage,
            DocumentTextPort reader,MaterialFieldAiPort ai,Clock clock,CaseReviewEngine reviews) {
        this(repository,tx,storage,reader,ai,clock,reviews,AuditCaseMaterialLimits.defaults());
    }
    public AuditCaseService(AuditCaseRepository repository,AuditCaseTransactions tx,FileStoragePort storage,
            DocumentTextPort reader,MaterialFieldAiPort ai,Clock clock,CaseReviewEngine reviews,AuditCaseMaterialLimits limits) {
        this(repository,tx,storage,reader,null,ai,clock,reviews,limits);
    }
    /** Production constructor: the versioned common parser is preferred while the text reader remains a compatibility fallback. */
    @org.springframework.beans.factory.annotation.Autowired
    public AuditCaseService(AuditCaseRepository repository,AuditCaseTransactions tx,FileStoragePort storage,
            DocumentTextPort reader,MaterialParsePort parser,MaterialFieldAiPort ai,Clock clock,
            CaseReviewEngine reviews,AuditCaseMaterialLimits limits) {
        this.repository=repository;this.tx=tx;this.storage=storage;this.reader=reader;this.parser=parser;this.ai=ai;this.clock=clock;this.reviews=reviews;this.limits=limits;
    }
    @FunctionalInterface public interface Input {InputStream open() throws IOException;}
    public record Upload(String name,Input source) {}
    public AuditCase upload(UUID operation,List<Upload> files,CurrentUser user) throws IOException {
        if(files==null||files.isEmpty()||files.size()>100)throw invalid("请选择 1 至 100 份文件");
        if(!uploads.tryAcquire())throw new BusinessException(BusinessErrorCode.CONFLICT,"文件保存繁忙，请重试同一上传操作");
        var staged=new ArrayList<StagedFile>();
        try {
            var documents=new ArrayList<Document>();long bytes=0;
            for(var file:files) {
                String name=file.name();
                if(name==null||name.length()>255||name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl)
                        ||!OfficeMaterialTypes.supports(name))throw invalid("请选择常用办公文档、PDF 或图片，暂不支持压缩包");
                StagedFile value;
                try(var in=file.source().open()){value=storage.stage(in,limits.maxBytes());}
                catch(FileStorageUserRejectionException rejection){throw invalid(rejection.reason()==FileStorageUserRejectionException.Reason.EMPTY
                        ?"不能上传空文件":"文件大小超过当前允许范围");}
                staged.add(value);
                if(value.size()>limits.maxBytes()-bytes)throw invalid("单批材料总大小超过当前允许范围");
                bytes+=value.size();
                if(!OfficeMaterialTypes.validSignature(OfficeMaterialTypes.mediaType(name),value.header()))throw invalid("文件扩展名与真实格式不符");
                documents.add(new Document(UUID.randomUUID(),name,value.size(),value.sha256(),hash("auto-case-file/"+UUID.randomUUID()),"STAGING",List.of(),List.of(),null,null,0));
            }
            // Sorted manifest preserves identity under multipart order changes; duplicates remain significant.
            var manifest=documents.stream().map(d->d.name().length()+":"+d.name()+":"+d.sha256()+":"+d.size()).sorted().toList();
            String fingerprint=hash(String.join("\n",manifest));
            UUID id=UUID.nameUUIDFromBytes(("auto-case/"+user.userId()+"/"+operation).getBytes(StandardCharsets.UTF_8));
            var existing=repository.find(id);
            if(existing.isPresent()) {
                if(!existing.get().fingerprint().equals(fingerprint))throw new BusinessException(BusinessErrorCode.CONFLICT,"同一上传操作不能更换材料");
                return existing.get();
            }
            var value=new AuditCase(id,user.userId(),operation,fingerprint,"UPLOADING",0,UUID.randomUUID().toString(),clock.instant().plusSeconds(1800),0,clock.instant(),documents,null);
            try {tx.create(value,user);}catch(org.springframework.dao.DataIntegrityViolationException conflict){
                var replay=repository.find(id).orElseThrow(()->conflict);
                if(!replay.fingerprint().equals(fingerprint))throw new BusinessException(BusinessErrorCode.CONFLICT,"上传操作内容不一致");return replay;
            }
            boolean uncertain=false;
            for(int i=0;i<documents.size();i++) {
                var doc=documents.get(i);
                try {storage.promote(staged.get(i),new StorageKey(doc.storageKey()));documents.set(i,doc.state("STORED",null));}
                catch(RuntimeException failure){documents.set(i,doc.state("UNKNOWN","STORAGE_OUTCOME_UNKNOWN"));uncertain=true;}
            }
            var next=value.update(uncertain&&documents.stream().noneMatch(d->d.status().equals("STORED"))?"UNKNOWN":"QUEUED",documents,null);
            save(value,next,user);
            return next;
        } finally {try {for(var value:staged)try{storage.discard(value);}catch(RuntimeException cleanup){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("AUTO_CASE_STAGING_CLEANUP_FAILED exceptionType={}",cleanup.getClass().getSimpleName());
        }}finally{uploads.release();}}
    }
    public AuditCase owned(UUID id,CurrentUser user){return repository.find(id).filter(c->c.owner().equals(user.userId())).orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));}
    public List<AuditCase> recent(CurrentUser user){return repository.recent(user.userId());}
    public record HistoryPage(List<AuditCaseHistoryEntry> items,UUID nextCursor) {}
    public HistoryPage history(UUID before,int size,CurrentUser user){
        return history(before,size,null,null,null,user);
    }
    public HistoryPage history(UUID before,int size,String keyword,Instant from,Instant to,CurrentUser user){
        if(size<1||size>50)throw invalid("每页数量应为 1 至 50");
        if(keyword!=null&&keyword.length()>160||from!=null&&to!=null&&!from.isBefore(to))throw invalid("历史筛选条件不正确");
        if(before!=null)owned(before,user);
        String query=keyword==null?"":keyword.strip().toLowerCase(Locale.ROOT);
        var items=new ArrayList<AuditCaseHistoryEntry>();UUID cursor=before;
        // Bounded metadata scanning also works with old snapshots, without a
        // new migration or loading document bodies/facts into the history API.
        for(int scanned=0;scanned<400;) {
            var page=repository.history(user.userId(),cursor,Math.min(50,400-scanned));
            if(page.isEmpty())return new HistoryPage(List.copyOf(items),null);
            for(var entry:page) {
                cursor=entry.id();scanned++;
                if((from==null||!entry.createdAt().isBefore(from))&&(to==null||entry.createdAt().isBefore(to))
                        &&(query.isEmpty()||Objects.toString(entry.projectName(),"").toLowerCase(Locale.ROOT).contains(query)
                            ||entry.fileNames().stream().anyMatch(n->n.toLowerCase(Locale.ROOT).contains(query))))items.add(entry);
                if(items.size()==size)return new HistoryPage(List.copyOf(items),cursor);
            }
            if(page.size()<50)return new HistoryPage(List.copyOf(items),null);
        }
        return new HistoryPage(List.copyOf(items),cursor);
    }
    public Optional<AuditCase> operation(UUID key,CurrentUser user){
        UUID id=UUID.nameUUIDFromBytes(("auto-case/"+user.userId()+"/"+key).getBytes(StandardCharsets.UTF_8));
        return repository.find(id).filter(c->c.owner().equals(user.userId()));
    }
    public void rejectOwner(AuditCase value){
        var report=new Report("FAILED",null,null,List.of(),null,
                List.of("创建用户已失效或不具备审查权限，任务停止；文件原有处理状态和证据保留"),
                Map.of("QUOTE","FAILED","AGENT_PRECHECK","NOT_EXECUTED","BLACKLIST","NOT_EXECUTED",
                        "TENDER_COMPLIANCE","NOT_EXECUTED","COLLUSION","NOT_EXECUTED","SPLIT_PURCHASE","NOT_EXECUTED"));
        tx.save(value,value.update("FAILED",value.documents(),report),null);
    }
    public StoredFileContent open(UUID caseId,UUID documentId,CurrentUser user) {
        var file=owned(caseId,user).documents().stream().filter(d->d.id().equals(documentId)).findFirst().orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));
        return storage.open(new StorageKey(file.storageKey()));
    }
    /** Invoked only after the worker revalidates the persisted owner and roles. No web AuthContext required. */
    public void process(UUID id,CurrentUser user) {
        AuditCase previous=owned(id,user);Instant now=clock.instant();
        if(!previous.status().equals("QUEUED")&&!(Set.of("RUNNING","UPLOADING").contains(previous.status())&&!previous.leaseUntil().isAfter(now)))return;
        if(previous.attempts()>=3){tx.save(previous,previous.update("UNKNOWN",previous.documents().stream().map(d->d.status().equals("EXTRACTED")?d:d.state("UNKNOWN","RECOVERY_LIMIT")).toList(),previous.report()),user);return;}
        var running=previous.claim(UUID.randomUUID().toString(),now.plusSeconds(3600));
        if(!tx.save(previous,running,user))return;
        var docs=new ArrayList<>(running.documents());
        for(int i=0;i<docs.size();i++) {
            if(Thread.currentThread().isInterrupted())return;
            var doc=docs.get(i);
            if(Set.of("EXTRACTED","FAILED","UNKNOWN").contains(doc.status()))continue;
            // An interrupted native OCR/model call has an uncertain outcome. Never silently issue a new call.
            if(doc.status().equals("CALLING")||doc.status().equals("PARSING")&&doc.readCheckpoint()==null) {
                docs.set(i,doc.state("UNKNOWN","EXECUTOR_LOST_EXTERNAL_OUTCOME_UNKNOWN"));
                running=checkpoint(running,docs,user);continue;
            }
            String stage="DOCUMENT_READ";
            try {
                if(doc.status().equals("STAGING")) {
                    var state=storage.inspect(new StorageKey(doc.storageKey()));
                    if(state.status()!=StoredFileInspection.Status.PRESENT||!state.file().sha256().equals(doc.sha256())||state.file().size()!=doc.size()) {
                        docs.set(i,doc.state("UNKNOWN","UPLOAD_INTERRUPTED_STORAGE_UNPROVEN"));running=checkpoint(running,docs,user);continue;
                    }
                    doc=doc.state("STORED",null);
                }
                if(doc.status().equals("STORED")||doc.status().equals("PARSING")&&doc.readCheckpoint()!=null) {
                    docs.set(i,doc.state("PARSING",null));running=checkpoint(running,docs,user);
                    var stored=storage.open(new StorageKey(doc.storageKey()));
                    DocumentTextPort.Result text;
                    final int documentIndex=i;
                    var readingCase=new java.util.concurrent.atomic.AtomicReference<>(running);
                    try(var in=stored.inputStream()) {
                        text=readDocument(in,doc,progress->{
                            docs.set(documentIndex,docs.get(documentIndex).reading(progress));
                            readingCase.set(checkpoint(readingCase.get(),docs,user));
                        });
                    } finally {running=readingCase.get();doc=docs.get(i);}
                    if(!doc.sha256().equals(text.sha256()))throw new IOException("stored source digest mismatch");
                    doc=doc.parsed(text.segments(),text.method());docs.set(i,doc);running=checkpoint(running,docs,user);
                }
                stage="FIELD_EXTRACTION";
                if(CaseMaterialWindows.required(doc.segments())) {
                    var windows=CaseMaterialWindows.split(doc.segments());
                    if(doc.calls()>0&&(doc.parseMethod()==null||!doc.parseMethod().contains("FIELD_WINDOWS_V1")))
                        throw new IllegalStateException("WINDOW_POLICY_UNPROVEN");
                    for(int window=doc.calls();window<windows.size();window++) {
                        docs.set(i,doc.state("CALLING",null));running=checkpoint(running,docs,user);
                        var result=ai.extract("PROCUREMENT_AUTO",CaseMaterialWindows.identity(doc.id(),window),doc.sha256(),windows.get(window));
                        var merged=new LinkedHashSet<>(doc.facts());
                        for(var fact:CaseFactPolicy.validate(doc,result))merged.add(new Fact("window"+window+":"+fact.row(),fact.type(),fact.value(),fact.dataType(),
                                fact.locator(),fact.excerpt(),fact.verification(),fact.model(),fact.extractionVersion()));
                        doc=doc.extractedWindow(List.copyOf(merged),window+1);docs.set(i,doc);running=checkpoint(running,docs,user);
                        if(Thread.currentThread().isInterrupted())return;
                    }
                    doc=doc.extracted(doc.facts(),windows.size());docs.set(i,doc);running=checkpoint(running,docs,user);
                } else
                for(int attempt=doc.calls();attempt<2;attempt++) {
                    if(attempt>0&&!CaseFactPolicy.needsSupplement(doc))break;
                    docs.set(i,doc.state("CALLING",null));running=checkpoint(running,docs,user);
                    String extractionId=attempt==0?doc.id().toString():UUID.nameUUIDFromBytes((doc.id()+"/missing-fields-v1").getBytes(StandardCharsets.UTF_8)).toString();
                    var result=ai.extract("PROCUREMENT_AUTO",extractionId,doc.sha256(),doc.segments());
                    var merged=new LinkedHashSet<>(doc.facts());merged.addAll(CaseFactPolicy.validate(doc,result));
                    doc=doc.extracted(List.copyOf(merged),attempt+1);docs.set(i,doc);running=checkpoint(running,docs,user);
                }
                try(var span=PipelineDiagnostics.start("PROCUREMENT_AUTO","DOCUMENT_COMPLETE",doc.id(),Map.of("outputChars",doc.segments().stream().mapToInt(x->x.text().length()).sum(),"factCount",doc.facts().size(),"callCount",doc.calls()))) {
                    span.outcome(doc.parseMethod()!=null&&doc.parseMethod().contains("PARTIAL")?"PARTIAL":"COMPLETED",Map.of());
                }
            } catch(DocumentTextPort.ReadFailure failure) {
                logFailure(doc.id(),stage,failure);
                docs.set(i,doc.state("FAILED",failure.code()));running=checkpoint(running,docs,user);
            } catch(IOException failure) {
                logFailure(doc.id(),stage,failure);
                docs.set(i,doc.state("FAILED","SOURCE_READ_FAILED"));running=checkpoint(running,docs,user);
            } catch(RuntimeException failure) {
                logFailure(doc.id(),stage,failure);
                // Includes model timeout/invalid JSON/uncertain response. Never publish invented defaults.
                var persisted=repository.find(id).orElseThrow();
                if(failure instanceof org.springframework.dao.DataAccessException || failure instanceof CheckpointLost
                        ||persisted.version()!=running.version()||!Objects.equals(persisted.leaseToken(),running.leaseToken()))throw failure;
                docs.set(i,doc.state("UNKNOWN",stage.equals("DOCUMENT_READ")?"READ_OUTCOME_UNPROVEN":"EXTRACTION_OUTCOME_UNPROVEN"));running=checkpoint(running,docs,user);
            }
        }
        var report=CaseFactPolicy.report(id,docs);
        if(running.report()!=null)for(var entry:running.report().reviews().entrySet())report=report.review(entry.getKey(),entry.getValue());
        if(blacklist!=null && !report.reviews().containsKey("BLACKLIST")) {
            try {running=tx.blacklist(running,docs,report,blacklist,user,clock.instant());report=running.report();}
            catch(org.springframework.dao.ConcurrencyFailureException lost){throw lost;}
            catch(RuntimeException unavailable) {
                // A failed database check is not proof of no blacklist hit. Other checks can still finish.
                report=report.review("BLACKLIST",new ModuleReview("UNKNOWN",hash("blacklist/"+id),"case-blacklist-v1",null,null,clock.instant(),
                        List.of(),List.of("BLACKLIST_CHECK_UNAVAILABLE"),List.of(),List.of(),0,"BLACKLIST_CHECK_UNAVAILABLE"));
                running=checkpointReport(running,docs,report,user);
            }
        }
        if(reviews!=null) {
            // Preserve durable module outcomes after restart; completed/uncertain calls are not repeated.
            if(running.report()!=null)for(var entry:running.report().reviews().entrySet())report=report.review(entry.getKey(),entry.getValue());
            for(String module:CaseReviewContext.MODULES)if(!report.reviews().containsKey(module)) {
                try {var request=reviews.prepare(id,module,docs);report=report.review(module,reviews.pending(request,docs));}
                catch(RuntimeException invalid) {
                    var ctx=CaseReviewContext.select(module,docs);
                    report=report.review(module,new ModuleReview("FAILED",hash(module+ctx.scope()),CaseReviewContext.VERSION,null,null,clock.instant(),ctx.scope(),ctx.gaps(),List.of(),List.of(),0,"MODULE_INPUT_PREPARATION_FAILED"));
                }
            }
            running=checkpointReport(running,docs,report,user);
            for(String module:CaseReviewContext.MODULES) {
                var review=report.reviews().get(module);
                if(review.status().equals("RUNNING")) {
                    report=report.review(module,reviews.unknown(review,"EXECUTOR_LOST_MODEL_OUTCOME_UNKNOWN"));
                    running=checkpointReport(running,docs,report,user);continue;
                }
                if(!review.status().equals("PENDING"))continue;
                CaseReviewAiPort.Request request;
                try {request=reviews.prepare(id,module,docs);}
                catch(RuntimeException invalid){report=report.review(module,reviews.unknown(review,"MODULE_INPUT_UNPROVEN"));running=checkpointReport(running,docs,report,user);continue;}
                if(!request.inputDigest().equals(review.inputDigest())) {
                    report=report.review(module,reviews.unknown(review,"MODULE_INPUT_CHANGED"));running=checkpointReport(running,docs,report,user);continue;
                }
                var active=reviews.running(review);report=report.review(module,active);
                running=checkpointReport(running,docs,report,user); // Commit call claim before external work.
                report=report.review(module,reviews.execute(request,active));
                running=checkpointReport(running,docs,report,user);
                if(Thread.currentThread().isInterrupted())return;
            }
        }
        String status=report.quoteStatus();
        var finalReport=report;
        if(status.equals("COMPLETED")&&report.reviews().containsKey("BLACKLIST")
                &&!"COMPLETED".equals(report.reviews().get("BLACKLIST").status()))status="PARTIAL";
        if(reviews!=null&&status.equals("COMPLETED")&&(!report.reviews().keySet().containsAll(CaseReviewContext.MODULES)
                ||CaseReviewContext.MODULES.stream().anyMatch(m->!"COMPLETED".equals(finalReport.modules().get(m)))))status="PARTIAL";
        save(running,running.update(status,docs,report),user);
    }
    private AuditCase checkpoint(AuditCase value,List<Document> docs,CurrentUser user) {
        var next=new AuditCase(value.id(),value.owner(),value.operationKey(),value.fingerprint(),"RUNNING",value.version()+1,
                value.leaseToken(),clock.instant().plusSeconds(3600),value.attempts(),value.createdAt(),List.copyOf(docs),value.report());
        save(value,next,user);return next;
    }
    private AuditCase checkpointReport(AuditCase value,List<Document> docs,Report report,CurrentUser user) {
        var next=new AuditCase(value.id(),value.owner(),value.operationKey(),value.fingerprint(),"RUNNING",value.version()+1,
                value.leaseToken(),clock.instant().plusSeconds(1800),value.attempts(),value.createdAt(),List.copyOf(docs),report);
        save(value,next,user);return next;
    }
    private static class CheckpointLost extends IllegalStateException {CheckpointLost(){super("auto-case lease/version lost");}}
    private void save(AuditCase previous,AuditCase next,CurrentUser user){if(!tx.save(previous,next,user))throw new CheckpointLost();}
    private DocumentTextPort.Result readDocument(InputStream input,Document document,
            java.util.function.Consumer<DocumentTextPort.PdfCheckpoint> progress) throws IOException {
        boolean resumable=document.name().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
        if(parser == null) return resumable
            ?reader.read(input,document.name(),document.id().toString(),limits.maxBytes(),limits.maxPages(),limits.maxChars(),document.readCheckpoint(),progress)
            :reader.read(input,document.name(),document.id().toString(),limits.maxBytes(),limits.maxPages(),limits.maxChars());
        var request=new MaterialParsePort.ParseRequest(document.id().toString(),document.name(),null,input,
                limits.maxBytes(),limits.maxPages(),limits.maxChars(),Map.of("consumer","PROCUREMENT_AUTO"));
        var parsed=resumable?parser.parse(request,document.readCheckpoint(),progress):parser.parse(request);
        if(parsed.parseStatus()==MaterialParsePort.ParseStatus.UNKNOWN || parsed.parseStatus()==MaterialParsePort.ParseStatus.CONFLICT)
            throw new IllegalStateException("PARSE_OUTCOME_UNPROVEN");
        if(parsed.parseStatus()!=MaterialParsePort.ParseStatus.PARSED || parsed.canonicalText().isBlank())
            throw new DocumentTextPort.ReadFailure(parsed.errorCode()==null?"DOCUMENT_PARSE_FAILED":parsed.errorCode());
        return parsed.textResult();
    }
    private static BusinessException invalid(String text){return new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,text);}
    static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void logFailure(UUID documentId,String stage,Throwable failure) {
        try(var span=PipelineDiagnostics.start("PROCUREMENT_AUTO",stage,documentId,Map.of())){span.fail(failure);}
    }

}
