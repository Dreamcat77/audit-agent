package com.fuquan.audit.common.material.transfer;

import static com.fuquan.audit.common.material.transfer.MaterialTransfer.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class MaterialTransferService {
    private final MaterialTransferRepository repo;private final MaterialTransferTransactions tx;
    private final FileStoragePort storage;private final ObjectMapper json;
    private final Semaphore partsCapacity=new Semaphore(4),mergeCapacity=new Semaphore(2);
    private final ScheduledExecutorService heartbeat=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"material-transfer-lease");t.setDaemon(true);return t;});
    @jakarta.annotation.PreDestroy public void close(){heartbeat.shutdownNow();}
    public MaterialTransferService(MaterialTransferRepository repo,MaterialTransferTransactions tx,FileStoragePort storage,ObjectMapper json){this.repo=repo;this.tx=tx;this.storage=storage;this.json=json;}
    public Session create(String kind,UUID operation,Spec spec,CurrentUser user) {
        authorize(kind,user);validate(kind,spec);
        UUID id=id(kind,operation,user);var old=repo.find(id);
        if(old.isEmpty())try{tx.create(new Session(id,user.userId(),kind,operation,spec,"OPEN",null,null,null,Instant.now()),user);}catch(org.springframework.dao.DuplicateKeyException race){/* committed winner is read below */}
        var saved=owned(kind,operation,user);
        if(!saved.spec().equals(spec))throw conflict("原上传文件或所属批次已确定，不能更换；请选择原文件续传");
        return saved;
    }
    public Session owned(String kind,UUID operation,CurrentUser user){authorize(kind,user);return repo.find(id(kind,operation,user)).filter(s->s.actor().equals(user.userId())&&s.kind().equals(kind)).orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND,"未找到原上传记录"));}
    public Progress progress(Session s) {
        var received=new ArrayList<Integer>();long bytes=0;
        if(!s.status().equals("COMPLETED"))for(var p:repo.parts(s.id())) {
            var state=storage.inspect(new StorageKey(p.storageKey()));
            if(state.status()==StoredFileInspection.Status.UNKNOWN)throw conflict("分片保存状态暂时无法确认，请稍后继续原上传");
            if(state.status()==StoredFileInspection.Status.PRESENT) {
                if(state.file().size()!=p.size()||!state.file().sha256().equals(p.sha256()))throw conflict("已保存分片校验不一致，请联系管理员");
                received.add(p.index());bytes+=p.size();
            }
        }
        return new Progress(s.operation(),s.status(),CHUNK_BYTES,List.copyOf(received),s.status().equals("COMPLETED")?s.spec().size():bytes);
    }
    public Progress part(String kind,UUID operation,int index,InputStream input,CurrentUser user) {
        var s=owned(kind,operation,user);
        if(index<0||index>=s.spec().partHashes().size())throw invalid("分片编号无效");
        if(!s.status().equals("OPEN"))throw conflict("文件已在合并或已经完成，请核对原上传状态");
        if(!partsCapacity.tryAcquire())throw conflict("文件保存繁忙，请稍后继续原上传");
        StagedFile staged=null;
        try(var span=PipelineDiagnostics.start("MATERIAL","UPLOAD_PART",s.id(),Map.of("partIndex",index))) {
            long expected=Math.min(CHUNK_BYTES,s.spec().size()-(long)index*CHUNK_BYTES);
            staged=storage.stage(input,expected);
            if(staged.size()!=expected||!staged.sha256().equals(s.spec().partHashes().get(index)))throw conflict("文件分片内容与原文件不一致，请重新选择原文件");
            String key=sha("material-transfer-part:"+s.id()+":"+index+":"+staged.sha256());
            storage.promote(staged,new StorageKey(key));
            var p=new Part(index,expected,staged.sha256(),key);
            try{tx.part(s.id(),p);}catch(org.springframework.dao.DuplicateKeyException replay){if(!repo.parts(s.id()).contains(p))throw conflict("同一分片不能替换内容");}
            span.success(Map.of("inputBytes",expected));
            // Do not rehash all prior chunks for every acknowledgement.
            var saved=repo.parts(s.id());
            return new Progress(operation,"OPEN",CHUNK_BYTES,saved.stream().map(Part::index).toList(),saved.stream().mapToLong(Part::size).sum());
        }finally{try{if(staged!=null)storage.discard(staged);}finally{partsCapacity.release();}}
    }
    @FunctionalInterface public interface Delivery {Object accept(Session session,InputStream input)throws IOException;}
    public Object complete(String kind,UUID operation,CurrentUser user,Delivery delivery)throws IOException {
        var s=owned(kind,operation,user);
        if(s.status().equals("COMPLETED")){cleanupCompleted(s);return result(s);}
        var received=repo.parts(s.id());
        if(received.size()!=s.spec().partHashes().size())throw conflict("尚有分片未上传，请继续原文件上传");
        if(!mergeCapacity.tryAcquire())throw conflict("文件合并繁忙，请稍后继续原上传");
        String token=UUID.randomUUID().toString(),whole=sha("material-transfer-whole:"+s.id()+":"+s.spec().sha256());
        boolean claimed=false;StagedFile staged=null;ScheduledFuture<?> renewal=null;var leaseKnown=new AtomicBoolean(true);
        try(var span=PipelineDiagnostics.start("MATERIAL","UPLOAD_COMPLETE",s.id(),Map.of("inputBytes",s.spec().size(),"partCount",received.size()))) {
            Instant now=Instant.now();claimed=tx.claim(s.id(),token,now,now.plusSeconds(120));
            if(!claimed){var current=owned(kind,operation,user);if(current.status().equals("COMPLETED"))return result(current);throw conflict("文件正在合并，请稍后核对原上传结果");}
            renewal=heartbeat.scheduleAtFixedRate(()->{try{if(!tx.renew(s.id(),token,Instant.now().plusSeconds(120)))leaseKnown.set(false);}catch(RuntimeException unknown){leaseKnown.set(false);}},30,30,TimeUnit.SECONDS);
            // Open lazily so only one part stream is live, even for a 500 MB file.
            Enumeration<InputStream> streams=new Enumeration<>() {private int index;
                public boolean hasMoreElements(){return index<received.size();}
                public InputStream nextElement(){var p=received.get(index);if(p.index()!=index||!p.sha256().equals(s.spec().partHashes().get(index++)))throw conflict("分片清单不完整或不一致");return storage.open(new StorageKey(p.storageKey())).inputStream();}};
            try(var combined=new SequenceInputStream(streams)){staged=storage.stage(combined,s.spec().size());}
            if(staged.size()!=s.spec().size()||!staged.sha256().equals(s.spec().sha256()))throw conflict("完整文件校验失败，未创建解析任务");
            storage.promote(staged,new StorageKey(whole));
            if(!leaseKnown.get()||!tx.renew(s.id(),token,Instant.now().plusSeconds(120)))throw conflict("文件交付状态暂时无法确认，请保留原上传操作");
            Object delivered;
            try(var input=storage.open(new StorageKey(whole)).inputStream()){delivered=delivery.accept(s,input);}
            String encoded;
            try{encoded=json.writeValueAsString(delivered);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("transfer result serialization failed",e);}
            if(!tx.complete(s.id(),token,encoded,user))throw conflict("文件交付结果正在核对，请保留原上传操作");
            span.success(Map.of("inputBytes",s.spec().size()));
            // Only temporary transfer objects are removed, after the business result and completion commit.
            for(var p:received)cleanup(p.storageKey());cleanup(whole);
            return delivered;
        }finally{if(renewal!=null)renewal.cancel(false);try{if(staged!=null)storage.discard(staged);}finally{try{if(claimed)tx.release(s.id(),token);}finally{mergeCapacity.release();}}}
    }
    private Object result(Session s){try{return json.readTree(s.resultJson());}catch(IOException|IllegalArgumentException e){throw new IllegalStateException("invalid saved transfer result",e);}}
    private void cleanupCompleted(Session s){for(var p:repo.parts(s.id()))cleanup(p.storageKey());cleanup(sha("material-transfer-whole:"+s.id()+":"+s.spec().sha256()));}
    private void cleanup(String key){try{storage.delete(new StorageKey(key));}catch(RuntimeException failure){/* completion remains durable; safe orphan is retained for operator reconciliation */}}
    private static UUID id(String kind,UUID operation,CurrentUser user){if(operation==null)throw invalid("上传操作编号缺失");return UUID.nameUUIDFromBytes(("transfer-v1:"+user.userId()+":"+kind+":"+operation).getBytes(StandardCharsets.UTF_8));}
    private static void authorize(String kind,CurrentUser user){Set<String> roles=switch(kind){case "budget"->Set.of("SYSTEM_ADMIN","PROCUREMENT_MANAGER","AUDITOR","FINANCE_REVIEWER");case "procurement","project"->Set.of("SYSTEM_ADMIN","PROCUREMENT_MANAGER","PROCUREMENT_APPLICANT","AUDITOR");default->throw invalid("材料类型无效");};if(user.roles().stream().noneMatch(roles::contains))throw new BusinessException(BusinessErrorCode.FORBIDDEN);}
    private static void validate(String kind,Spec s){
        if(s==null||s.fileName()==null||s.fileName().isBlank()||s.fileName().length()>255||s.fileName().matches(".*[\\\\/\\p{Cntrl}].*")||!s.fileName().toLowerCase(Locale.ROOT).matches(".*\\.(docx?|xlsx?|pdf|pptx?|txt|csv|rtf|png|jpe?g|bmp|tiff?)")||s.size()<1||s.size()>MAX_BYTES||s.sha256()==null||!s.sha256().matches("[a-f0-9]{64}")||s.partHashes()==null||s.partHashes().size()!=(s.size()+CHUNK_BYTES-1)/CHUNK_BYTES||s.partHashes().stream().anyMatch(p->p==null||!p.matches("[a-f0-9]{64}"))||s.contentType()!=null&&s.contentType().length()>128||s.budgetYear()!=null&&s.budgetYear().length()>16||s.departmentCode()!=null&&s.departmentCode().length()>64||kind.equals("project")!=(s.projectId()!=null)||!kind.equals("budget")&&(s.batchId()!=null||s.budgetYear()!=null||s.departmentCode()!=null))throw invalid("上传清单不完整或超出支持范围");
    }
    private static String sha(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static BusinessException invalid(String s){return new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,s);}
    private static BusinessException conflict(String s){return new BusinessException(BusinessErrorCode.CONFLICT,s);}
}
