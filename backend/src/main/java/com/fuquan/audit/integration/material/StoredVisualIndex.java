package com.fuquan.audit.integration.material;
import com.fuquan.audit.common.material.visual.*;
import com.fuquan.audit.contract.application.port.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Recoverable derived artifact using the existing immutable FileStoragePort. No public object-key access. */
@Component
public class StoredVisualIndex implements VisualIndexStore {
    private static final int LIMIT=16*1024*1024;
    private final FileStoragePort files;private final ObjectMapper json;
    private record Cached(Optional<VisualIndex> value,long expires,long bytes){}
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(16,.75f,true);
    private long cacheBytes;
    public StoredVisualIndex(FileStoragePort files,ObjectMapper json){this.files=files;this.json=json;}
    private StorageKey key(String id,String sha,String text){return new StorageKey(MaterialVisualEvidence.digest("visual-index-v1\n"+id+"\n"+sha+"\n"+text));}
    public void save(VisualIndex index){
        if(index.anchors().isEmpty())return;
        StagedFile staged=null;
        try {
            byte[] data=json.writeValueAsBytes(index);if(data.length>LIMIT)return;
            staged=files.stage(new ByteArrayInputStream(data),LIMIT);files.promote(staged,key(index.documentId(),index.fileSha256(),index.textSha256()));
        }catch(IOException|RuntimeException failure){
            // Preview metadata must not discard successfully parsed business content.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("VISUAL_INDEX_WRITE outcome=UNAVAILABLE reason=ARTIFACT_NOT_PUBLISHED");
        }finally{
            synchronized(cache){var removed=cache.remove(key(index.documentId(),index.fileSha256(),index.textSha256()).value());if(removed!=null)cacheBytes-=removed.bytes();}
            if(staged!=null)try{files.discard(staged);}catch(RuntimeException ignored){}
        }
    }
    public Optional<VisualIndex> find(String id,String sha,String text){
        if(id==null||id.length()>200||!VisualIndex.hash(sha)||!VisualIndex.hash(text))return Optional.empty();
        var cacheKey=key(id,sha,text).value();
        synchronized(cache){var hit=cache.get(cacheKey);if(hit!=null&&System.nanoTime()<hit.expires())return hit.value();}
        try {
            var key=key(id,sha,text);var inspection=files.inspect(key);
            if(inspection.status()!=StoredFileInspection.Status.PRESENT||inspection.file().size()>LIMIT)return remember(cacheKey,Optional.empty(),0);
            var content=files.open(key);byte[] data;
            try(var in=content.inputStream()){data=in.readNBytes(LIMIT+1);}
            if(data.length>LIMIT||data.length!=inspection.file().size()||!MaterialVisualEvidence.digest(data).equals(inspection.file().sha256()))return Optional.empty();
            var index=json.readValue(data,VisualIndex.class);
            return id.equals(index.documentId())&&sha.equals(index.fileSha256())&&text.equals(index.textSha256())?remember(cacheKey,Optional.of(index),Math.max(data.length,index.anchors().size()*512L)):Optional.empty();
        }catch(IOException|RuntimeException failure){return Optional.empty();}
    }
    private Optional<VisualIndex> remember(String key,Optional<VisualIndex> value,long bytes){
        if(bytes>32L*1024*1024)return value;
        synchronized(cache){
            var prior=cache.put(key,new Cached(value,System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(value.isPresent()?120:5),bytes));
            cacheBytes+=bytes-(prior==null?0:prior.bytes());
            while(cache.size()>64||cacheBytes>32L*1024*1024){var it=cache.entrySet().iterator();var oldest=it.next();cacheBytes-=oldest.getValue().bytes();it.remove();}
        }return value;
    }
}
