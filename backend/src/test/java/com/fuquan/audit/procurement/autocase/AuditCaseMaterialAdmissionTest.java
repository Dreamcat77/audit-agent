package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuditCaseMaterialAdmissionTest {
    final InMemoryAuditCaseRepository repo=new InMemoryAuditCaseRepository();
    final AuditCaseTransactions tx=mock(AuditCaseTransactions.class);
    final FileStoragePort storage=mock(FileStoragePort.class);
    final DocumentTextPort reader=mock(DocumentTextPort.class);
    final MaterialFieldAiPort ai=mock(MaterialFieldAiPort.class);
    final CurrentUser user=new CurrentUser("admission-test","admission-test","测试",Set.of("AUDITOR"));
    final String sha="a".repeat(64);
    AuditCaseService service;
    @BeforeEach void setup(){
        service=new AuditCaseService(repo,tx,storage,reader,ai,Clock.systemUTC(),null);
        doAnswer(c->{repo.insert(c.getArgument(0));return null;}).when(tx).create(any(),any());
        when(tx.save(any(),any(),any())).thenAnswer(c->repo.replace(c.getArgument(0),c.getArgument(1)));
    }
    AuditCase upload(String name)throws IOException{return service.upload(UUID.randomUUID(),List.of(file(name)),user);}
    AuditCaseService.Upload file(String name){return new AuditCaseService.Upload(name,()->new ByteArrayInputStream(new byte[]{1}));}
    byte[] header(String extension){return switch(extension){
        case "pdf"->"%PDF-1.7".getBytes();
        case "doc","xls","ppt"->new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1};
        case "docx","xlsx","pptx"->new byte[]{80,75,3,4};
        case "rtf"->"{\\rtf1".getBytes();
        case "png"->new byte[]{(byte)137,80,78,71,13,10,26,10};
        case "jpg","jpeg"->new byte[]{(byte)255,(byte)216,(byte)255};
        case "bmp"->new byte[]{66,77};
        case "tif","tiff"->new byte[]{73,73,42,0};
        default->"synthetic text".getBytes();};}
    void staged(long size,byte[] header){when(storage.stage(any(),anyLong())).thenReturn(new StagedFile(UUID.randomUUID().toString(),size,sha,header));}

    @ParameterizedTest @ValueSource(strings={"pdf","doc","docx","xls","xlsx","ppt","pptx","txt","csv","rtf","png","jpg","jpeg","bmp","tif","tiff"})
    void commonFormatsAreAdmittedWithTheSharedLimit(String extension)throws Exception{
        staged(64,header(extension));var result=upload("材料."+extension);
        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(result.documents().get(0).sha256()).isEqualTo(sha);
        verify(storage).stage(any(),eq(524288000L));verifyNoInteractions(ai,reader);
    }
    @Test void largerThanOldTwentyMbIsReadWithExactlyTheAdmissionBudget()throws Exception{
        long size=26L*1024*1024;staged(size,header("docx"));var result=upload("较大.docx");
        when(storage.open(any())).thenReturn(new StoredFileContent(new ByteArrayInputStream(new byte[]{1}),size));
        when(reader.read(any(),anyString(),anyString(),anyLong(),anyInt(),anyInt()))
            .thenThrow(new DocumentTextPort.ReadFailure("TEXT_LIMIT_EXCEEDED"));
        service.process(result.id(),user);
        verify(reader).read(any(),eq("较大.docx"),anyString(),eq(524288000L),eq(512),eq(768000));
        var actual=repo.find(result.id()).orElseThrow();
        assertThat(actual.documents().get(0).status()).isEqualTo("FAILED");
        assertThat(actual.documents().get(0).errorCode()).isEqualTo("TEXT_LIMIT_EXCEEDED");
        assertThat(actual.status()).isNotEqualTo("COMPLETED");verifyNoInteractions(ai);
    }
    @ParameterizedTest @ValueSource(strings={"zip","exe","../材料.pdf"})
    void archivesAndUnsafeNamesCannotBypassAdmission(String extension){
        String name=extension.startsWith("../")?extension:"材料."+extension;
        assertThatThrownBy(()->upload(name)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage,ai,reader,tx);
    }
    @Test void forgedExtensionIsRejectedAndStagingCleaned(){
        staged(30,header("doc"));assertThatThrownBy(()->upload("伪装.pdf")).isInstanceOf(BusinessException.class);
        verify(storage).discard(any());verifyNoInteractions(tx,ai);
    }
    @Test void emptyOrOversizeFileIsAClientRejectionRatherThanServerError(){
        for(var reason:FileStorageUserRejectionException.Reason.values()){
            doThrow(new FileStorageUserRejectionException(reason,"safe")).when(storage).stage(any(),anyLong());
            assertThatThrownBy(()->upload("材料.pdf")).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(tx,ai);
    }
    @Test void configuredBatchLimitIsCheckedWithoutOverflowOrPromotingFiles(){
        service=new AuditCaseService(repo,tx,storage,reader,ai,Clock.systemUTC(),null,new AuditCaseMaterialLimits(100,10,1000));
        staged(60,header("pdf"));
        assertThatThrownBy(()->service.upload(UUID.randomUUID(),List.of(file("1.pdf"),file("2.pdf")),user)).isInstanceOf(BusinessException.class);
        verify(storage,times(2)).discard(any());verify(storage,never()).promote(any(),any());verifyNoInteractions(tx,ai);
    }
}
