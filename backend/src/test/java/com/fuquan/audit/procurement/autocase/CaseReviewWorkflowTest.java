package com.fuquan.audit.procurement.autocase;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.common.material.application.port.DocumentTextPort;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.procurement.application.port.ProcurementTenderRuleRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

class CaseReviewWorkflowTest {
    final Clock clock=Clock.systemUTC();final InMemoryAuditCaseRepository repo=new InMemoryAuditCaseRepository();
    final CaseReviewAiPort ai=mock(CaseReviewAiPort.class);final AuditCaseTransactions tx=mock(AuditCaseTransactions.class);
    final MaterialFieldAiPort extractor=mock(MaterialFieldAiPort.class);
    final CurrentUser user=new CurrentUser("case-owner","case-owner","测试",Set.of("AUDITOR"));
    CaseReviewEngine engine;AuditCaseService service;List<Document> docs;
    @BeforeEach void setup(){
        var rules=mock(ProcurementTenderRuleRepository.class);when(rules.findEnabled()).thenReturn(List.of());
        engine=new CaseReviewEngine(ai,rules,clock,1000);
        service=new AuditCaseService(repo,tx,mock(FileStoragePort.class),mock(DocumentTextPort.class),extractor,clock,engine);
        when(tx.save(any(),any(),any())).thenAnswer(call->repo.replace(call.getArgument(0),call.getArgument(1)));
        when(ai.analyze(any())).thenAnswer(call->CaseReviewFixtures.result(call.getArgument(0)));
        docs=List.of(CaseReviewEngineTest.doc("技术需求表","16GB及512GB"),CaseReviewEngineTest.doc("招标文件","综合评分"),
                CaseReviewEngineTest.doc("评审办法","价格30技术45服务20方案5"),CaseReviewEngineTest.doc("澄清答复","付款口径不改变原比例"));
    }
    @AfterEach void close(){engine.close();}
    AuditCase value(String status,Report report){return new AuditCase(UUID.randomUUID(),user.userId(),UUID.randomUUID(),"fp",status,0,"lease",Instant.EPOCH,1,clock.instant(),docs,report);}
    @Test void interruptedModuleIsUnknownAndNeverCalledAgainButNextModuleRuns(){
        var c=value("RUNNING",null);var pre=engine.prepare(c.id(),"AGENT_PRECHECK",docs);var f10=engine.prepare(c.id(),"TENDER_COMPLIANCE",docs);
        var report=CaseFactPolicy.report(c.id(),docs).review("AGENT_PRECHECK",engine.running(engine.pending(pre,docs))).review("TENDER_COMPLIANCE",engine.pending(f10,docs));
        c=new AuditCase(c.id(),c.owner(),c.operationKey(),c.fingerprint(),c.status(),c.version(),c.leaseToken(),c.leaseUntil(),c.attempts(),c.createdAt(),c.documents(),report);
        repo.insert(c);service.process(c.id(),user);
        var actual=repo.find(c.id()).orElseThrow();
        assertThat(actual.report().reviews().get("AGENT_PRECHECK").status()).isEqualTo("UNKNOWN");
        assertThat(actual.report().reviews().get("TENDER_COMPLIANCE").status()).isEqualTo("COMPLETED");
        verify(ai,times(1)).analyze(argThat(r->r.module().equals("TENDER_COMPLIANCE")));verifyNoInteractions(extractor);
        service.process(c.id(),user);verifyNoMoreInteractions(ai);
        assertThat(actual.status()).isNotEqualTo("COMPLETED");
    }
    @Test void oneModuleFailureCannotPreventOtherModuleAndQuoteResult(){
        doThrow(new IllegalStateException("timeout")).when(ai).analyze(argThat(r->r!=null&&r.module().equals("AGENT_PRECHECK")));
        var c=value("QUEUED",null);repo.insert(c);service.process(c.id(),user);
        var actual=repo.find(c.id()).orElseThrow();
        assertThat(actual.report().modules()).containsEntry("AGENT_PRECHECK","UNKNOWN").containsEntry("TENDER_COMPLIANCE","COMPLETED").containsEntry("BLACKLIST","NOT_EXECUTED");
        assertThat(actual.status()).isNotEqualTo("COMPLETED");assertThat(actual.report().quoteStatus()).isEqualTo("DATA_INSUFFICIENT");
    }
    @Test void runningClaimIsSavedBeforeExternalCall(){
        var c=value("QUEUED",null);repo.insert(c);
        doAnswer(call->{var input=(CaseReviewAiPort.Request)call.getArgument(0);var persisted=repo.find(c.id()).orElseThrow();
            assertThat(persisted.report().reviews().get(input.module()).status()).isEqualTo("RUNNING");return CaseReviewFixtures.result(input);}).when(ai).analyze(any());
        service.process(c.id(),user);verify(ai,times(2)).analyze(any());
    }
}
