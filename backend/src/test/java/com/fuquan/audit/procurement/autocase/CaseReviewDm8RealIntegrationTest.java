package com.fuquan.audit.procurement.autocase;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

@EnabledIfEnvironmentVariable(named="AUDIT_CASE_TEST_DB_CONFIRM",matches="ISOLATED_AUTOCASE2")
class CaseReviewDm8RealIntegrationTest {
    @Test void moduleScopeCitationsAndUnknownStateRoundTripRealClob()throws Exception {
        var db=new Dm8AutoCaseRealIntegrationTest();db.setup();
        var d=CaseReviewEngineTest.doc("技术需求表","内存16GB，固态硬盘512GB");
        var ctx=CaseReviewContext.select("TENDER_COMPLIANCE",List.of(d));
        var review=new ModuleReview("UNKNOWN","f".repeat(64),CaseReviewContext.VERSION,"MOCK_DM8_PROTOCOL",Instant.now(),Instant.now(),ctx.scope(),ctx.gaps(),
                List.of(new ReviewCheck("TECHNICAL_REQUIREMENTS","SOURCE_GROUNDED_OBSERVATION","仅测试持久化，不代表语义验收",ctx.evidence())),List.of(),0,"EXECUTOR_LOST_MODEL_OUTCOME_UNKNOWN");
        UUID id=UUID.randomUUID();var report=CaseFactPolicy.report(id,List.of(d)).review("TENDER_COMPLIANCE",review);
        var c=new AuditCase(id,"phase3-jdbc",UUID.randomUUID(),"fp","PARTIAL",0,"lease",Instant.now(),1,Instant.now(),List.of(d),report);
        db.tx.executeWithoutResult(s->db.repository.insert(c));
        var reopened=db.repository.find(id).orElseThrow();
        assertThat(reopened.report().reviews().get("TENDER_COMPLIANCE")).isEqualTo(review);
        assertThat(reopened.documents().get(0).facts()).isEmpty();assertThat(reopened.documents().get(0).segments()).isEqualTo(d.segments());
        var next=c.update("PARTIAL",c.documents(),report);
        db.tx.executeWithoutResult(s->{assertThat(db.repository.replace(c,next)).isTrue();s.setRollbackOnly();});
        assertThat(db.repository.find(id).orElseThrow().version()).isZero();
    }
}
