package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisSemanticsTest {
    private Source source(String name,String text){return new Source(UUID.randomUUID(),name,"a".repeat(64),100,SourceState.READY,UUID.randomUUID(),1,sha256(text),null);}
    private Fact fact(Source source,String text,String type,String value,String quote){
        return new Fact(type,value,"AI_SOURCE_VERIFIED",List.of(new ContractAnalysisEvidence().locate(new ContractAnalysisEvidence.Scope(source,text,0,text.length()),quote).orElseThrow()));
    }
    @Test void bidIsPreservedButNeverBecomesContractOrAward(){
        String text="投标总价：220万元\n投标人名称：青禾技术有限公司\n招标编号：ZB-2026-01\n统一社会信用代码：913000000000000001";
        var s=source("投标文件.pdf",text);
        assertThat(ContractAnalysisSemantics.fact(fact(s,text,"TOTAL_AMOUNT","220万元","投标总价：220万元"),s,text).orElseThrow().type()).isEqualTo("BID_AMOUNT");
        assertThat(ContractAnalysisSemantics.fact(fact(s,text,"AWARD_SUPPLIER","青禾技术有限公司","投标人名称：青禾技术有限公司"),s,text).orElseThrow().type()).isEqualTo("BIDDER");
        for(String quote:List.of("招标编号：ZB-2026-01","统一社会信用代码：913000000000000001"))
            assertThat(ContractAnalysisSemantics.fact(fact(s,text,"CONTRACT_NO",quote.substring(quote.indexOf('：')+1),quote),s,text)).isEmpty();
    }
    @Test void rolesUseLocalEvidenceRatherThanOtherLabelsInSameQuote(){
        String text="甲方：青禾大学\n投标人名称：明川技术有限公司";var s=source("投标文件.pdf",text);
        assertThat(ContractAnalysisSemantics.fact(fact(s,text,"PARTY_A","明川技术有限公司",text),s,text).orElseThrow().type()).isEqualTo("BIDDER");
        assertThat(ContractAnalysisSemantics.fact(fact(s,text,"PARTY_A","青禾大学",text),s,text).orElseThrow().type()).isEqualTo("PARTY_A");
    }
    @Test void historicalAndFinancialSectionsDoNotPolluteCurrentContract(){
        String text="项目名称：青禾平台建设\n第三章 类似项目业绩\n合同金额：80万元\n乙方：旧项目服务有限公司\n第四章 合同条款\n合同金额：220万元";
        var s=source("投标文件.pdf",text);
        assertThat(ContractAnalysisSemantics.fact(fact(s,text,"TOTAL_AMOUNT","80万元","合同金额：80万元"),s,text)).isEmpty();
        assertThat(ContractAnalysisSemantics.fact(fact(s,text,"TOTAL_AMOUNT","220万元","合同金额：220万元"),s,text)).isPresent();
        assertThat(ContractAnalysisSemantics.reference("第三章 类似项目业绩……18\n合同金额：220万元",30)).isFalse();
    }
    @Test void titleCleanupPreservesMeaningfulParenthesesAndEvidence(){
        String text="合同名称：《青禾平台（二期）服务合同》；";var s=source("合同书.docx",text);
        var actual=ContractAnalysisSemantics.fact(fact(s,text,"CONTRACT_NAME","《青禾平台（二期）服务合同》；",text),s,text).orElseThrow();
        assertThat(actual.value()).isEqualTo("青禾平台（二期）服务合同");assertThat(actual.evidence().get(0).excerpt()).isEqualTo(text);
        assertThat(ContractAnalysisSemantics.key("明川（上海）技术有限公司")).isEqualTo(ContractAnalysisSemantics.key("明川(上海) 技术有限公司"));
        assertThat(ContractAnalysisSemantics.key("明川技术有限公司")).isNotEqualTo(ContractAnalysisSemantics.key("明川信息技术有限公司"));
    }
    @Test void modelScopeFiltersReferencesWithoutMarkingSuccessfulScopeAsFailed(){
        String text="合同金额：80万元";var s=source("合同书.docx",text);var scope=new ContractAnalysisEvidence.Scope(s,text,0,text.length());
        var cite=List.of(new ContractAnalysisAiPort.Citation(s.fileId(),text));
        var input=new ContractAnalysisAiPort.Result("synthetic","contract-case-v1","CONTRACT","GENERAL",true,
            List.of(new ContractAnalysisAiPort.Fact("TOTAL_AMOUNT","80万元",cite,"REFERENCE")),List.of(),List.of());
        var actual=new ContractAnalysisEvidence().validate(input,List.of(scope),"FILE");
        assertThat(actual.facts()).isEmpty();assertThat(actual.complete()).isTrue();
    }
    @Test void ocrTypoDoesNotBecomeCertainBusinessRiskButGenuineMismatchSurvives(){
        String text="合同金额：1972660元，大写：壹佰玖拾集万贰仟陆佰陆拾元整";var s=source("合同书.pdf",text);
        var scope=new ContractAnalysisEvidence.Scope(s,text,0,text.length(),true);
        var cite=List.of(new ContractAnalysisAiPort.Citation(s.fileId(),text));
        var input=new ContractAnalysisAiPort.Result("synthetic","contract-case-v1","CONTRACT","GENERAL",true,List.of(),List.of(),
            List.of(new ContractAnalysisAiPort.Finding("OTHER_CONCRETE_RISK","WARNING","金额大写有误","大写含错别字，应改为玖拾玖","修改大写",cite)));
        assertThat(new ContractAnalysisEvidence().validate(input,List.of(scope),"FILE").findings()).singleElement().satisfies(f->{
            assertThat(f.level()).isEqualTo("INFO");assertThat(f.suggestion()).doesNotContain("玖拾玖");});
        var checks=new ArrayList<Check>();var findings=new ArrayList<Finding>();
        ContractAnalysisRules.compare(checks,findings,"F02_AMOUNT_WORDS_MATCH","金额核对",new java.math.BigDecimal("1972660"),new java.math.BigDecimal("1992660"),List.of(fact(s,text,"TOTAL_AMOUNT","1972660元",text).evidence().get(0)));
        assertThat(findings).singleElement().satisfies(f->assertThat(f.level()).isEqualTo("WARNING"));
    }
    @Test void distinctPartiesAreRetainedAndConflictIsReportedButJointPartiesAreNotConflicts(){
        for(boolean joint:List.of(false,true)){
            String text=(joint?"共同甲方：":"甲方：")+"青禾大学\n甲方：明川研究院\n合同金额：100元";
            var s=source("合同书.docx",text);
            var facts=List.of(fact(s,text,"PARTY_A","青禾大学",text),fact(s,text,"PARTY_A","明川研究院",text));
            var out=new Output("CONTRACT","GENERAL",true,facts,List.of(),List.of(),List.of(),0,null,"test");
            var rules=new ContractAnalysisRules(new com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor(java.time.Clock.systemUTC()),new ContractAnalysisEvidence());
            var actual=rules.evaluate(s,List.of(out),text);
            assertThat(actual.findings().stream().anyMatch(f->f.code().equals("PARTY_A_NAME_CONFLICT"))).isEqualTo(!joint);
        }
    }
    @Test void partyDisplayMergesExactNormalizedNamesAndKeepsAllEvidence(){
        String text="甲方：明川（上海）技术有限公司\n甲方：明川(上海) 技术有限公司";var s=source("合同书.docx",text);
        var facts=List.of(fact(s,text,"PARTY_A","明川（上海）技术有限公司","甲方：明川（上海）技术有限公司"),fact(s,text,"PARTY_A","明川(上海) 技术有限公司","甲方：明川(上海) 技术有限公司"));
        var document=new ContractAnalysisReport.Document(s.fileId(),s.name(),"READY","CONTRACT","GENERAL",facts,List.of(),List.of(),List.of(),text.length(),text.length(),true);
        var party=ContractAnalysisCategories.project(List.of(document),List.of(),List.of()).stream().filter(c->c.id().equals("PARTY_QUALIFICATION")).findFirst().orElseThrow();
        assertThat(party.items()).singleElement().satisfies(i->assertThat(i.evidence()).hasSize(2));
    }

    @Test void neighboringAmountsAndOppositePartyLabelsKeepTheirActualRoles(){
        String text="预算金额为100万元，中标金额为90万元\n乙方：澄川技术有限公司";var s=source("合同书.docx",text);
        assertThat(ContractAnalysisSemantics.fact(fact(s,text,"TOTAL_AMOUNT","90万元",text),s,text).orElseThrow().type()).isEqualTo("AWARD_AMOUNT");
        assertThat(ContractAnalysisSemantics.fact(fact(s,text,"PARTY_A","澄川技术有限公司",text),s,text).orElseThrow().type()).isEqualTo("PARTY_B");
    }

    @Test void bidPriceIsVisibleInTheAmountCategoryUnderItsOwnLabel(){
        String text="投标总价：220万元";var s=source("投标文件.txt",text);
        var f=ContractAnalysisSemantics.fact(fact(s,text,"TOTAL_AMOUNT","220万元",text),s,text).orElseThrow();
        var doc=new ContractAnalysisReport.Document(s.fileId(),s.name(),"READY","BID","GENERAL",List.of(f),List.of(),List.of(),List.of(),text.length(),text.length(),true);
        var category=ContractAnalysisCategories.project(List.of(doc),List.of(),List.of()).get(0);
        assertThat(category.items()).singleElement().satisfies(i->{assertThat(i.title()).isEqualTo("投标报价");assertThat(i.summary()).doesNotContain("合同金额");});
        assertThat(category.status()).isEqualTo("AVAILABLE");
    }

    @Test void broadCurrentQuoteCannotLaunderAHistoricalAmount(){
        String text="项目名称：青禾平台建设\n第三章 历史业绩\n合同金额：80万元";var s=source("投标文件.txt",text);
        assertThat(ContractAnalysisSemantics.fact(fact(s,text,"TOTAL_AMOUNT","80万元",text),s,text)).isEmpty();
    }
    @Test void prohibitingConsortiumDoesNotSuppressRealPartyNameConflict(){
        String text="甲方：青禾大学\n甲方：明川研究院\n不接受联合体。";var s=source("合同书.docx",text);
        var facts=List.of(fact(s,text,"PARTY_A","青禾大学",text),fact(s,text,"PARTY_A","明川研究院",text));
        var out=new Output("CONTRACT","GENERAL",true,facts,List.of(),List.of(),List.of(),0,null,"test");
        var rules=new ContractAnalysisRules(new com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor(java.time.Clock.systemUTC()),new ContractAnalysisEvidence());
        assertThat(rules.evaluate(s,List.of(out),text).findings()).anyMatch(f->f.code().equals("PARTY_A_NAME_CONFLICT"));
    }

    @Test void tableCurrencyHeaderSupportsOnlyAnExactlyEquivalentAmount(){
        String text="投标报价（元）\n平台采购项目 2200000.00";var s=source("投标文件.txt",text);
        var cite=List.of(new ContractAnalysisAiPort.Citation(s.fileId(),text));
        var input=new ContractAnalysisAiPort.Result("synthetic","contract-case-v1","BID","GENERAL",true,List.of(new ContractAnalysisAiPort.Fact("BID_AMOUNT","2200000.00元",cite)),List.of(),List.of());
        assertThat(new ContractAnalysisEvidence().validate(input,List.of(new ContractAnalysisEvidence.Scope(s,text,0,text.length())),"FILE").facts()).singleElement().satisfies(f->assertThat(f.type()).isEqualTo("BID_AMOUNT"));
        assertThat(ContractAnalysisEvidence.containsValue("BID_AMOUNT","100元","投标报价（万元）100")).isFalse();
        assertThat(ContractAnalysisEvidence.containsValue("BID_AMOUNT","100元","投标报价（元）2100")).isFalse();
        assertThat(ContractAnalysisEvidence.containsValue("BID_AMOUNT","100元","投标报价：2100元")).isFalse();
        assertThat(ContractAnalysisEvidence.containsValue("BID_AMOUNT","100元","投标报价：-100元")).isFalse();
        assertThat(ContractAnalysisEvidence.containsValue("BID_AMOUNT","2200001元",text)).isFalse();
        assertThat(ContractAnalysisEvidence.containsValue("BID_AMOUNT","100万元","报价（万元）100")).isTrue();
        assertThat(ContractAnalysisEvidence.containsValue("BID_AMOUNT","100元","金额（元）100，预算（万元）200")).isFalse();
    }

    @Test void unitHeaderNormalizationDoesNotLaunderHistoricalAmounts(){
        String text="项目名称：青禾平台建设\n第三章 历史业绩\n合同金额（元）：800000";var s=source("投标文件.txt",text);
        assertThat(ContractAnalysisSemantics.fact(fact(s,text,"TOTAL_AMOUNT","800000元",text),s,text)).isEmpty();
    }

}
