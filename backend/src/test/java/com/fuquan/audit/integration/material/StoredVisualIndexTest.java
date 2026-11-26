package com.fuquan.audit.integration.material;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.material.visual.*;
import com.fuquan.audit.common.material.visual.VisualIndex.*;
import com.fuquan.audit.integration.file.FileSystemStorageAdapter;
import com.fuquan.audit.contract.application.port.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredVisualIndexTest {
    @TempDir Path root;
    FileSystemStorageAdapter storage(){return new FileSystemStorageAdapter(root,(a,b)->Files.createLink(b,a));}
    VisualIndex index(){return new VisualIndex("d","a".repeat(64),"b".repeat(64),List.of(new Anchor(1,"OCR",0,3,List.of(new Point(.1,.1),new Point(.8,.1),new Point(.8,.2),new Point(.1,.2)),.9,"UNCERTAIN")));}
    @Test void reopenAndConcurrentIdempotentPublicationKeepOneVerifiedArtifact()throws Exception {
        var store=new StoredVisualIndex(storage(),new ObjectMapper());var index=index();var threads=Executors.newFixedThreadPool(4);
        try{var futures=new ArrayList<Future<?>>();for(int i=0;i<8;i++)futures.add(threads.submit(()->store.save(index)));for(var f:futures)f.get(15,TimeUnit.SECONDS);}finally{threads.shutdownNow();}
        var reopened=new StoredVisualIndex(storage(),new ObjectMapper());
        assertThat(reopened.find("d",index.fileSha256(),index.textSha256())).contains(index);
        assertThat(reopened.find("other",index.fileSha256(),index.textSha256())).isEmpty();
        assertThat(reopened.find("d",index.fileSha256(),"c".repeat(64))).isEmpty();
        try(var files=Files.walk(root)){assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);}
    }
    @Test void unknownStorageOrTamperedContentNeverReturnsCoordinates()throws Exception {
        var files=mock(FileStoragePort.class);when(files.inspect(any())).thenReturn(StoredFileInspection.unknown());
        var store=new StoredVisualIndex(files,new ObjectMapper());assertThat(store.find("d",index().fileSha256(),index().textSha256())).isEmpty();verify(files,never()).open(any());
        var real=storage();var saved=new StoredVisualIndex(real,new ObjectMapper());saved.save(index());
        Path artifact;try(var stream=Files.walk(root)){artifact=stream.filter(Files::isRegularFile).findFirst().orElseThrow();}
        Files.writeString(artifact,"{broken-json");assertThat(saved.find("d",index().fileSha256(),index().textSha256())).isEmpty();
    }
}
