package com.fuquan.audit.common.material;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectNameResolverTest {
    @Test void documentTitlesNeverBecomeProjectNamesEvenWhenMislabelledByAModel() {
        for(String title:List.of("采购委托协议书","档案维护服务合同书","某单位设备采购委托协议书","需求标准","预算通知书","合同书.pdf")) {
            assertThat(ProjectNameResolver.resolve(List.of(title),List.of(title+"\n合同名称："+title),"自动整理项目-测试"))
                    .as(title).isEqualTo("自动整理项目-测试");
        }
    }
    @Test void explicitProjectLabelWinsOverAnAgreementTitleAndOtherFieldsRemainSeparate() {
        assertThat(ProjectNameResolver.resolve(List.of("档案维护服务合同书"),
                List.of("采购委托协议书\n项目名称：档案数字化维护服务项目\n合同名称：档案维护服务合同书"),"fallback"))
                .isEqualTo("档案数字化维护服务项目");
        assertThat(ProjectNameResolver.resolve(List.of(),List.of("项目名称：设备更新项目\t项目编号：P-01"),"fallback"))
                .isEqualTo("设备更新项目");
    }
    @Test void realExtractedNamesAndStandaloneProjectHeadingsRemainUsable() {
        assertThat(ProjectNameResolver.resolve(List.of("档案数字化维护服务"),List.of(),"fallback"))
                .isEqualTo("档案数字化维护服务");
        assertThat(ProjectNameResolver.resolve(List.of(),List.of("青禾阅览室改造项目\n采购需求见附件"),"fallback"))
                .isEqualTo("青禾阅览室改造项目");
    }
    @Test void stampedRevisionsAndDecoratedDocumentTitlesStillAreNotProjectNames() {
        for(String title:List.of("采购委托协议书（盖章版）","档案维护服务合同书_最终版","《采购委托协议书》","采购委托协议书V2","合同书（修订版）.DOCX"))
            assertThat(ProjectNameResolver.resolve(List.of(title),List.of(title),"fallback")).as(title).isEqualTo("fallback");
    }
    @Test void nearbyFieldsAndSameLineMetadataDoNotBecomePartOfProjectName() {
        assertThat(ProjectNameResolver.resolve(List.of(),List.of("项目名称：青榆库房改造项目 采购人：青榆合成档案中心"),"fallback"))
                .isEqualTo("青榆库房改造项目");
        assertThat(ProjectNameResolver.resolve(List.of(),List.of("合同名称：采购委托协议书；备注：以下简称项目名称：采购代理服务"),"fallback"))
                .isEqualTo("fallback");
    }
}
