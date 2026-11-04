package com.fuquan.audit.contract.application.analysis;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class ContractAnalysisCategoryTest {
    @Test void supportingDocumentTitleDoesNotBecomeAContractName(){
        String text="中标通知书";var source=ContractAnalysisEvidenceTest.source(text);
        var e=new Evidence(source.fileId(),source.sha256(),source.parseResultId(),1,source.textSha256(),0,text.length(),text);
        var doc=new ContractAnalysisReport.Document(source.fileId(),"中标通知书.docx","READY","AWARD","GENERAL",List.of(new Fact("CONTRACT_NAME",text,"MODEL",List.of(e))),List.of(),List.of(),List.of(),text.length(),text.length(),true);
        var structure=ContractAnalysisCategories.project(List.of(doc),List.of(),List.of()).get(1);
        assertThat(structure.items()).singleElement().satisfies(i->{
            assertThat(i.title()).isEqualTo("材料名称");
            assertThat(i.summary()).isEqualTo("材料名称：中标通知书");
            assertThat(i.evidence()).containsExactly(e);
        });
    }
    @Test void eightViewsExistWithoutFormalContractAndRetainRealClauseContent(){
        String text="合同自双方盖章之日起生效，一式肆份。";var source=ContractAnalysisEvidenceTest.source(text);
        var e=new Evidence(source.fileId(),source.sha256(),source.parseResultId(),1,source.textSha256(),0,text.length(),text);
        var doc=new ContractAnalysisReport.Document(source.fileId(),"合同书.pdf","READY","CONTRACT","GENERAL",List.of(),List.of(new Clause("EFFECTIVENESS","双方盖章生效，合同肆份",List.of(e))),List.of(),List.of(),text.length(),text.length(),true);
        var views=ContractAnalysisCategories.project(List.of(doc),List.of(),List.of());
        assertThat(views).hasSize(8);assertThat(views).extracting(ContractAnalysisCategories.Category::id).doesNotHaveDuplicates();
        assertThat(views.get(6).items()).singleElement().satisfies(i->{assertThat(i.summary()).contains("肆份");assertThat(i.evidence()).containsExactly(e);});
        assertThat(views.get(0).status()).isEqualTo("DATA_INSUFFICIENT");
        assertThat(ContractAnalysisCategories.project(List.of(doc),List.of(),List.of())).isEqualTo(views);
    }
    @Test void longEvidenceIsShortenedAsRealContiguousTextWithExactOffsets(){
        String text="前段文字".repeat(180)+"甲方：澄川档案服务中心"+"。后续约定".repeat(180);var source=ContractAnalysisEvidenceTest.source(text);
        var e=new Evidence(source.fileId(),source.sha256(),source.parseResultId(),1,source.textSha256(),0,text.length(),text);
        var shortEvidence=ContractAnalysisFactPublisher.shortEvidence(e,"澄川档案服务中心");
        assertThat(shortEvidence).isNotNull();assertThat(shortEvidence.excerpt()).hasSizeLessThanOrEqualTo(240).contains("甲方：澄川档案服务中心");
        assertThat(text.substring(shortEvidence.start(),shortEvidence.end())).isEqualTo(shortEvidence.excerpt());
        assertThat(ContractAnalysisFactPublisher.shortEvidence(e,"不存在的乙方")).isNull();
    }
}
