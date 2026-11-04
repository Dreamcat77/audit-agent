package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CaseLongMaterialTest {
    static class Crash extends Error {}
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-30T00:00:00Z");
        public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}
    }
    final String sha="a".repeat(64);
    final CurrentUser user=new CurrentUser("long-test","long-test","测试",Set.of("AUDITOR"));
    final InMemoryAuditCaseRepository repo=new InMemoryAuditCaseRepository();
    final AuditCaseTransactions tx=mock(AuditCaseTransactions.class);
    final FileStoragePort storage=mock(FileStoragePort.class);
    final MutableClock clock=new MutableClock();
    final UUID id=UUID.randomUUID(),document=UUID.randomUUID();
    List<Segment> source() {
        var source=new ArrayList<Segment>();
        for(int i=1;i<=162;i++)source.add(new Segment("pdf/page:"+i+"/ocr/part:"+i,
                (i==162?"本项目的预算为2200000人民币。\n":"项目名称：测试服务采购\n")+"普通技术要求文字。".repeat(180)));
        return List.copyOf(source);
    }
    void create() {
        repo.insert(new AuditCase(id,user.userId(),UUID.randomUUID(),"fingerprint","QUEUED",0,null,clock.instant(),0,clock.instant(),
            List.of(new AuditCase.Document(document,"招标文件.pdf",200,sha,"b".repeat(64),"STORED",List.of(),List.of(),null,null,0)),null));
        when(storage.open(any())).thenAnswer(c->new StoredFileContent(new ByteArrayInputStream(new byte[]{1}),200));
        doAnswer(c->repo.replace(c.getArgument(0),c.getArgument(1))).when(tx).save(any(),any(),any());
    }
    MaterialFieldAiPort gateway(List<String> identities,List<Segment> visited) {
        return (kind,id,hash,segments)->{
            identities.add(id);visited.addAll(segments);
            var rows=new ArrayList<Row>();
            for(var s:segments)if(s.text().contains("预算为2200000"))rows.add(new Row("r",List.of(new Fact("budgetAmount","2200000人民币",s.locator(),"本项目的预算为2200000人民币。"))));
            return new Result(rows,"TEST","v1");
        };
    }
    AuditCaseService service(MaterialFieldAiPort ai) {
        return new AuditCaseService(repo,tx,storage,(input,name,resource,bytes,pages,chars)->
            new DocumentTextPort.Result(source(),"OFFICE_TEXT_OCR",sha),ai,clock);
    }
    @Test void longSourceIncludesTailAmountAndResumesAcknowledgedWindowsAfterExecutorCrash() {
        create();var identities=new ArrayList<String>();var visited=new ArrayList<Segment>();var ai=gateway(identities,visited);
        doAnswer(c->{
            AuditCase next=c.getArgument(1);boolean saved=repo.replace(c.getArgument(0),next);
            var doc=next.documents().get(0);
            if(saved&&doc.status().equals("PARSED")&&doc.calls()==2)throw new Crash();
            return saved;
        }).when(tx).save(any(),any(),any());
        assertThatThrownBy(()->service(ai).process(id,user)).isInstanceOf(Crash.class);
        assertThat(repo.find(id).orElseThrow().documents().get(0).calls()).isEqualTo(2);
        doAnswer(c->repo.replace(c.getArgument(0),c.getArgument(1))).when(tx).save(any(),any(),any());
        clock.now=clock.now.plusSeconds(3700);service(ai).process(id,user);
        var done=repo.find(id).orElseThrow();
        assertThat(done.documents().get(0).status()).isEqualTo("EXTRACTED");
        assertThat(done.report().budget()).isEqualByComparingTo("2200000");
        assertThat(CaseReviewContext.select("AGENT_PRECHECK",done.documents()).evidence())
            .anySatisfy(e->assertThat(e.excerpt()).contains("预算为2200000"));
        assertThat(visited).containsExactlyElementsOf(source());
        assertThat(identities).doesNotHaveDuplicates();
        assertThat(identities).containsExactlyElementsOf(java.util.stream.IntStream.range(0,CaseMaterialWindows.split(source()).size())
            .mapToObj(n->CaseMaterialWindows.identity(document,n)).toList());
    }
    @Test void uncertainLaterWindowKeepsKnownAmountsWithoutRetryOrInventingACompleteReport() {
        create();var calls=new int[1];
        MaterialFieldAiPort ai=(kind,operation,hash,segments)->{
            if(++calls[0]==2)throw new IllegalStateException("synthetic lost response");
            var s=segments.get(0);
            return new Result(List.of(new Row("r",List.of(new Fact("projectName","测试服务采购",s.locator(),"项目名称：测试服务采购")))),"TEST","v1");
        };
        service(ai).process(id,user);var done=repo.find(id).orElseThrow();
        assertThat(done.documents().get(0).facts()).isNotEmpty();
        assertThat(done.documents().get(0).status()).isEqualTo("UNKNOWN");
        assertThat(done.status()).isNotEqualTo("COMPLETED");
        service(ai).process(id,user);assertThat(calls[0]).isEqualTo(2);
    }
}
