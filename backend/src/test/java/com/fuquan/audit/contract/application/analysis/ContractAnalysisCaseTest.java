package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.infra.memory.analysis.InMemoryContractAnalysisRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisCaseTest {
    static final Instant NOW=Instant.parse("2026-10-27T14:00:00Z");
    static ContractAnalysisCase sample(){
        UUID p=UUID.randomUUID();
        return new ContractAnalysisCase(identity("owner",p),p,"owner",sha256("manifest"),State.QUEUED,0,null,null,0,NOW,NOW.plusSeconds(1800),
                List.of(new Source(UUID.randomUUID(),"合成测试合同.docx","a".repeat(64),100,SourceState.WAITING,null,null,null,null)),List.of(),null);
    }
    @Test void independentCaseNeedsNoFormalContractOrBusinessFields(){
        var c=sample();assertThat(c.status()).isEqualTo(State.QUEUED);assertThat(c.terminal()).isFalse();
        assertThat(c.sources().get(0).parseResultId()).isNull();
        assertThat(identity("other",c.projectId())).isNotEqualTo(c.id());
    }
    @Test void duplicateAdmissionCannotOverwriteFirstManifest(){
        var r=new InMemoryContractAnalysisRepository();var c=sample();r.insert(c);
        assertThatThrownBy(()->r.insert(c)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(r.find(c.id())).contains(c);
    }
    @Test void concurrentWorkersHaveOnlyOneLeaseOwner() throws Exception {
        var r=new InMemoryContractAnalysisRepository();var c=sample();r.insert(c);
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(8);
        try {var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<8;i++)futures.add(pool.submit(()->{start.await();return r.replace(c,c.claim(UUID.randomUUID().toString(),NOW),NOW);}));
            start.countDown();int successes=0;for(var f:futures)if(f.get(5,TimeUnit.SECONDS))successes++;assertThat(successes).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void expiredAndReplacedExecutorsCannotPublishEvenWithPreviouslyValidOutput(){
        var r=new InMemoryContractAnalysisRepository();var original=sample();r.insert(original);
        var first=original.claim("first",NOW);assertThat(r.replace(original,first,NOW)).isTrue();
        var late=NOW.plusSeconds(241);
        assertThat(r.replace(first,first.release(State.COMPLETED,first.sources(),List.of(),null),late)).isFalse();
        var recovered=first.claim("second",late);assertThat(r.replace(first,recovered,late)).isTrue();
        assertThat(r.replace(first,first.release(State.FAILED,first.sources(),List.of(),"STALE"),late)).isFalse();
        assertThat(r.find(original.id()).orElseThrow().leaseToken()).isEqualTo("second");
    }
    @Test void unknownIsTerminalAndNotSilentlyResent(){
        var r=new InMemoryContractAnalysisRepository();var c=sample();r.insert(c);var claimed=c.claim("worker",NOW);r.replace(c,claimed,NOW);
        var unknown=claimed.release(State.UNKNOWN,claimed.sources(),List.of(),"EXTERNAL_OUTCOME_UNKNOWN");assertThat(r.replace(claimed,unknown,NOW)).isTrue();
        assertThat(r.pending(NOW.plusSeconds(4000),10)).isEmpty();assertThat(unknown.terminal()).isTrue();
    }
    @Test void readyFileRequiresImmutableParseTuple(){
        assertThatThrownBy(()->new Source(UUID.randomUUID(),"合同.pdf","a".repeat(64),1,SourceState.READY,null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void readAndHistoryArePureAndOwnerScoped(){
        var r=new InMemoryContractAnalysisRepository();var c=sample();r.insert(c);
        assertThat(r.recent("other",0,20)).isEmpty();assertThat(r.recent("owner",0,20)).containsExactly(c);
        assertThat(r.find(c.id()).orElseThrow().version()).isZero();
    }
    @Test void frozenSourceCannotBeReplacedOrDropped(){
        var c=sample().claim("worker",NOW);var s=c.sources().get(0);
        var replaced=new Source(s.fileId(),s.name(),"b".repeat(64),s.size(),s.status(),null,null,null,null);
        assertThatThrownBy(()->verifyTransition(c,c.checkpoint(List.of(replaced),List.of()))).hasMessage("FROZEN_SOURCE_MUTATION");
        assertThatThrownBy(()->verifyTransition(c,c.checkpoint(List.of(),List.of()))).hasMessage("SEALED_MANIFEST_MUTATION");
    }
    @Test void terminalStepsAreImmutableAcrossCheckpoints(){
        var c=sample().claim("worker",NOW);var s=c.sources().get(0);
        var step=ContractAnalysisPlan.step("one",s,"FILE",0,2).running(NOW).finish(StepState.UNKNOWN,null,"MODEL_OUTCOME_UNKNOWN",NOW);
        var before=c.checkpoint(c.sources(),List.of(step));
        var changed=step.finish(StepState.FAILED,null,"changed",NOW);
        assertThatThrownBy(()->verifyTransition(before,before.checkpoint(before.sources(),List.of(changed)))).hasMessage("FROZEN_STEP_MUTATION");
    }
}
