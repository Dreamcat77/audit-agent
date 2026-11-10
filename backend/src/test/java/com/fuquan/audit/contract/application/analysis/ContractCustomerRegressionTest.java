package com.fuquan.audit.contract.application.analysis;

import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractCustomerRegressionTest {
    @Test void explicitAbsenceOfGarbledTextDoesNotHideAnActualPaymentConcern(){
        assertThat(ContractAnalysisEvidence.ocrTranscriptionConcern("原文无乱码，付款比例前后矛盾。",List.of(),List.of())).isFalse();
    }
    @Test void awardedSupplierCaptionRetainsTheAwardPrefixRatherThanBecomingAGenericSupplier(){
        String text="中标供应商：远景技术有限公司\n中标金额：10000元";var s=ContractAnalysisEvidenceTest.source(text);
        var e=new Evidence(s.fileId(),s.sha256(),s.parseResultId(),s.parseVersion(),s.textSha256(),0,text.indexOf('\n'),text.substring(0,text.indexOf('\n')));
        assertThat(ContractAnalysisSemantics.fact(new Fact("AWARD_SUPPLIER","远景技术有限公司","RULE_RECALL_SOURCE_VERIFIED",List.of(e)),s,text)).isPresent().get().extracting(Fact::type).isEqualTo("AWARD_SUPPLIER");
        var tenderText="供应商：远景技术有限公司";var other=ContractAnalysisEvidenceTest.source(tenderText);
        assertThat(ContractAnalysisSemantics.fact(fact(other,tenderText,"AWARD_SUPPLIER","远景技术有限公司"),other,tenderText)).isEmpty();
    }
    @Test void aDateBlankWarningSurvivesProjectionDespiteALegacyGenericTerminationTitle(){
        String text="第一条 服务期限\n服务期限为2026年11月1日至2027年10月31日；履行完毕后终止。\n签订日期：2026年10月30日\n甲方签订日期： 年 月 日";var s=ContractAnalysisEvidenceTest.source(text);
        var f=new Finding("DATE_FORMAT_COMPLETENESS","WARNING","合同日期与终止条件需要关注","日期存在空白或无效格式。","",fact(s,text,"SIGN_DATE","年 月 日").evidence(),"DETERMINISTIC_DOCUMENT");
        assertThat(ContractFindingSupport.supports(f,s,text)).isTrue();
        var out=new Output("OTHER","GENERAL",true,List.of(),List.of(),List.of(),List.of(f),0,null,"document-rules-v1");
        assertThat(ContractAnalysisSemantics.project(out,s,text,false).findings()).anySatisfy(found->{assertThat(found.code()).isEqualTo("DATE_FORMAT_COMPLETENESS");assertThat(found.level()).isEqualTo("WARNING");assertThat(found.title()).doesNotContain("终止");assertThat(found.evidence()).allSatisfy(e->assertThat(e.excerpt()).contains("年 月 日").doesNotContain("2026年10月30日"));});
    }
    @Test void ancillaryNamesAloneCannotProveLackOfLegalPersonalityButConcreteClauseRiskRemains(){
        String a="甲方：青松管理局",b="甲方：青松管理局保障处";var s=ContractAnalysisEvidenceTest.source(a);var raw=ContractAnalysisEvidenceTest.source(b);
        var annex=new Source(raw.fileId(),"保密协议.pdf",raw.sha256(),raw.size(),raw.status(),raw.parseResultId(),raw.parseVersion(),raw.textSha256(),null);
        var f=new Finding("CROSS_FILE_CONFLICT","WARNING","甲方主体名称不一致","主合同与保密协议主体不同，若保障处非独立法人且无授权，可能不适格。","请补授权。",List.of(fact(s,a,"PARTY_A","青松管理局").evidence().get(0),fact(annex,b,"PARTY_A","青松管理局保障处").evidence().get(0)),"AI_REVIEW_SUGGESTION");
        var projected=ContractAnalysisEvidence.ancillaryPartyDifference(f,List.of(s,annex));
        assertThat(projected.level()).isEqualTo("INFO");assertThat(projected.explanation()).doesNotContain("非独立法人","不适格");assertThat(projected.evidence()).hasSize(2);
        var concrete=new Finding(f.code(),f.level(),"甲方付款条款不一致","主合同与保密协议要求的付款责任不一致。","",f.evidence(),f.origin());
        assertThat(ContractAnalysisEvidence.ancillaryPartyDifference(concrete,List.of(s,annex))).isEqualTo(concrete);
        assertThat(ContractAnalysisEvidence.ancillaryPartyDifference(f,List.of(s,raw))).isEqualTo(f);
    }
    @Test void uploadedFileAbsenceClaimsCannotOverrideAnActuallyEmbeddedAttachment(){
        String text="附件一《交付要求》为本合同组成部分。\n附件一 交付要求\n完整交付数字目录与校验记录，抽样准确率不低于99%。";
        var scope=ContractAnalysisEvidenceTest.scope(text);
        var finding=new ContractAnalysisAiPort.Finding("MATERIAL_SCOPE_NOTE","INFO","附件一交付要求需核实","合同正文引用附件一《交付要求》作为验收标准，但本次上传文件未包含该附件的独立文本。","",List.of(new ContractAnalysisAiPort.Citation(scope.source().fileId(),"附件一《交付要求》为本合同组成部分。")));
        var out=new ContractAnalysisEvidence().validate(ContractAnalysisEvidenceTest.result(List.of(),List.of(),List.of(finding)),List.of(scope),"FILE");
        assertThat(out.findings()).isEmpty();assertThat(out.rejectedCitations()).isEqualTo(1);
        var review=new com.fuquan.audit.contract.application.clause.ContractTermClauseAnalyzer().analyze(List.of(new com.fuquan.audit.contract.application.clause.ContractClauseMaterial(scope.source().fileId(),"合同.pdf",com.fuquan.audit.contract.domain.project.ProjectFileCategory.CONTRACT_BODY,text)));
        assertThat(review.deterministicChecks()).anySatisfy(c->{assertThat(c.type()).isEqualTo("ATTACHMENT_REFERENCE_MATCH");assertThat(c.status()).isEqualTo("NORMAL");assertThat(c.sources()).hasSizeGreaterThanOrEqualTo(2);});
    }
    @Test void foreignCurrencyAndNumericSubstringsCannotCreateAnRmbMismatch(){
        for(String text:List.of("合同金额：10000美元，大写：壹万元整。","合同金额：110000元，大写：壹万元整。")){
            var s=ContractAnalysisEvidenceTest.source(text);
            assertThat(ContractAmountPairs.pairs(List.of(fact(s,text,"TOTAL_AMOUNT","10000"),fact(s,text,"AMOUNT_WORDS","壹万元整")),text)).isEmpty();
        }
    }
    @Test void findingsFromDifferentFrozenParsesCannotBeDeduplicated(){
        String text="甲方签订日期： 年 月 日";var s=ContractAnalysisEvidenceTest.source(text);var e=fact(s,text,"PARTY_A","甲方").evidence().get(0);
        var changed=new Evidence(e.fileId(),e.fileSha256(),UUID.randomUUID(),2,e.textSha256(),e.start(),e.end(),e.excerpt());
        var one=new Finding("SIGN_DATE_COMPLETENESS","WARNING","甲方签订日期未填","甲方日期留空。","",List.of(e),"AI_REVIEW_SUGGESTION");
        var two=new Finding(one.code(),one.level(),one.title(),one.explanation(),"",List.of(changed),one.origin());
        assertThat(ContractResultConsolidation.findings(List.of(one,two))).hasSize(2);
    }
    static Fact fact(Source s,String text,String type,String value){return ContractAnalysisRulesTest.fact(s,text,type,value);}
    static Output output(List<Fact> facts){return ContractAnalysisRulesTest.output(facts);}
    final ContractAnalysisRules rules=new ContractAnalysisRules(new com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor(Clock.systemUTC()),new ContractAnalysisEvidence());
    @Test void actualRecallFindsLaterUnpunctuatedPriceWithoutPromotingGuaranteeOrHistoricalPrice(){
        String text="合同总价：10000元，大写：壹万元整。\n补充价款约定：合同金额20000元，大写：叁万元整。\n履约保证金：500元，大写：伍佰元整。\n历史业绩\n合同金额：3800000元，大写：叁佰捌拾万元整。";
        var s=ContractAnalysisEvidenceTest.source(text);
        var file=new com.fuquan.audit.contract.domain.project.ProjectFile(s.fileId(),UUID.randomUUID(),null,"合同.pdf","合同.pdf","application/pdf",com.fuquan.audit.contract.domain.project.ProjectFileCategory.CONTRACT_BODY,
            com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus.AVAILABLE,com.fuquan.audit.contract.domain.project.ProjectFileParseStatus.PARSED,s.sha256(),s.sha256(),100,text,null,java.time.Instant.now(),java.time.Instant.now());
        var recalled=rules.recall(file,s,text);
        assertThat(recalled.facts().stream().filter(f->f.type().equals("TOTAL_AMOUNT"))).extracting(Fact::value).contains("10000元","20000元").doesNotContain("500元","3800000元");
        assertThat(recalled.facts().stream().filter(f->f.type().equals("AMOUNT_WORDS"))).extracting(Fact::value).doesNotContain("伍佰元整","叁佰捌拾万元整");
        assertThat(rules.evaluate(s,List.of(recalled),text).findings()).anySatisfy(f->{assertThat(f.code()).isEqualTo("F02_AMOUNT_WORDS_MATCH");assertThat(f.explanation()).contains("20000","30000");});
    }
    @Test void annotatedFullPartyAndItsExplicitAliasConsolidateToOneGroundedLegalName(){
        String text="甲方：青松管理局（以下简称松局）\n甲方（盖章）：松局\n主管单位：青松管理局（主管）";var s=ContractAnalysisEvidenceTest.source(text);
        var facts=List.of(fact(s,text,"PARTY_A","青松管理局（以下简称松局）"),fact(s,text,"PARTY_A","松局"),fact(s,text,"PARTY_A","青松管理局（主管）"));
        assertThat(ContractAnalysisSemantics.facts(facts,s,text)).singleElement().extracting(Fact::value).isEqualTo("青松管理局");
    }
    @Test void multiplePricePairsAreCheckedLocallyInsteadOfDiscardedOrCrossMatched(){
        String text="合同总价：10000元，大写：壹万元整。\n补充约定合同金额：20000元，大写：叁万元整。\n履约保证金：500元，大写：伍佰元整。";
        var s=ContractAnalysisEvidenceTest.source(text);var facts=List.of(fact(s,text,"TOTAL_AMOUNT","10000元"),fact(s,text,"TOTAL_AMOUNT","20000元"),fact(s,text,"AMOUNT_WORDS","壹万元整"),fact(s,text,"AMOUNT_WORDS","叁万元整"),fact(s,text,"AMOUNT_WORDS","伍佰元整"));
        var out=rules.evaluate(s,List.of(output(facts)),text);
        assertThat(out.checks().stream().filter(c->c.code().equals("F02_AMOUNT_WORDS_MATCH"))).hasSize(2).extracting(Check::status).containsExactly("PASS","FAIL");
        assertThat(out.findings()).singleElement().satisfies(f->{assertThat(f.explanation()).contains("20000","30000");assertThat(f.evidence()).allSatisfy(e->{assertThat(e.excerpt()).doesNotContain("保证金");assertThat(text.substring(e.start(),e.end())).isEqualTo(e.excerpt());});});
    }
    @Test void guaranteeAndSeparatePriceSectionsCannotBePairedWithContractTotal(){
        String text="合同总价：10000元。\n履约保证金：500元，大写：伍佰元整。";var s=ContractAnalysisEvidenceTest.source(text);
        var out=rules.evaluate(s,List.of(output(List.of(fact(s,text,"TOTAL_AMOUNT","10000元"),fact(s,text,"AMOUNT_WORDS","伍佰元整")))),text);
        assertThat(out.findings()).isEmpty();assertThat(out.checks()).anyMatch(c->c.code().equals("F02_AMOUNT_WORDS_MATCH")&&c.status().equals("NOT_EVALUATED"));
    }
    @Test void sameLinePriceEvidenceDoesNotHighlightEarlierPriceOrFollowingGuarantee(){
        String text="合同总价：10000元，大写：壹万元整；补充合同金额：20000元，大写：叁万元整；履约保证金：500元，大写：伍佰元整。";var s=ContractAnalysisEvidenceTest.source(text);
        var facts=List.of(fact(s,text,"TOTAL_AMOUNT","10000元"),fact(s,text,"TOTAL_AMOUNT","20000元"),fact(s,text,"AMOUNT_WORDS","壹万元整"),fact(s,text,"AMOUNT_WORDS","叁万元整"),fact(s,text,"AMOUNT_WORDS","伍佰元整"));
        assertThat(rules.evaluate(s,List.of(output(facts)),text).findings()).singleElement().satisfies(f->assertThat(f.evidence()).allSatisfy(e->assertThat(e.excerpt()).contains("20000","叁万元").doesNotContain("10000","保证金","500")));
    }
    @Test void duplicateNumericAndWordsSpellingsMergeButDifferentRolesAndRowsRemain(){
        String text="合同总价10000元，壹万元整，人民币壹万元整。";var s=ContractAnalysisEvidenceTest.source(text);
        var result=ContractFactConsolidation.merge(List.of(fact(s,text,"AMOUNT_WORDS","壹万元整"),fact(s,text,"AMOUNT_WORDS","人民币壹万元整"),fact(s,text,"BUDGET_AMOUNT","10000元"),fact(s,text,"TOTAL_AMOUNT","10000元")),text);
        assertThat(result).hasSize(3);assertThat(result.stream().filter(f->f.type().equals("AMOUNT_WORDS"))).hasSize(1);
    }
    @Test void partyAnnotationsAndExplicitAliasesMergeButDepartmentsAndDifferentCompaniesRemain(){
        String text="甲方：青松管理局（以下简称松局）\n甲方：松局\n甲方：青松管理局（主管）\n甲方：青松管理局保障处\n乙方：青松科技有限公司";var s=ContractAnalysisEvidenceTest.source(text);
        var result=ContractAnalysisSemantics.facts(List.of(fact(s,text,"PARTY_A","青松管理局"),fact(s,text,"PARTY_A","松局"),fact(s,text,"PARTY_A","青松管理局（主管）"),fact(s,text,"PARTY_A","青松管理局保障处"),fact(s,text,"PARTY_B","青松科技有限公司")),s,text);
        assertThat(result.stream().filter(f->f.type().equals("PARTY_A"))).extracting(Fact::value).containsExactly("青松管理局","青松管理局保障处");
        assertThat(result.stream().filter(f->f.type().equals("PARTY_B"))).hasSize(1);
    }
    @Test void projectManagerTrainingIsAProjectNameRatherThanAnEmployee(){
        String text="项目名称：项目经理培训与能力提升";var s=ContractAnalysisEvidenceTest.source(text);
        assertThat(ContractAnalysisSemantics.fact(fact(s,text,"PROJECT_NAME","项目经理培训与能力提升"),s,text)).isPresent();
        assertThat(com.fuquan.audit.common.material.ProjectNameResolver.resolve(List.of(),List.of("项目名称：\n青禾档案数字化维护\n项目编号：A-01"),null)).isEqualTo("青禾档案数字化维护");
        assertThat(com.fuquan.audit.common.material.ProjectNameResolver.resolve(List.of(),List.of("项目名称：\n项目编号：A-01"),null)).isNull();
    }
    @Test void lateProjectNameCanBeFoundButHistoricalProjectNameCannot(){
        String text="说明。\n".repeat(500)+"项目名称：青禾档案数字化维护\n历史合同\n项目名称：其他档案整理工程";
        assertThat(com.fuquan.audit.common.material.ProjectNameResolver.resolve(List.of(),List.of(ContractAnalysisSemantics.currentNamingText(text)),null)).isEqualTo("青禾档案数字化维护");
        assertThat(ContractAnalysisSemantics.currentNamingText("历史业绩\n项目名称：旧项目" )).doesNotContain("旧项目");
    }
    @Test void wideCitationIsNarrowedToTheActuallyBlankDateEvenWithAnotherFilledDate(){
        String text="合同编号：A-2026\n签订日期：2026年1月2日\n甲方签订日期： 年 月 日\n合同签字后生效。";var s=ContractAnalysisEvidenceTest.source(text);
        var issue=new Finding("SIGN_DATE_COMPLETENESS","WARNING","签订日期未填写","甲方签订日期留空。","",fact(s,text,"PROJECT_NAME","A").evidence(),"AI_REVIEW_SUGGESTION");
        assertThat(ContractFindingSupport.supports(issue,s,text)).isTrue();
        var narrowed=ContractFindingSupport.narrow(issue,s,text);
        assertThat(narrowed.evidence()).isNotEmpty().allSatisfy(e->{assertThat(e.excerpt()).doesNotContain("合同编号","2026年1月2日");assertThat(text.substring(e.start(),e.end())).isEqualTo(e.excerpt());});
    }
    @Test void textRecognitionConcernIsNotPublishedAsConfirmedPaymentFault(){
        String text="付款条件：验收后□日内付清。";var s=ContractAnalysisEvidenceTest.scope(text);
        var issue=new ContractAnalysisAiPort.Finding("OTHER_CONCRETE_RISK","WARNING","付款条件文本存在乱码","付款文本存在乱码。","应修复合同。",List.of(new ContractAnalysisAiPort.Citation(s.source().fileId(),text)));
        var out=new ContractAnalysisEvidence().validate(ContractAnalysisEvidenceTest.result(List.of(),List.of(),List.of(issue)),List.of(s),"FILE");
        assertThat(out.findings()).singleElement().satisfies(f->{assertThat(f.level()).isEqualTo("INFO");assertThat(f.origin()).isEqualTo("OCR_TRANSCRIPTION_UNCERTAIN");});
    }
    @Test void sameTypedIssueMergesButDifferentActorsAndSeparateLocationsDoNot(){
        String text="甲方与乙方签署日期分别留空。\n其他约定。";var s=ContractAnalysisEvidenceTest.source(text);var es=fact(s,text,"PARTY_A","甲方").evidence();
        var a=new Finding("SIGN_DATE_COMPLETENESS","WARNING","甲方签订日期未填","甲方签订日期留空。","",es,"AI_REVIEW_SUGGESTION");
        var b=new Finding("SIGN_DATE_COMPLETENESS","WARNING","甲方日期缺失","甲方签订日期未填写。","",es,"DETERMINISTIC_DOCUMENT");
        var c=new Finding("SIGN_DATE_COMPLETENESS","WARNING","乙方签订日期未填","乙方日期留空。","",es,"AI_REVIEW_SUGGESTION");
        assertThat(ContractResultConsolidation.findings(List.of(a,b,c))).hasSize(2);
        var other=new Finding(c.code(),c.level(),c.title(),c.explanation(),"",List.of(new Evidence(s.fileId(),s.sha256(),s.parseResultId(),1,s.textSha256(),text.indexOf("其他"),text.length(),"其他约定。")),c.origin());
        assertThat(ContractResultConsolidation.findings(List.of(c,other))).hasSize(2);
    }
    @Test void emptyPartyCaptionsAreNotActualNamesAndAuxiliaryAgreementsAreNotMainContract(){
        for(String v:List.of("甲方","乙方","乙方（承揽方）","甲方单位名称","单位名称"))assertThat(com.fuquan.audit.contract.domain.ContractPartyName.isPresent(v)).as(v).isFalse();
        assertThat(new com.fuquan.audit.contract.application.project.ProjectFileClassifier().classify("廉洁协议.pdf")).isEqualTo(com.fuquan.audit.contract.domain.project.ProjectFileCategory.SUPPLEMENT);
    }
}
