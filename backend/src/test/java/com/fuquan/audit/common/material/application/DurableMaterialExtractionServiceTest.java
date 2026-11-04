package com.fuquan.audit.common.material.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import com.fuquan.audit.common.material.application.port.MaterialExtractionLedger.Entry;
import com.fuquan.audit.integration.material.memory.InMemoryMaterialExtractionLedger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DurableMaterialExtractionServiceTest {
    @Test void dm8GenericUniqueViolationIsRecoveredOnlyWhenWinningRecordCanBeProven() {
        var winner=new InMemoryMaterialExtractionLedger(){@Override public synchronized void insert(Entry e){
            super.insert(e);throw new org.springframework.dao.DataIntegrityViolationException("synthetic duplicate");}};
        assertThat(service(winner,new Gateway()).extract("BUDGET",ID,SHA,List.of(source.get(0))).rows()).hasSize(1);
        var noWinner=new InMemoryMaterialExtractionLedger(){@Override public synchronized void insert(Entry e){
            throw new org.springframework.dao.DataIntegrityViolationException("synthetic constraint failure");}};
        var gateway=new Gateway();
        assertThatThrownBy(()->service(noWinner,gateway).extract("BUDGET",ID,SHA,source))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(gateway.calls).isZero();
    }
    static final String POLICY="a".repeat(64),SHA="b".repeat(64),ID="synthetic-material";
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final List<Segment> source=List.of(new Segment("p1","甲".repeat(1500)),new Segment("p2","乙".repeat(1500)));
    UUID operation(String kind){return UUID.nameUUIDFromBytes(("material-extraction/"+kind+"/"+ID).getBytes(StandardCharsets.UTF_8));}
    class Gateway implements MaterialUnitAiPort {
        int calls;String policy=POLICY;boolean fail;
        public String policy(){return policy;}
        public Reply extractUnit(String kind,String id,String sha,List<Segment> segments,String policy,int remaining,long millis){
            calls++;if(fail)throw new IllegalStateException("unknown transport outcome");
            return reply(segments);
        }
        Reply reply(List<Segment> segments){var s=segments.get(0);return new Reply(new Result(List.of(new Row("same",List.of(
            new Fact("subjectName",s.text(),s.locator(),s.text())))),"test","v6"),1);}
    }
    DurableMaterialExtractionService service(MaterialExtractionLedger ledger,MaterialUnitAiPort gateway){return new DurableMaterialExtractionService(ledger,gateway,json);}
    static class Crash extends Error {}
    class CrashAfterCommit extends InMemoryMaterialExtractionLedger {
        boolean crash=true;
        @Override public synchronized boolean acknowledge(UUID id,int index,String token,int calls,long elapsed,String results,Instant now) {
            boolean saved=super.acknowledge(id,index,token,calls,elapsed,results,now);
            if(saved&&crash){crash=false;throw new Crash();}return saved;
        }
    }
    @Test void partialEvidenceWarningsPersistAcrossRecoveryWithoutReissuingKnownCalls() {
        var ledger=new InMemoryMaterialExtractionLedger();
        var gateway=new Gateway(){@Override Reply reply(List<Segment> segments){
            var value=super.reply(segments);return new Reply(new Result(value.result().rows(),"test","v7",
                List.of(new ExtractionIssue("declaredAmount",segments.get(0).locator(),"EVIDENCE_UNVERIFIED"))),1);
        }};
        var first=service(ledger,gateway).extract("BUDGET",ID,SHA,source);
        assertThat(first.issues()).hasSize(2);gateway.fail=true;
        assertThat(service(ledger,gateway).extract("BUDGET",ID,SHA,source)).isEqualTo(first);
        assertThat(gateway.calls).isEqualTo(2);
    }
    @Test void restartReusesAcknowledgedUnitAndCompletedResultEvenWhenProviderIsUnavailable() {
        var ledger=new CrashAfterCommit();var first=new Gateway();
        assertThatThrownBy(()->service(ledger,first).extract("BUDGET",ID,SHA,source)).isInstanceOf(Crash.class);
        assertThat(ledger.find(operation("BUDGET")).orElseThrow().nextUnit()).isEqualTo(1);
        var next=new Gateway();var result=service(ledger,next).extract("BUDGET",ID,SHA,source);
        assertThat(first.calls).isEqualTo(1);assertThat(next.calls).isEqualTo(1);
        assertThat(result.rows()).hasSize(2);
        next.fail=true;next.policy="changed";
        assertThat(service(ledger,next).extract("BUDGET",ID,SHA,source)).isEqualTo(result);
        assertThat(next.calls).isEqualTo(1);
    }
    @Test void unknownCallIsNotReissuedOnRetryOrRestartAndNeverPublishesPartialFacts() {
        var ledger=new InMemoryMaterialExtractionLedger();var gateway=new Gateway(){
            @Override public Reply extractUnit(String kind,String id,String sha,List<Segment> segments,String policy,int remaining,long millis){
                if(calls==1){calls++;throw new IllegalStateException("lost response");}
                return super.extractUnit(kind,id,sha,segments,policy,remaining,millis);
            }};
        assertThatThrownBy(()->service(ledger,gateway).extract("BUDGET",ID,SHA,source)).hasMessageContaining("UNACKNOWLEDGED_CALL");
        var saved=ledger.find(operation("BUDGET")).orElseThrow();
        assertThat(saved.status()).isEqualTo("UNKNOWN");assertThat(saved.nextUnit()).isEqualTo(1);
        assertThatThrownBy(()->service(ledger,gateway).extract("BUDGET",ID,SHA,source)).hasMessageContaining("CALL_OUTCOME_UNKNOWN");
        assertThat(gateway.calls).isEqualTo(2);
    }
    @Test void sourcePolicyAndActualModelCannotSilentlyChangeAcrossRecovery() {
        var ledger=new CrashAfterCommit();var gateway=new Gateway();
        assertThatThrownBy(()->service(ledger,gateway).extract("BUDGET",ID,SHA,source)).isInstanceOf(Crash.class);
        assertThatThrownBy(()->service(ledger,gateway).extract("BUDGET",ID,"c".repeat(64),source)).hasMessageContaining("SOURCE_DRIFT");
        gateway.policy="c".repeat(64);
        assertThatThrownBy(()->service(ledger,gateway).extract("BUDGET",ID,SHA,source)).hasMessageContaining("POLICY_DRIFT");
        var changed=new Gateway(){@Override Reply reply(List<Segment> s){var r=super.reply(s);return new Reply(new Result(r.result().rows(),"other-model","v6"),1);}};
        assertThatThrownBy(()->service(ledger,changed).extract("BUDGET",ID,SHA,source)).hasMessageContaining("MODEL_OR_PROMPT_DRIFT");
        assertThat(ledger.find(operation("BUDGET")).orElseThrow().status()).isEqualTo("UNKNOWN");
    }
    @Test void cumulativeBudgetDoesNotResetAfterRestart() {
        var initial=new CrashAfterCommit();var first=new Gateway();
        assertThatThrownBy(()->service(initial,first).extract("BUDGET",ID,SHA,source)).isInstanceOf(Crash.class);
        var saved=initial.find(operation("BUDGET")).orElseThrow();
        for(boolean calls:List.of(true,false)) {
            var ledger=new InMemoryMaterialExtractionLedger();
            ledger.insert(new Entry(saved.id(),saved.sourceHash(),saved.policyHash(),saved.unitCount(),saved.nextUnit(),
                calls?512:saved.calls(),calls?saved.usedMillis():900000,"READY",null,Instant.now(),saved.resultsJson(),null));
            var next=new Gateway();
            assertThatThrownBy(()->service(ledger,next).extract("BUDGET",ID,SHA,source)).hasMessageContaining(calls?"CUMULATIVE_BUDGET_EXHAUSTED":"MATERIAL_TIME_LIMIT");
            assertThat(next.calls).isZero();
        }
    }
    @Test void concurrentExecutorsCannotIssueTwoCallsForOneUnit() throws Exception {
        var ledger=new InMemoryMaterialExtractionLedger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var externalCalls=new AtomicInteger();
        var gateway=new Gateway(){@Override public Reply extractUnit(String kind,String id,String sha,List<Segment> s,String policy,int limit,long time){
            externalCalls.incrementAndGet();entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("test timeout");}catch(InterruptedException e){throw new RuntimeException(e);}return reply(s);}};
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->service(ledger,gateway).extract("BUDGET",ID,SHA,List.of(source.get(0))));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service(ledger,gateway).extract("BUDGET",ID,SHA,List.of(source.get(0)))).hasMessageContaining("UNKNOWN_OR_IN_PROGRESS");
            release.countDown();assertThat(first.get(5,TimeUnit.SECONDS).rows()).hasSize(1);assertThat(externalCalls).hasValue(1);
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void staleTokenAndLateExecutorCannotAcknowledgeAnUnknownCall() {
        var ledger=new InMemoryMaterialExtractionLedger();var id=UUID.randomUUID();
        ledger.insert(new Entry(id,SHA,POLICY,1,0,0,0,"READY",null,Instant.EPOCH,"[]",null));
        assertThat(ledger.begin(id,0,"owner",Instant.EPOCH)).isTrue();
        assertThat(ledger.acknowledge(id,0,"foreign",1,1,"[]",Instant.now())).isFalse();
        ledger.unknown(id,"owner","EXECUTOR_LOST",Instant.now());
        assertThat(ledger.acknowledge(id,0,"owner",1,1,"[]",Instant.now())).isFalse();
        assertThat(ledger.begin(id,0,"new-owner",Instant.now())).isFalse();
    }
    @Test void unicodePlanPreservesEveryCoreCharacterAndDoesNotCreateSurrogateHalves() {
        String text="🙂甲".repeat(2200)+"\n最后一行";
        var units=DurableMaterialExtractionService.plan(List.of(new Segment("p1",text)));
        var rebuilt=new StringBuilder();
        for(var unit:units)for(var piece:unit) {
            String core=piece.segment().text().substring(piece.coreStart(),piece.coreEnd());
            assertThat(Character.isLowSurrogate(core.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(core.charAt(core.length()-1))).isFalse();rebuilt.append(core);
        }
        assertThat(rebuilt.toString()).isEqualTo(text);
    }
    @Test void procurementUnitsRemainOneMaterialAndForeignEvidenceIsRejected() {
        var result=service(new InMemoryMaterialExtractionLedger(),new Gateway()).extract("PROCUREMENT",ID,SHA,source);
        assertThat(result.rows()).hasSize(1);assertThat(result.rows().get(0).facts()).hasSize(2);
        var foreign=new Gateway(){@Override Reply reply(List<Segment> s){return new Reply(new Result(List.of(new Row("r",List.of(
            new Fact("subjectName","甲","other-page","甲")))),"test","v6"),1);}};
        assertThatThrownBy(()->service(new InMemoryMaterialExtractionLedger(),foreign).extract("BUDGET",ID,SHA,source)).hasMessageContaining("FOREIGN_UNIT_SOURCE");
    }
    @Test void timeoutPurgesPartialCandidatesFencesLateRepliesAndNeverAutomaticallyCallsAgain() {
        var ledger=new InMemoryMaterialExtractionLedger();var gateway=new Gateway(){
            @Override public Reply extractUnit(String kind,String id,String sha,List<Segment> s,String policy,int limit,long time){
                if(calls==1){calls++;throw new MaterialExtractionTerminated("MATERIAL_CALL_TIMEOUT");}
                return super.extractUnit(kind,id,sha,s,policy,limit,time);
            }};
        assertThatThrownBy(()->service(ledger,gateway).extract("BUDGET",ID,SHA,source)).isInstanceOf(MaterialExtractionTerminated.class);
        var ended=ledger.find(operation("BUDGET")).orElseThrow();
        assertThat(ended.status()).isEqualTo("UNKNOWN"); // The upstream outcome is still unknown.
        assertThat(ended.reasonCode()).isEqualTo("MATERIAL_CALL_TIMEOUT");
        assertThat(ended.resultsJson()).isEqualTo("[]");assertThat(ended.token()).isNull();
        assertThat(ledger.acknowledge(ended.id(),ended.nextUnit(),"late",1,1,"[]",Instant.now())).isFalse();
        assertThatThrownBy(()->service(ledger,gateway).extract("BUDGET",ID,SHA,source)).isInstanceOf(MaterialExtractionTerminated.class);
        assertThat(gateway.calls).isEqualTo(2);
    }
    static final class TestClock extends java.time.Clock {
        Instant now=Instant.now(); public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        public java.time.Clock withZone(java.time.ZoneId zone){return this;} public Instant instant(){return now;}
    }
    @Test void entireMaterialDeadlineSurvivesRestartAndLateResponseCannotPublish() {
        var clock=new TestClock();var ledger=new CrashAfterCommit();var gateway=new Gateway();
        var start=clock.now;
        assertThatThrownBy(()->new DurableMaterialExtractionService(ledger,gateway,json,clock).extract("BUDGET",ID,SHA,source)).isInstanceOf(Crash.class);
        assertThat(ledger.find(operation("BUDGET")).orElseThrow().deadlineAt()).isEqualTo(start.plusSeconds(900));
        clock.now=start.plusSeconds(901);
        assertThatThrownBy(()->new DurableMaterialExtractionService(ledger,gateway,json,clock).extract("BUDGET",ID,SHA,source)).isInstanceOf(MaterialExtractionTerminated.class).hasMessageContaining("MATERIAL_TIME_LIMIT");
        assertThat(gateway.calls).isEqualTo(1);assertThat(ledger.find(operation("BUDGET")).orElseThrow().resultsJson()).isEqualTo("[]");
        var late=new Gateway(){@Override public Reply extractUnit(String k,String id,String sha,List<Segment>s,String p,int calls,long time){clock.now=clock.now.plusSeconds(901);return reply(s);}};
        var fresh=new InMemoryMaterialExtractionLedger();
        assertThatThrownBy(()->new DurableMaterialExtractionService(fresh,late,json,clock).extract("PROCUREMENT",ID,SHA,List.of(source.get(0)))).isInstanceOf(MaterialExtractionTerminated.class);
        assertThat(fresh.find(operation("PROCUREMENT")).orElseThrow().resultsJson()).isEqualTo("[]");
    }
}
