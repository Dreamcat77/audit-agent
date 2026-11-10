package com.fuquan.audit.procurement.autocase;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.procurement.application.port.ProcurementTenderRuleRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;
import static com.fuquan.audit.procurement.autocase.CaseReviewAiPort.*;

class CaseReviewEngineTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-10-27T04:00:00Z"),ZoneOffset.UTC);
    final CaseReviewAiPort ai=mock(CaseReviewAiPort.class);
    final ProcurementTenderRuleRepository rules=mock(ProcurementTenderRuleRepository.class);
    CaseReviewEngine engine;
    @BeforeEach void setup(){when(rules.findEnabled()).thenReturn(List.of());engine=new CaseReviewEngine(ai,rules,clock,1000);}
    @AfterEach void close(){engine.close();}
    @Test void singleBidScopeLimitationsDoNotBecomeRisksButConcreteScoringDefectRemains() {
        var docs=List.of(doc("投标文件","供应商北辰软件有限公司，报价2200000元，180天交付。服务方案由评委自由酌情评分，不设置指标。"));
        var request=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",docs);
        var refs=List.of(request.evidence().get(0).id());
        when(ai.analyze(request)).thenReturn(new Result("TEST",request.version(),false,List.of(
            new Candidate("TECHNICAL_AMBIGUITY","WARNING","技术需求与评分标准缺失","仅上传投标文件，未提供招标文件。","补充完整招标文件",refs),
            new Candidate("PROCEDURAL_EVIDENCE","WARNING","关键程序性文件缺失","缺少招标公告和中标通知书。","提供相关程序文件",refs,"MATERIAL_SCOPE"),
            new Candidate("EVALUATION_BASIS","WARNING","评分缺少客观依据","条款写明自由酌情且不设置指标。","列明客观评分指标",refs,"CLAUSE_ISSUE")),
            List.of(new Observation("DATE_AND_PLAN","投标文件承诺180天交付。",refs))));
        var result=engine.execute(request,engine.running(engine.pending(request,docs)));
        assertThat(result.findings()).extracting(ReviewFinding::code).containsExactly("EVALUATION_BASIS");
        assertThat(result.checks()).anyMatch(c->c.explanation().contains("180天交付"));
        assertThat(result.status()).isEqualTo("PARTIAL");
    }
    @Test void optionalSuggestionsAreNeutralObservationsAndCannotCreatePriorityWarnings() {
        var docs=List.of(doc("采购文件","验收后付款，可约定验收办理时限。"));
        var request=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",docs);var refs=List.of(request.evidence().get(0).id());
        when(ai.analyze(request)).thenReturn(new Result("TEST",request.version(),false,List.of(
            new Candidate("CLAUSE_AMBIGUITY","WARNING","可完善验收安排","验收后付款。","可增加办理时限",refs,"IMPROVEMENT")),List.of()));
        var result=engine.execute(request,engine.running(engine.pending(request,docs)));
        assertThat(result.findings()).isEmpty();assertThat(result.checks()).anyMatch(c->c.status().equals("SOURCE_GROUNDED_OBSERVATION")&&c.code().equals("CLAUSE_AMBIGUITY"));
    }
    static Document doc(String title,String body,Fact... facts){return new Document(UUID.randomUUID(),title+".pdf",100,"a".repeat(64),"key","EXTRACTED",List.of(new MaterialFieldAiPort.Segment("p1",title+"\n"+body)),List.of(facts),"PDFBOX",null,1);}
    static Fact fact(String type,String value,String excerpt){return new Fact("r",type,value,"TEXT","p1",excerpt,"SOURCE_VERIFIED","TEST","v1");}
    @Test void forbiddenExampleDoesNotCreateDurableRuleFindingButOperativeRestrictionDoes(){
        var rule=new com.fuquan.audit.procurement.domain.ProcurementTenderRule(UUID.randomUUID(),"TEST-REGION","地域条件","地域","KEYWORD","本地注册","WARNING","公平采购","BUILTIN",true,Instant.EPOCH,Instant.EPOCH);
        when(rules.findEnabled()).thenReturn(List.of(rule));
        String example="编制说明：以下内容是禁止采用的反面示例：“仅限本地注册企业参与投标”。该示例不是本项目的资格条件。";
        var docs=List.of(doc("招标文件",example));var request=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",docs);
        assertThat(engine.pending(request,docs).findings()).isEmpty();
        var actualDocs=List.of(doc("招标文件",example+"本项目投标人须为本地注册企业。"));
        var actual=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",actualDocs);
        assertThat(actual.ruleSignals()).hasSize(1);assertThat(engine.pending(actual,actualDocs).findings()).isEmpty();
    }
    @Test void broadBrandKeywordIsOnlyAClueAndSemanticCounterevidencePreventsRisk(){
        var rule=new com.fuquan.audit.procurement.domain.ProcurementTenderRule(UUID.randomUUID(),"SYN-BRAND","品牌产品要求","品牌","KEYWORD","品牌产品","CRITICAL","公平竞争","BUILTIN",true,Instant.EPOCH,Instant.EPOCH);
        when(rules.findEnabled()).thenReturn(List.of(rule));
        var docs=List.of(doc("采购需求","接受满足指标的等效品牌产品。"));
        var request=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",docs);
        assertThat(request.ruleSignals()).hasSize(1);
        when(ai.analyze(request)).thenReturn(CaseReviewFixtures.result(request));
        var result=engine.execute(request,engine.running(engine.pending(request,docs)));
        assertThat(result.findings()).isEmpty();assertThat(result.status()).isEqualTo("COMPLETED");
    }
    @Test void genuineSemanticSevereRiskRemainsConfirmableAndIsNotDuplicatedByRuleHit(){
        var rule=new com.fuquan.audit.procurement.domain.ProcurementTenderRule(UUID.randomUUID(),"SYN-BRAND","品牌产品要求","品牌","KEYWORD","仅限","CRITICAL","公平竞争","BUILTIN",true,Instant.EPOCH,Instant.EPOCH);
        when(rules.findEnabled()).thenReturn(List.of(rule));var docs=List.of(doc("招标文件","仅限星桥品牌，其他品牌不得参与。"));
        var request=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",docs);var fixtures=CaseReviewFixtures.result(request);
        when(ai.analyze(request)).thenReturn(new Result("MOCK",request.version(),true,List.of(new Candidate("RESTRICTIVE_CONDITION","CRITICAL","指定品牌排除其他供应商","招标文件仅限星桥品牌，其他品牌不得参与。","改为客观性能指标并允许等效产品",List.of(request.evidence().get(0).id()))),fixtures.observations()));
        var result=engine.execute(request,engine.running(engine.pending(request,docs)));
        assertThat(result.findings()).singleElement().satisfies(f->{assertThat(f.level()).isEqualTo("CRITICAL");assertThat(f.origin()).isEqualTo("MODEL");assertThat(CaseFindingAcknowledgments.identity(f)).hasSize(64);});
    }
    @Test void technicalZeroFactsStillGetsRawContextAndFilenameIsNotAuthority(){
        var d=doc("办公终端采购技术需求表","内存不低于16GB；固态硬盘不低于512GB；等效产品可以响应");
        var r=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",List.of(d));
        assertThat(r.documents()).singleElement().extracting(ReviewScope::role).isEqualTo("TECHNICAL");
        assertThat(r.evidence().get(0).excerpt()).contains("16GB","512GB");assertThat(r.facts()).isEmpty();
        assertThat(CaseReviewContext.role(doc("普通业务说明","引用招标文件、投标文件或报价单不改变封面类型"))).isEqualTo("OTHER");
    }
    @Test void overBudgetQuoteCannotBecomeAnAwardMismatchWithoutAwardEvidence(){
        var docs=List.of(doc("招标文件","预算100元",fact("budgetAmount","100元","预算100元")),
                doc("报价单","供应商甲，报价120元",fact("supplierName","供应商甲","供应商甲"),fact("quoteAmount","120元","报价120元")));
        var request=engine.prepare(UUID.randomUUID(),"AGENT_PRECHECK",docs);var fixture=CaseReviewFixtures.result(request);
        when(ai.analyze(request)).thenReturn(new Result("MOCK",request.version(),true,List.of(new Candidate(
                "AWARD_QUOTE_MISMATCH","WARNING","报价超限","报价120元超过预算100元","核对报价",request.evidence().stream().map(SourceEvidence::id).toList())),fixture.observations()));
        var result=engine.execute(request,engine.running(engine.pending(request,docs)));
        assertThat(result.findings()).singleElement().satisfies(f->assertThat(f.code()).isEqualTo("QUOTE_BUDGET"));
        assertThat(result.rejectedCitations()).isEqualTo(1);assertThat(result.status()).isEqualTo("PARTIAL");
    }
    @Test void actualAwardMismatchIsKeptOnceWithAConcreteTitle(){
        var docs=List.of(doc("报价单","供应商甲，报价120元",fact("supplierName","供应商甲","供应商甲"),fact("quoteAmount","120元","报价120元")),
                doc("中标通知书","供应商甲，中标金额110元",fact("supplierName","供应商甲","供应商甲"),fact("awardAmount","110元","中标金额110元")));
        var request=engine.prepare(UUID.randomUUID(),"AGENT_PRECHECK",docs);var fixture=CaseReviewFixtures.result(request);
        when(ai.analyze(request)).thenReturn(new Result("MOCK",request.version(),true,List.of(new Candidate(
                "AWARD_QUOTE_MISMATCH","WARNING","成交价不同","报价120元、成交110元","核对成交依据",request.evidence().stream().map(SourceEvidence::id).toList())),fixture.observations()));
        var result=engine.execute(request,engine.running(engine.pending(request,docs)));
        assertThat(result.findings()).singleElement().satisfies(f->{assertThat(f.code()).isEqualTo("AWARD_QUOTE_MISMATCH");
            assertThat(f.origin()).isEqualTo("DETERMINISTIC");assertThat(f.title()).isEqualTo("中标金额与同一供应商报价不一致");});
        assertThat(result.rejectedCitations()).isZero();
    }
    @Test void tenderReviewIncludesQuotationScopeAndSingleClauseScoringDefect(){
        var docs=List.of(doc("采购文件","配件耗材费用应计入含税总报价。服务方案由评委自由酌情评分，不设置指标。"),
                doc("报价单","报价仅含人工，不包含配件和耗材费用。"));
        var request=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",docs);var fixture=CaseReviewFixtures.result(request);
        assertThat(request.documents()).extracting(ReviewScope::role).containsExactly("TENDER","QUOTATION");
        assertThat(request.evidence()).anyMatch(e->e.excerpt().contains("不包含配件"));
        when(ai.analyze(request)).thenReturn(new Result("MOCK",request.version(),true,List.of(
                new Candidate("EVALUATION_BASIS","WARNING","评分缺少客观标准","服务方案分由评委自由酌情且无指标","明确评分指标与分档",List.of(request.evidence().get(0).id())),
                new Candidate("QUOTE_SCOPE","WARNING","报价未含必需耗材","采购要求包含耗材，报价明确不含","统一报价范围",request.evidence().stream().map(SourceEvidence::id).toList())),fixture.observations()));
        var result=engine.execute(request,engine.running(engine.pending(request,docs)));
        assertThat(result.findings()).extracting(ReviewFinding::code).containsExactly("EVALUATION_BASIS","QUOTE_SCOPE");
        assertThat(result.rejectedCitations()).isZero();
    }
    @Test void missingIdentityIsNotConflictAndBudgetDoesNotBecomeAward(){
        var d=doc("采购需求申请书","项目编号：FZ-CG-2026-001；预算上限222600元",fact("projectNo","FZ-CG-2026-001","项目编号：FZ-CG-2026-001"),fact("budgetAmount","222600元","预算上限222600元"));
        var a=doc("中标通知书","中标金额212000元；供应商甲",fact("awardAmount","212000元","中标金额212000元"));
        var r=engine.prepare(UUID.randomUUID(),"AGENT_PRECHECK",List.of(d,a,doc("验收方案","尚无实际验收结论")));
        assertThat(r.checks()).noneMatch(c->c.status().equals("CONFLICT"));
        assertThat(r.checks()).filteredOn(c->c.code().equals("BUDGET_CONFLICT")).singleElement().satisfies(c->assertThat(c.explanation()).contains("222600").doesNotContain("212000"));
    }
    @Test void differingIdentityStringsRequireSemanticReviewRatherThanCreateRisk(){
        var a=doc("需求申请书","编号A",fact("projectNo","A","编号A"));var b=doc("预算批复","编号B",fact("projectNo","B","编号B"));
        var r=engine.prepare(UUID.randomUUID(),"AGENT_PRECHECK",List.of(a,b));var pending=engine.pending(r,List.of(a,b));
        assertThat(pending.findings()).isEmpty();
        assertThat(r.checks()).filteredOn(c->c.code().equals("IDENTITY_projectNo")).singleElement().satisfies(c->{
            assertThat(c.status()).isEqualTo("SEMANTIC_REVIEW_REQUIRED");assertThat(c.evidence()).hasSize(2);
        });
    }
    @Test void inventedOrCrossCaseCitationsAndOneFileConflictAreRejected(){
        var docs=List.of(doc("采购招标文件","付款30%，终验65%，质保5%"));var r=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",docs);
        var mock=CaseReviewFixtures.result(r);
        var candidates=List.of(new Candidate("CLAUSE_AMBIGUITY","WARNING","疑点","说明","核实",List.of("E-outside-case")),
                new Candidate("PAYMENT_CONFLICT","WARNING","付款冲突","两个付款比例冲突","核实",List.of(r.evidence().get(0).id())));
        when(ai.analyze(r)).thenReturn(new Result("MOCK",r.version(),true,candidates,mock.observations()));
        var actual=engine.execute(r,engine.running(engine.pending(r,docs)));
        assertThat(actual.status()).isEqualTo("PARTIAL");assertThat(actual.rejectedCitations()).isEqualTo(2);assertThat(actual.findings()).isEmpty();
    }
    @Test void completionRequiresAllTopicsAndSourceCoverage(){
        var docs=List.of(doc("招标文件","采购说明"));var r=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",docs);
        when(ai.analyze(r)).thenReturn(new Result("MOCK",r.version(),true,List.of(),List.of(new Observation("TECHNICAL_REQUIREMENTS","仅核对技术",List.of(r.evidence().get(0).id())))));
        assertThat(engine.execute(r,engine.running(engine.pending(r,docs))).status()).isEqualTo("PARTIAL");
    }
    @Test void timeoutDoesNotBecomeFailedOrComplete() {
        engine.close();engine=new CaseReviewEngine(ai,rules,clock,5);
        var docs=List.of(doc("技术需求表","16GB"));var r=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",docs);
        when(ai.analyze(r)).thenAnswer(call->{Thread.sleep(1000);return CaseReviewFixtures.result(r);});
        var actual=engine.execute(r,engine.running(engine.pending(r,docs)));
        assertThat(actual.status()).isEqualTo("UNKNOWN");assertThat(actual.errorCode()).contains("TIMEOUT");
    }
    @Test void oversizedContextCannotClaimCompleteAndFailedFileStillExposesRawEvidence(){
        var a=doc("技术需求表","技术要求".repeat(4000));var b=doc("招标文件","付款说明").state("UNKNOWN","EXTRACTION_TIMEOUT");
        var c=CaseReviewContext.select("TENDER_COMPLIANCE",List.of(a,b));
        assertThat(c.gaps()).anyMatch(g->g.endsWith("CONTEXT_TRUNCATED"));assertThat(c.gaps()).anyMatch(g->g.endsWith("FILE_UNKNOWN"));
        assertThat(c.evidence()).anyMatch(e->e.documentId().equals(b.id()));assertThat(c.evidence().stream().mapToInt(e->e.excerpt().length()).sum()).isLessThanOrEqualTo(54000);
    }
    @Test void oldPhase2JsonRemainsReadable()throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var report=json.readValue("{\"quoteStatus\":\"COMPLETED\",\"offers\":[],\"notices\":[],\"modules\":{\"QUOTE\":\"COMPLETED\"}}",Report.class);
        assertThat(report.reviews()).isEmpty();assertThat(report.quoteStatus()).isEqualTo("COMPLETED");
    }
    @Test void fullInventoryIsSeparateFromSelectedContextAndPartOfTheFrozenDigest() {
        var tender=doc("招标文件","会议终端10台");var quote=doc("中标通知书","中标金额10000元");
        var request=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",List.of(tender,quote));
        assertThat(request.documents()).hasSize(1);assertThat(request.uploadedDocuments()).hasSize(2);
        assertThat(request.uploadedDocuments()).anyMatch(d->d.documentId().equals(quote.id())&&d.role().equals("AWARD"));
        assertThat(request.checks()).filteredOn(c->c.code().equals("MATERIAL_COVERAGE")).singleElement().satisfies(c->{
            assertThat(c.status()).isEqualTo("SYSTEM_SCOPE");assertThat(c.explanation()).contains("2 份材料","1 份正文");});
        assertThat(request.inputDigest()).isNotEqualTo(engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",List.of(tender)).inputDigest());
    }
    @Test void modelCannotOverwriteSystemCoverageOrClaimUploadedQuoteIsMissing() {
        var docs=List.of(doc("招标文件","本次材料含会议终端10台"),doc("报价单","报价10000元"));
        var request=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",docs);var id=request.evidence().get(0).id();
        when(ai.analyze(request)).thenReturn(new Result("MOCK",request.version(),true,List.of(
                new Candidate("PROCEDURAL_EVIDENCE","WARNING","缺少报价单","未提供报价单，无法比较","核实材料",List.of(id))),List.of(
                new Observation("MATERIAL_COVERAGE","本次缺少报价单及合同",List.of(id)),
                new Observation("SCORING_METHOD","本批报价单未上传",List.of(id)))));
        var result=engine.execute(request,engine.running(engine.pending(request,docs)));
        assertThat(result.status()).isEqualTo("PARTIAL");assertThat(result.rejectedCitations()).isEqualTo(3);
        assertThat(result.findings()).isEmpty();
        assertThat(result.checks()).filteredOn(c->c.code().equals("MATERIAL_COVERAGE")).singleElement().satisfies(c->assertThat(c.status()).isEqualTo("SYSTEM_SCOPE"));
        assertThat(result.checks()).noneMatch(c->c.explanation().contains("未上传")&&!c.status().equals("SYSTEM_SCOPE"));
    }
    @Test void genuineClauseAndCoverageLimitationsAreNotTurnedIntoAnAllClear() {
        var docs=List.of(doc("招标文件","未列具体评分办法"),doc("报价单","报价10000元"));
        var request=engine.prepare(UUID.randomUUID(),"TENDER_COMPLIANCE",docs);
        assertThat(CaseReviewEngine.contradictedMaterialAbsence("未提供评分细则，无法核验评分",request.uploadedDocuments())).isFalse();
        assertThat(CaseReviewEngine.contradictedMaterialAbsence("付款触发条件缺失",request.uploadedDocuments())).isFalse();
        assertThat(CaseReviewEngine.contradictedMaterialAbsence("报价单已上传，付款条件尚不能证明",request.uploadedDocuments())).isFalse();
        assertThat(CaseReviewEngine.contradictedMaterialAbsence("未提供澄清文件，报价单已上传",request.uploadedDocuments())).isFalse();
        assertThat(CaseReviewEngine.contradictedMaterialAbsence("采购文件未提供评分指标和档次，评分过于主观",request.uploadedDocuments())).isFalse();
        assertThat(CaseReviewEngine.contradictedMaterialAbsence("招标文件未提供可核验的服务方案评分细则",request.uploadedDocuments())).isFalse();
        assertThat(CaseReviewEngine.contradictedMaterialAbsence("未提供完整的采购文件，无法审查",request.uploadedDocuments())).isTrue();
        assertThat(CaseReviewEngine.contradictedMaterialAbsence("采购文件尚未提供，无法审查",request.uploadedDocuments())).isTrue();
        when(ai.analyze(request)).thenReturn(CaseReviewFixtures.result(request));
        var result=engine.execute(request,engine.running(engine.pending(request,docs)));
        assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.coverageGaps()).isEmpty();
    }
}
