package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fuquan.audit.integration.dm8.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Fakes validate repository invariants; they do not prove real DM8 dialect/DDL behavior. */
class Dm8AuditCaseRepositoryTest {
    @Test void legacyFactDigestReopensAndMetadataTamperingStillFailsClosed()throws Exception{
        var mapper=mock(AutoCaseMapper.class);var json=JsonMapper.builder().findAndAddModules().build();var repo=new Dm8AuditCaseRepository(mapper,json);
        String legacy="{\"row\":\"r\",\"type\":\"budgetAmount\",\"value\":\"100元\",\"dataType\":\"MONEY\",\"locator\":\"p1\",\"excerpt\":\"预算100元\",\"verification\":\"SOURCE_VERIFIED\",\"model\":\"TEST\",\"extractionVersion\":\"v3\"}";
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("["+legacy+"]").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var doc=new AuditCase.Document(UUID.randomUUID(),"预算.pdf",1,"a".repeat(64),"key","EXTRACTED",List.of(),List.of(),"PDF",null,1);
        var value=value().update("COMPLETED",List.of(doc),null);
        when(mapper.find(value.id().toString())).thenReturn(json.writeValueAsString(value));
        when(mapper.files(value.id().toString())).thenReturn(List.of(json.writeValueAsString(new Dm8AuditCaseRepository.StoredDocument(doc,1,digest))));
        when(mapper.facts(value.id().toString())).thenReturn(List.of(new AutoCaseMapper.StoredFact(doc.id().toString(),0,legacy)));
        assertThat(repo.find(value.id()).orElseThrow().documents().get(0).facts()).singleElement().extracting(AuditCase.Fact::value).isEqualTo("100元");
        String changed=legacy.substring(0,legacy.length()-1)+",\"subjectScope\":\"REFERENCE\"}";
        when(mapper.facts(value.id().toString())).thenReturn(List.of(new AutoCaseMapper.StoredFact(doc.id().toString(),0,changed)));
        assertThatThrownBy(()->repo.find(value.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("manifest corrupt");
    }
    @Test void pdfCheckpointIsStoredInFileClobAndReopenedWithoutAnyNewSchema() throws Exception {
        var mapper=mock(AutoCaseMapper.class);var json=JsonMapper.builder().findAndAddModules().build();var repo=new Dm8AuditCaseRepository(mapper,json);
        var cp=new com.fuquan.audit.common.material.application.port.DocumentTextPort.PdfCheckpoint("a".repeat(64),"office-pdf-pages-v1",162,List.of(1),
            List.of(new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment("pdf/page:1/ocr/part:1","第一页扫描文字")),List.of());
        var doc=new AuditCase.Document(UUID.randomUUID(),"扫描.pdf",200,"a".repeat(64),"b".repeat(64),"PARSING",List.of(),List.of(),null,null,0,cp);
        var c=value().update("RUNNING",List.of(doc),null);
        var casePayload=new java.util.concurrent.atomic.AtomicReference<String>();var filePayload=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call->{casePayload.set(((AutoCaseMapper.Row)call.getArgument(0)).payload());return 1;}).when(mapper).insert(any());
        doAnswer(call->{filePayload.set(call.getArgument(6));return 1;}).when(mapper).insertFile(anyString(),anyString(),anyInt(),anyString(),anyString(),anyString(),anyString());
        repo.insert(c);
        when(mapper.find(c.id().toString())).thenAnswer(call->casePayload.get());when(mapper.files(c.id().toString())).thenAnswer(call->List.of(filePayload.get()));
        when(mapper.facts(c.id().toString())).thenReturn(List.of());
        assertThat(repo.find(c.id()).orElseThrow().documents()).containsExactly(doc);
        var old=json.valueToTree(doc);((com.fasterxml.jackson.databind.node.ObjectNode)old).remove("readCheckpoint");
        assertThat(json.treeToValue(old,AuditCase.Document.class).readCheckpoint()).isNull();
    }
    private AuditCase value(){return new AuditCase(UUID.randomUUID(),"owner",UUID.randomUUID(),"a".repeat(64),"QUEUED",1,"token",Instant.now().plusSeconds(1800),0,Instant.now(),List.of(),null);}
    @Test void lostVersionNeverDeletesFileFactsOrLines(){
        var mapper=mock(AutoCaseMapper.class);var repo=new Dm8AuditCaseRepository(mapper,JsonMapper.builder().findAndAddModules().build());
        var old=value();assertThat(repo.replace(old,old.update("RUNNING",List.of(),null))).isFalse();
        verify(mapper,never()).deleteFiles(anyString());verify(mapper,never()).deleteFacts(anyString());verify(mapper,never()).deleteLines(anyString());
    }
    @Test void concurrentSnapshotReadFailsClosedInsteadOfMixingTwoVersions()throws Exception{
        var mapper=mock(AutoCaseMapper.class);var json=JsonMapper.builder().findAndAddModules().build();var repo=new Dm8AuditCaseRepository(mapper,json);
        var old=value();var counter=new java.util.concurrent.atomic.AtomicInteger();
        when(mapper.find(old.id().toString())).thenAnswer(call->json.writeValueAsString(new AuditCase(old.id(),old.owner(),old.operationKey(),old.fingerprint(),old.status(),counter.incrementAndGet(),old.leaseToken(),old.leaseUntil(),old.attempts(),old.createdAt(),List.of(),null)));
        when(mapper.files(anyString())).thenReturn(List.of());
        assertThatThrownBy(()->repo.find(old.id())).isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
    }
    @Test void versionChangeRetriesBeforeDiagnosingMixedFileManifest()throws Exception {
        var mapper=mock(AutoCaseMapper.class);var json=JsonMapper.builder().findAndAddModules().build();var repo=new Dm8AuditCaseRepository(mapper,json);
        var old=value();var next=old.update("RUNNING",List.of(),null);
        when(mapper.find(old.id().toString())).thenReturn(json.writeValueAsString(old),json.writeValueAsString(next),json.writeValueAsString(next));
        when(mapper.files(anyString())).thenReturn(List.of());when(mapper.facts(anyString())).thenReturn(List.of());
        assertThat(repo.find(old.id())).contains(next);
        verify(mapper,times(4)).find(old.id().toString());
    }
    @Test void missingDurableFileManifestIsCorruptionNotSuccessfulEmptyCase()throws Exception{
        var mapper=mock(AutoCaseMapper.class);var json=JsonMapper.builder().findAndAddModules().build();var repo=new Dm8AuditCaseRepository(mapper,json);
        var old=value();var doc=new AuditCase.Document(UUID.randomUUID(),"x.pdf",1,"a".repeat(64),"b".repeat(64),"STORED",List.of(),List.of(),null,null,0);
        var withFile=old.update("QUEUED",List.of(doc),null);
        when(mapper.find(old.id().toString())).thenReturn(json.writeValueAsString(withFile));when(mapper.files(anyString())).thenReturn(List.of());
        assertThatThrownBy(()->repo.find(old.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("manifest corrupt");
    }
}
