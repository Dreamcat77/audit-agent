package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import java.util.*;
import org.junit.jupiter.api.Test;

class CaseAvailableContextTest {
    @Test void concreteTailPaymentAndDeliveryWinOverIndexAndGenericResponseWhileContextRemainsBounded() {
        var segments=new ArrayList<Segment>();
        segments.add(new Segment("cover","投标文件\n项目名称：北辰政务服务采购项目"));
        segments.add(new Segment("index","目录\n评标办法...........9\n付款与验收..........270\n".repeat(20)));
        for(int i=0;i<80;i++)segments.add(new Segment("body"+i,"符合性审查：付款条件符合招标文件要求。验收、服务期限、评分均符合要求。".repeat(20)));
        segments.add(new Segment("delivery","五、商务要求\n建设期：合同签订后180天内完成部署并验收。免费运维期：3年。"));
        segments.add(new Segment("payment","支付方式：首付50%，验收后40%，质保结束后10%。"));
        var doc=new AuditCase.Document(UUID.randomUUID(),"投标文件.pdf",100,"a".repeat(64),"key","UNKNOWN",segments,List.of(),"PDF",null,0);
        for(String module:CaseReviewContext.MODULES){
            var ctx=CaseReviewContext.select(module,List.of(doc));
            if(module.equals("AGENT_PRECHECK"))assertThat(ctx.evidence()).anyMatch(e->e.locator().equals("delivery"));
            if(module.equals("TENDER_COMPLIANCE"))assertThat(ctx.evidence()).anyMatch(e->e.locator().equals("payment"));
            assertThat(ctx.evidence()).noneMatch(e->e.locator().equals("index"));
            assertThat(ctx.evidence().stream().mapToInt(e->e.excerpt().length()).sum()).isLessThanOrEqualTo(12000);
            assertThat(ctx.gaps()).anyMatch(g->g.endsWith(":CONTEXT_TRUNCATED"));
        }
    }
}
