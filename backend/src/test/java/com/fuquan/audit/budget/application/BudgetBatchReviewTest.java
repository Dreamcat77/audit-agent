package com.fuquan.audit.budget.application;

import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.budget.application.ai.BudgetBatchReviewPort;
import com.fuquan.audit.budget.application.ai.BudgetBatchReviewPort.*;
import com.fuquan.audit.common.material.application.port.MaterialExtractionLedger;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Row;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class BudgetBatchReviewTest {
    static Input text(String name,String text,Row... rows){return new Input(UUID.randomUUID(),name,"PARSED",new Snapshot(List.of(rows),List.of(),List.of(new Segment("pdf/page:1/text",text)),"a".repeat(64),"NATIVE"));}
    static Item issue(Source source){String quote=source.text().substring(0,source.text().indexOf('。')+1);return new Item("PURPOSE_SCOPE","WARNING","CLAUSE_ISSUE","设备经费被用于宴请","批准用途与申请用途不一致。","按批准用途调整支出。",List.of(source.id()),Map.of(source.id(),quote));}
    static Map<UUID,FileResult> baseline(List<Input> inputs){return new BudgetUploadedMaterialAnalyzer().analyzeBatch(inputs);}
    @Test void rawProseWithoutStructuredAmountsProducesLocatedSemanticFindingAndReusesDurableResult(){
        var input=text("情况说明.pdf","批准经费仅用于设备购置，但申请中将该项经费用于宴请。");
        var ledger=new Ledger();var ai=mock(BudgetBatchReviewPort.class);
        when(ai.analyze(any())).thenAnswer(inv->new Result(true,List.of(issue(((Request)inv.getArgument(0)).evidence().get(0)))));
        var service=new BudgetBatchReviewService(ledger,ai,new ObjectMapper());UUID id=UUID.randomUUID();
        var result=service.review(id,List.of(input),baseline(List.of(input))).get(input.taskId());
        assertThat(result.analysisCoverage()).isEqualTo("COMPLETE");
        assertThat(result.assessments()).singleElement().satisfies(a->{assertThat(a.title()).contains("宴请");
            assertThat(a.sources()).singleElement().satisfies(e->{assertThat(e.start()).isZero();assertThat(e.end()).isEqualTo(input.snapshot().segments().get(0).text().length());assertThat(e.pageNumber()).isEqualTo(1);});});
        assertThat(service.review(id,List.of(input),baseline(List.of(input))).get(input.taskId())).isEqualTo(result);
        verify(ai,times(1)).analyze(any());
    }
    @Test void foreignQuotesUnsupportedNumbersMissingFileClaimsAndNullCodesCannotBecomeWarnings(){
        var input=text("说明.pdf","设备经费限用于购置，不得用于宴请。");var ai=mock(BudgetBatchReviewPort.class);
        when(ai.analyze(any())).thenAnswer(inv->{var s=((Request)inv.getArgument(0)).evidence().get(0);return new Result(true,List.of(
            new Item(null,"WARNING","CLAUSE_ISSUE","错误","错误","",List.of(s.id()),Map.of(s.id(),s.text())),
            new Item("PURPOSE_SCOPE","WARNING","CLAUSE_ISSUE","超支20000元","超支20000元","调整",List.of(s.id()),Map.of(s.id(),s.text())),
            new Item("PURPOSE_SCOPE","WARNING","CLAUSE_ISSUE","未提供批复文件","未提供批复文件","上传",List.of(s.id()),Map.of(s.id(),s.text())),
            new Item("PURPOSE_SCOPE","WARNING","CLAUSE_ISSUE","引用错误","引用错误","调整",List.of(s.id()),Map.of(s.id(),"其他文件的句子"))));});
        var result=new BudgetBatchReviewService(new Ledger(),ai,new ObjectMapper()).review(UUID.randomUUID(),List.of(input),baseline(List.of(input))).get(input.taskId());
        assertThat(result.assessments()).isEmpty();assertThat(result.analysisCoverage()).isEqualTo("PARTIAL");
    }
    @Test void successfulUnitsSurviveLaterTimeoutAndUnknownIsNeverResent(){
        var input=text("长说明.pdf","批准经费仅用于购置，不得用于宴请。"+"用途说明。".repeat(1800));var ledger=new Ledger();var ai=mock(BudgetBatchReviewPort.class);
        when(ai.analyze(any())).thenAnswer(inv->new Result(true,List.of(issue(((Request)inv.getArgument(0)).evidence().get(0)))))
            .thenThrow(new IllegalStateException("SYNTHETIC_TIMEOUT"));
        var service=new BudgetBatchReviewService(ledger,ai,new ObjectMapper());UUID id=UUID.randomUUID();
        var result=service.review(id,List.of(input),baseline(List.of(input))).get(input.taskId());
        assertThat(ledger.entry.status()).isEqualTo("UNKNOWN");assertThat(ledger.entry.nextUnit()).isEqualTo(1);
        assertThat(result.assessments()).singleElement().satisfies(a->assertThat(a.title()).contains("宴请"));
        assertThat(result.analysisCoverage()).isEqualTo("PARTIAL");
        service.review(id,List.of(input),baseline(List.of(input)));verify(ai,times(2)).analyze(any());
    }
    @Test void failedAcknowledgementCannotPublishModelCandidate(){
        var input=text("说明.pdf","批准经费仅用于购置，但申请改用于宴请。");var ledger=new Ledger();ledger.rejectAck=true;var ai=mock(BudgetBatchReviewPort.class);
        when(ai.analyze(any())).thenAnswer(inv->new Result(true,List.of(issue(((Request)inv.getArgument(0)).evidence().get(0)))));
        var result=new BudgetBatchReviewService(ledger,ai,new ObjectMapper()).review(UUID.randomUUID(),List.of(input),baseline(List.of(input))).get(input.taskId());
        assertThat(result.assessments()).isEmpty();assertThat(ledger.entry.status()).isEqualTo("UNKNOWN");
    }
    @Test void noModelStillKeepsExactTotalsAndInclusiveMillionThreshold(){
        var row=new Row("r",List.of(new Fact("projectBudgetAmount","壹佰万元整","pdf/page:1/text","项目预算：壹佰万元整")));
        var input=text("请示.pdf","项目预算：壹佰万元整",row);
        var result=new BudgetBatchReviewService((MaterialExtractionLedger)null,(BudgetBatchReviewPort)null,new ObjectMapper()).review(UUID.randomUUID(),List.of(input),baseline(List.of(input))).get(input.taskId());
        assertThat(result.totals()).singleElement().satisfies(t->assertThat(t.amount()).isEqualByComparingTo("1000000"));
        assertThat(result.assessments()).anyMatch(a->a.code().equals("TOTAL_APPROVAL_THRESHOLD"));
        assertThat(result.analysisCoverage()).isEqualTo("PARTIAL");
    }
    @Test void sameBatchNamesMayDifferButYearsAndSubjectsDoNotMix(){
        var a=text("申报.pdf","",BudgetUploadedMaterialAnalyzerTest.row("projectName","北区终端升级","subjectName","设备","budgetYear","2026","declaredAmount","12000"));
        var b=text("批复.pdf","",BudgetUploadedMaterialAnalyzerTest.row("projectName","终端更新项目","subjectName","设备","budgetYear","2026","approvalAmount","10000"));
        var old=text("去年.pdf","",BudgetUploadedMaterialAnalyzerTest.row("subjectName","设备","budgetYear","2025","approvalAmount","99999"));
        var engine=new BudgetUploadedMaterialAnalyzer();
        assertThat(engine.analyzeBatch(List.of(a,b,old)).get(a.taskId()).lines()).singleElement().satisfies(l->{assertThat(l.status()).isEqualTo("OVER_APPROVAL");assertThat(l.approvalAmount()).isEqualByComparingTo("10000");});
        var other=text("其他科目.pdf","",BudgetUploadedMaterialAnalyzerTest.row("subjectName","培训","budgetYear","2026","approvalAmount","99999"));
        assertThat(engine.analyzeBatch(List.of(a,other)).get(a.taskId()).lines()).allSatisfy(l->assertThat(l.approvalAmount()).isNull());
    }
    @Test void totalRolesYearsAnnualAndPackageAmountsAreNotMixed(){
        var a=text("申报.pdf","",BudgetUploadedMaterialAnalyzerTest.row("budgetYear","2026","declaredTotalAmount","12000元"));
        var b=text("批复.pdf","",BudgetUploadedMaterialAnalyzerTest.row("budgetYear","2026","approvedTotalAmount","10000元"));
        var old=text("历史.pdf","",BudgetUploadedMaterialAnalyzerTest.row("budgetYear","2025","approvedTotalAmount","5000元"));
        var totals=BudgetBatchAmounts.totals(List.of(a,b,old));
        assertThat(BudgetBatchAmounts.checks(totals)).filteredOn(c->c.code().equals("TOTAL_VS_APPROVAL")).singleElement().satisfies(c->assertThat(c.explanation()).contains("2000"));
        var annual=text("年度.pdf","",new Row("r",List.of(new Fact("approvedTotalAmount","10000元","p","本项目每年批复总额10000元","CURRENT","ANNUAL"))));
        assertThat(BudgetBatchAmounts.checks(BudgetBatchAmounts.totals(List.of(a,annual)))).noneMatch(c->c.code().equals("TOTAL_VS_APPROVAL"));
        var cropped=text("年度批复.pdf","本年度批复总额：10000元，非三年项目总额。",new Row("r",List.of(new Fact("approvedTotalAmount","10000元","pdf/page:1/text","批复总额：10000元"))));
        assertThat(BudgetBatchAmounts.totals(List.of(cropped))).singleElement().satisfies(t->assertThat(t.scope()).isEqualTo("ANNUAL"));
        var prose=text("经费请示.txt","经费盘子按人民币壹佰贰拾万元控制",new Row("r",List.of(new Fact("projectBudgetAmount","壹佰贰拾万元","pdf/page:1/text","经费盘子按人民币壹佰贰拾万元控制","CURRENT_PROJECT","TOTAL"))));
        assertThat(BudgetBatchAmounts.checks(BudgetBatchAmounts.totals(List.of(prose)))).anyMatch(a1->a1.code().equals("TOTAL_APPROVAL_THRESHOLD"));
    }
    @Test void smallCompleteBatchUsesOneCrossFileCallInsteadOfRepeatingTheSameCorpus(){
        var a=text("申报.txt","项目申请设备经费。");var b=text("批复.txt","批准设备经费。");
        assertThat(BudgetBatchReviewService.plan(List.of(a,b))).singleElement().satisfies(u->{assertThat(u.scope()).isEqualTo("CROSS_FILE");assertThat(u.evidence()).hasSize(2);});
        var ai=mock(BudgetBatchReviewPort.class);when(ai.analyze(any())).thenReturn(new Result(true,List.of()));
        var result=new BudgetBatchReviewService(new Ledger(),ai,new ObjectMapper()).review(UUID.randomUUID(),List.of(a,b),baseline(List.of(a,b)));
        assertThat(result.values()).allSatisfy(r->assertThat(r.analysisCoverage()).isEqualTo("COMPLETE"));verify(ai,times(1)).analyze(any());
    }
    @Test void singleLongFileSynthesizesSeparateConditionsAndExecutionWithoutDeclaringMissingContext(){
        String filler="资产登记及实施记录按进度整理。\n".repeat(450);
        String approved="批复经费仅用于业务终端更新，不得列支员工礼盒。";
        String actual="本次将全部经费用于发放员工礼盒，不购置业务终端，未作用途调整。";
        var input=text("长说明.txt",filler+approved+"\n"+filler+actual);
        var units=BudgetBatchReviewService.plan(List.of(input));
        assertThat(units.stream().filter(u->u.scope().equals("FRAGMENT")).count()).isGreaterThan(1);
        assertThat(units.get(units.size()-1).scope()).isEqualTo("CROSS_FILE");
        assertThat(units.get(units.size()-1).evidence().stream().map(Source::text)).anyMatch(s->s.contains(approved)).anyMatch(s->s.contains(actual));
    }
    @Test void identityConflictNeedsBothOperandsAndOcrConfusablesCannotBecomeConfirmedFaults(){
        String first="项目编号：SYN-OFFICE-2026",second="项目编号：SYN-0FFICE-2026",third="项目编号：OTHER-2026";
        var ai=mock(BudgetBatchReviewPort.class);
        when(ai.analyze(any())).thenAnswer(inv->{var source=((Request)inv.getArgument(0)).evidence().get(0);return new Result(true,List.of(
                new Item("IDENTITY_CONFLICT","WARNING","CLAUSE_ISSUE","项目编号不一致","原文项目编号不一致。","核对编号。",List.of(source.id()),Map.of(source.id(),first))));});
        var input=text("原文.txt",first+"\n"+second);
        assertThat(new BudgetBatchReviewService(new Ledger(),ai,new ObjectMapper()).review(UUID.randomUUID(),List.of(input),baseline(List.of(input))).get(input.taskId()).assessments()).isEmpty();
        doAnswer(inv->{var source=((Request)inv.getArgument(0)).evidence().get(0);return new Result(true,List.of(
                new Item("IDENTITY_CONFLICT","WARNING","CLAUSE_ISSUE","项目编号不一致","两处项目编号不一致。","核对编号。",List.of(source.id()),Map.of(source.id(),source.text()))));}).when(ai).analyze(any());
        var scan=new Input(UUID.randomUUID(),"scan.png","PARSED",new Snapshot(List.of(),List.of(),List.of(new Segment("image/frame:1",first+"\n"+second)),null,"OCR"));
        assertThat(new BudgetBatchReviewService(new Ledger(),ai,new ObjectMapper()).review(UUID.randomUUID(),List.of(scan),baseline(List.of(scan))).get(scan.taskId()).assessments()).isEmpty();
        var genuine=text("原文.txt",first+"\n"+third);
        assertThat(new BudgetBatchReviewService(new Ledger(),ai,new ObjectMapper()).review(UUID.randomUUID(),List.of(genuine),baseline(List.of(genuine))).get(genuine.taskId()).assessments()).singleElement().satisfies(a->assertThat(a.code()).isEqualTo("SEMANTIC_IDENTITY_CONFLICT"));
        var genuineScan=new Input(UUID.randomUUID(),"scan.pdf","PARSED",new Snapshot(List.of(),List.of(),List.of(new Segment("pdf/page:1/ocr",first+"\n"+third)),null,"OCR"));
        assertThat(new BudgetBatchReviewService(new Ledger(),ai,new ObjectMapper()).review(UUID.randomUUID(),List.of(genuineScan),baseline(List.of(genuineScan))).get(genuineScan.taskId()).assessments()).singleElement().satisfies(a->assertThat(a.code()).isEqualTo("SEMANTIC_IDENTITY_CONFLICT"));
        assertThat(new BudgetBatchReviewService(new Ledger(),ai,new ObjectMapper()).review(UUID.randomUUID(),List.of(input),baseline(List.of(input))).get(input.taskId()).assessments()).singleElement().satisfies(a->assertThat(a.code()).isEqualTo("SEMANTIC_IDENTITY_CONFLICT"));
    }
    @Test void modelFailurePreservesExplicitAmountsAndFrozenText() throws Exception {
        var ai=mock(com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.class);when(ai.extract(any(),any(),any(),any())).thenThrow(new IllegalStateException("SYNTHETIC_MODEL_DOWN"));
        var reader=new BudgetUploadedMaterialReader(null,ai,null);var segments=List.of(new Segment("p1","项目预算：壹佰万元整\n资金用于设备更新，相关说明详见本次方案。"));
        var snapshot=reader.extract(new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(segments,"NATIVE","a".repeat(64)),"synthetic");
        assertThat(snapshot.segments()).isEqualTo(segments);assertThat(snapshot.issues()).anyMatch(i->i.reasonCode().equals("MODEL_EXTRACTION_INCOMPLETE"));
        assertThat(BudgetBatchAmounts.totals(List.of(new Input(UUID.randomUUID(),"请示.txt","PARSED",snapshot)))).singleElement().satisfies(t->assertThat(t.amount()).isEqualByComparingTo("1000000"));
    }
    @Test void exactSourceRangeDoesNotFallBackToAnotherPageOrRepeatedPhrase(){
        UUID id=UUID.randomUUID();var snapshot=new Snapshot(List.of(),List.of(),List.of(new Segment("pdf/page:1/text","同一句。\n同一句。"),new Segment("pdf/page:2/ocr","批复用途为设备购置。")),null,null);
        assertThat(BudgetSourceText.locate(new Evidence(id,"a.pdf","x","","pdf/page:1/text","同一句。"),snapshot).start()).isNull();
        var located=BudgetSourceText.locate(new Evidence(id,"a.pdf","x","","pdf/page:1/text/line:1","同一句。"),snapshot);
        assertThat(located.start()).isEqualTo(5);assertThat(BudgetSourceText.text(snapshot).substring(located.start(),located.end())).isEqualTo("同一句。");
        assertThat(BudgetSourceText.locate(new Evidence(id,"a.pdf","x","","pdf/page:1/text","批复用途为设备购置。"),snapshot).start()).isNull();
    }
    @Test void explicitHistoricalLabelsCannotEnterCurrentTotalsEvenWhenModelOmitsThem() throws Exception {
        String source="项目名称：松林服务站修缮项目\n本次项目预算：800000元。\n历史项目预算：3000000元。\n旧项目名称：旧楼扩建项目。";
        var reader=new BudgetUploadedMaterialReader(null,null,null);
        var snapshot=reader.extract(new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(List.of(new Segment("p1",source)),"NATIVE","a".repeat(64)),"synthetic");
        var inputs=List.of(new Input(UUID.randomUUID(),"说明.txt","PARSED",snapshot));
        assertThat(BudgetBatchAmounts.totals(inputs)).singleElement().satisfies(t->assertThat(t.amount()).isEqualByComparingTo("800000"));
        assertThat(BudgetBatchReviewService.projectName(inputs)).isEqualTo("松林服务站修缮项目");
        String current="项目名称：旧设备改造项目\n旧设备更新项目预算：800000元。\n本次不是历史项目预算：900000元。";
        var present=reader.extract(new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(List.of(new Segment("p1",current)),"NATIVE","b".repeat(64)),"synthetic2");
        assertThat(BudgetBatchAmounts.totals(List.of(new Input(UUID.randomUUID(),"当前.txt","PARSED",present)))).hasSize(2);
    }
    @Test void duplicateLabelKeepsExactValueAndPositionButAcceptsGroundedSemanticScope() throws Exception {
        var ai=mock(com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.class);
        String source="本次列示整体安排；下列数字仅用于以往工程对照。\n项目预算：3000000元。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Result(List.of(new Row("model",List.of(
                new Fact("projectBudgetAmount","3000000元","p1","项目预算：3000000元","REFERENCE","TOTAL")))),"m","v"));
        var reader=new BudgetUploadedMaterialReader(null,ai,null);
        var result=reader.extract(new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(List.of(new Segment("p1",source)),"NATIVE","a".repeat(64)),"synthetic");
        assertThat(result.rows().stream().flatMap(r->r.facts().stream())).filteredOn(f->f.field().equals("projectBudgetAmount")).singleElement().satisfies(f->{
            assertThat(f.value()).isEqualTo("3000000元");assertThat(f.locator()).isEqualTo("p1/line:1");assertThat(f.subjectScope()).isEqualTo("REFERENCE");
        });
        assertThat(BudgetBatchAmounts.totals(List.of(new Input(UUID.randomUUID(),"参考.txt","PARSED",result)))).isEmpty();
    }
    static final class Ledger implements MaterialExtractionLedger {
        Entry entry;boolean rejectAck;
        public Optional<Entry> find(UUID id){return Optional.ofNullable(entry);}
        public void insert(Entry e){entry=e;}
        public boolean begin(UUID id,int next,String token,Instant now){if(!entry.status().equals("READY")||entry.nextUnit()!=next)return false;entry=new Entry(id,entry.sourceHash(),entry.policyHash(),entry.unitCount(),next,entry.calls(),entry.usedMillis(),"CALLING",token,now,entry.resultsJson(),null,entry.deadlineAt());return true;}
        public boolean acknowledge(UUID id,int next,String token,int calls,long elapsed,String result,Instant now){if(rejectAck||!Objects.equals(token,entry.token()))return false;entry=new Entry(id,entry.sourceHash(),entry.policyHash(),entry.unitCount(),next+1,entry.calls()+calls,entry.usedMillis()+elapsed,next+1==entry.unitCount()?"COMPLETE":"READY",null,now,result,null,entry.deadlineAt());return true;}
        public void unknown(UUID id,String token,String reason,Instant now){if(Objects.equals(token,entry.token()))entry=new Entry(id,entry.sourceHash(),entry.policyHash(),entry.unitCount(),entry.nextUnit(),entry.calls(),entry.usedMillis(),"UNKNOWN",token,now,entry.resultsJson(),reason,entry.deadlineAt());}
        public void startDeadline(UUID id,Instant deadline){if(entry.deadlineAt()==null)entry=new Entry(id,entry.sourceHash(),entry.policyHash(),entry.unitCount(),entry.nextUnit(),entry.calls(),entry.usedMillis(),entry.status(),entry.token(),entry.updatedAt(),entry.resultsJson(),entry.reasonCode(),deadline);}
        public boolean terminate(UUID id,String token,String reason,Instant now){throw new UnsupportedOperationException();}
    }
}
