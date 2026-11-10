package com.fuquan.audit.common.material.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import com.fuquan.audit.common.material.application.port.MaterialExtractionLedger.Entry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Clock;
import java.util.*;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Persist each acknowledged unit before issuing the next external call. */
@Service @Primary @Profile({"local-dev & local-ai","dev29","customer"})
public class DurableMaterialExtractionService implements MaterialFieldAiPort {
    private static final String STRATEGY="material-units-v1/core1600/context120/max6";
    private final MaterialExtractionLedger ledger;
    private final MaterialUnitAiPort gateway;
    private final ObjectMapper json;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public DurableMaterialExtractionService(MaterialExtractionLedger ledger,MaterialUnitAiPort gateway,ObjectMapper json) {
        this(ledger,gateway,json,Clock.systemUTC());
    }
    DurableMaterialExtractionService(MaterialExtractionLedger ledger,MaterialUnitAiPort gateway,ObjectMapper json,Clock clock) {
        this.ledger=ledger;this.gateway=gateway;this.json=json;this.clock=clock;
    }
    record Piece(Segment segment,int coreStart,int coreEnd) {}
    @Override public Result extract(String kind,String materialId,String sha,List<Segment> source) {
        try(var admission=gateway.admit()){return extractAdmitted(kind,materialId,sha,source);}
    }
    @Override public Optional<Result> acknowledgedResult(String kind,String materialId,String sha,List<Segment> source) {
        // Procurement can publish individually proven facts while the document remains
        // incomplete. Other consumers retain their existing all-or-nothing contract.
        if(!"PROCUREMENT_AUTO".equals(kind))return Optional.empty();
        if(materialId==null||materialId.isBlank()||materialId.length()>36||sha==null||!sha.matches("[a-f0-9]{64}")
                ||source==null||source.isEmpty()||source.size()>10000
                ||source.stream().mapToLong(s->s.text().codePointCount(0,s.text().length())).sum()>192000
                ||source.stream().map(Segment::locator).distinct().count()!=source.size())throw failure("INVALID_SOURCE");
        var plan=plan(List.copyOf(source));
        if(plan.isEmpty()||plan.size()>512)throw failure("UNIT_LIMIT");
        UUID id=UUID.nameUUIDFromBytes(("material-extraction/"+kind+"/"+materialId).getBytes(StandardCharsets.UTF_8));
        var entry=ledger.find(id).orElse(null);
        if(entry==null)return Optional.empty();
        if(!hash(encode(List.of(STRATEGY,kind,materialId,sha,source))).equals(entry.sourceHash())||entry.unitCount()!=plan.size())throw failure("SOURCE_DRIFT");
        // Timeout termination deliberately purges unpublished candidates. Neither an
        // active executor nor a missing/unknown commit is evidence of acknowledgement.
        if(!"UNKNOWN".equals(entry.status())||!Set.of("UNACKNOWLEDGED_CALL","EXECUTOR_LOST").contains(Objects.toString(entry.reasonCode(),"")))return Optional.empty();
        var results=decode(entry.resultsJson());
        if(entry.nextUnit()!=results.size()||entry.nextUnit()>plan.size())throw failure("CHECKPOINT_INVALID");
        for(int i=0;i<results.size();i++)results.set(i,validate(results.get(i),plan.get(i),i));
        return results.isEmpty()?Optional.empty():Optional.of(finalResult(kind,results));
    }
    private Result extractAdmitted(String kind,String materialId,String sha,List<Segment> source) {
        if(!Set.of("BUDGET","PROCUREMENT","PROCUREMENT_REVIEW","PROCUREMENT_AUTO").contains(kind)||materialId==null||materialId.isBlank()||materialId.length()>36
                ||sha==null||!sha.matches("[a-f0-9]{64}"))throw failure("INVALID_SOURCE");
        source=List.copyOf(source);
        if(source.isEmpty()||source.size()>10000||source.stream().mapToLong(s->s.text().codePointCount(0,s.text().length())).sum()>192000
                ||source.stream().map(Segment::locator).distinct().count()!=source.size())throw failure("INVALID_SOURCE");
        var plan=plan(source);
        if(plan.isEmpty()||plan.size()>512)throw failure("UNIT_LIMIT");
        String fingerprint=hash(encode(List.of(STRATEGY,kind,materialId,sha,source)));
        UUID id=UUID.nameUUIDFromBytes(("material-extraction/"+kind+"/"+materialId).getBytes(StandardCharsets.UTF_8));
        Entry entry=ledger.find(id).orElse(null);
        if(entry==null) {
            String policy=gateway.policy();
            if(policy==null||!policy.matches("[a-f0-9]{64}"))throw failure("INVALID_POLICY");
            try {ledger.insert(new Entry(id,fingerprint,policy,plan.size(),0,0,0,"READY",null,Instant.now(),"[]",null));}
            catch(org.springframework.dao.DataIntegrityViolationException concurrent) {
                // DM8 may classify unique-key violations as DataIntegrityViolation,
                // not DuplicateKeyException. Prove the winning row; never swallow
                // another integrity error when no durable operation exists.
                if(ledger.find(id).isEmpty())throw concurrent;
            }
            entry=ledger.find(id).orElseThrow(()->failure("LEDGER_UNKNOWN"));
        }
        if(!fingerprint.equals(entry.sourceHash())||entry.unitCount()!=plan.size())throw failure("SOURCE_DRIFT");
        if(entry.status().equals("CALLING")&&entry.deadlineAt()!=null&&!clock.instant().isBefore(entry.deadlineAt())) {
            if(!ledger.terminate(id,entry.token(),"MATERIAL_TIME_LIMIT",clock.instant()))throw failure("TERMINATION_FENCE_LOST");
            throw new MaterialExtractionTerminated("MATERIAL_TIME_LIMIT");
        }
        if(entry.status().equals("UNKNOWN")&&Set.of("MATERIAL_CALL_TIMEOUT","MATERIAL_TIME_LIMIT").contains(Objects.toString(entry.reasonCode(),"")))
            throw new MaterialExtractionTerminated(entry.reasonCode());
        var results=decode(entry.resultsJson());
        if(entry.nextUnit()!=results.size()||entry.nextUnit()>plan.size())throw failure("CHECKPOINT_INVALID");
        for(int i=0;i<results.size();i++)results.set(i,validate(results.get(i),plan.get(i),i));
        if(entry.status().equals("COMPLETE")) {
            if(entry.nextUnit()!=plan.size())throw failure("CHECKPOINT_INVALID");
            return finalResult(kind,results);
        }
        if(entry.status().equals("UNKNOWN"))throw failure("CALL_OUTCOME_UNKNOWN");
        if(entry.status().equals("CALLING")) {
            // Never take over an external call. A stale executor may still return;
            // fencing UNKNOWN prevents its late acknowledgement from publishing.
            if(entry.updatedAt().plusSeconds(1100).isBefore(Instant.now()))
                ledger.unknown(id,entry.token(),"EXECUTOR_LOST",Instant.now());
            throw failure("CALL_OUTCOME_UNKNOWN_OR_IN_PROGRESS");
        }
        if(!entry.status().equals("READY"))throw failure("CHECKPOINT_INVALID");
        if(entry.usedMillis()>=900000) {
            if(!ledger.terminate(id,null,"MATERIAL_TIME_LIMIT",clock.instant()))throw failure("TERMINATION_FENCE_LOST");
            throw new MaterialExtractionTerminated("MATERIAL_TIME_LIMIT");
        }
        if(!entry.policyHash().equals(gateway.policy()))throw failure("POLICY_DRIFT");
        ledger.startDeadline(id,clock.instant().plusMillis(Math.max(0,900000-entry.usedMillis())));
        entry=ledger.find(id).orElseThrow(()->failure("LEDGER_UNKNOWN"));
        for(int index=entry.nextUnit();index<plan.size();index++) {
            long remaining=Math.min(900000-entry.usedMillis(),java.time.Duration.between(clock.instant(),entry.deadlineAt()).toMillis());
            if(remaining<=0) {
                if(!ledger.terminate(id,null,"MATERIAL_TIME_LIMIT",clock.instant()))throw failure("TERMINATION_FENCE_LOST");
                throw new MaterialExtractionTerminated("MATERIAL_TIME_LIMIT");
            }
            if(entry.calls()>=512)throw failure("CUMULATIVE_BUDGET_EXHAUSTED");
            String token=UUID.randomUUID().toString();
            if(!ledger.begin(id,index,token,Instant.now()))throw failure("CONCURRENT_OR_UNKNOWN_CALL");
            org.slf4j.LoggerFactory.getLogger(getClass()).info(
                    "MATERIAL_EXTRACTION_UNIT operationId={} unitIndex={} unitCount={} priorCalls={} priorUsedMillis={} outcome=START",
                    id,index+1,plan.size(),entry.calls(),entry.usedMillis());
            long started=System.nanoTime();
            try {
                var unit=plan.get(index);
                var reply=gateway.extractUnit(kind,materialId,sha,unit.stream().map(Piece::segment).toList(),
                        entry.policyHash(),512-entry.calls(),remaining);
                if(!clock.instant().isBefore(entry.deadlineAt()))throw new MaterialExtractionTerminated("MATERIAL_TIME_LIMIT");
                if(reply==null||reply.result()==null||reply.callCount()<0||reply.callCount()>512-entry.calls())throw failure("INVALID_UNIT_RESPONSE");
                var validated=validate(reply.result(),unit,index);
                results.add(validated);
                combine(results); // Fail before persisting a mixed model or oversized candidate set.
                long elapsed=Math.max(1,(System.nanoTime()-started)/1_000_000);
                String value=encode(results);
                if(value.length()>2_000_000)throw failure("CHECKPOINT_SIZE_LIMIT");
                if(!ledger.acknowledge(id,index,token,reply.callCount(),elapsed,value,clock.instant())) {
                    if(!clock.instant().isBefore(entry.deadlineAt()))throw new MaterialExtractionTerminated("MATERIAL_TIME_LIMIT");
                    throw failure("CALL_FENCE_OR_BUDGET_LOST");
                }
                org.slf4j.LoggerFactory.getLogger(getClass()).info(
                        "MATERIAL_EXTRACTION_UNIT operationId={} unitIndex={} callCount={} elapsedMillis={} outcome=ACKNOWLEDGED",
                        id,index+1,reply.callCount(),elapsed);
                entry=ledger.find(id).orElseThrow(()->failure("LEDGER_UNKNOWN"));
            } catch(RuntimeException failure) {
                if(failure instanceof MaterialExtractionTerminated ended) {
                    if(!ledger.terminate(id,token,ended.reasonCode(),clock.instant()))throw failure("TERMINATION_FENCE_LOST");
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                        "MATERIAL_EXTRACTION_UNIT operationId={} unitIndex={} outcome=TERMINATED reasonCode={}",id,index+1,ended.reasonCode());
                    throw ended;
                }
                // Includes response loss, invalid evidence and uncertain commit.
                // Successful earlier units remain available; this unit is not resent.
                try {ledger.unknown(id,token,"UNACKNOWLEDGED_CALL",Instant.now());}
                catch(RuntimeException markingFailure) {failure.addSuppressed(markingFailure);}
                org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                        "MATERIAL_EXTRACTION_UNIT operationId={} unitIndex={} outcome=UNKNOWN reasonCode=UNACKNOWLEDGED_CALL",id,index+1);
                if(failure instanceof MaterialExtractionUnknown)throw failure;
                throw new MaterialExtractionUnknown("UNACKNOWLEDGED_CALL",failure);
            }
        }
        return finalResult(kind,results);
    }
    private Result finalResult(String kind,List<Result> values) {
        var result=combine(values);
        if(!kind.equals("PROCUREMENT")||result.rows().isEmpty())return result;
        // One material may span many transport units. Do not expose unit-local
        // row keys as projects; downstream normalizer rejects conflicting identities.
        var facts=new LinkedHashSet<Fact>();
        result.rows().forEach(row->facts.addAll(row.facts()));
        return new Result(List.of(new Row("material",List.copyOf(facts))),result.model(),result.promptVersion());
    }
    private Result validate(Result result,List<Piece> pieces,int index) {
        if(result.rows()==null||result.model()==null||result.model().isBlank()
                ||result.promptVersion()==null||result.promptVersion().isBlank())throw failure("INVALID_UNIT_RESPONSE");
        if(result.issues().size()>2000)throw failure("RESULT_LIMIT");
        for(var issue:result.issues())if(pieces.stream().noneMatch(p->p.segment().locator().equals(issue.locator())))
            throw failure("FOREIGN_UNIT_SOURCE");
        var rows=new ArrayList<Row>();
        for(var row:result.rows()) {
            if(row==null||row.facts()==null)throw failure("INVALID_UNIT_RESPONSE");
            var facts=new ArrayList<Fact>();
            for(var fact:row.facts()) {
                if(fact==null||fact.value()==null||fact.value().isBlank()||fact.excerpt()==null||fact.excerpt().isBlank()
                        ||!fact.excerpt().contains(fact.value()))throw failure("UNGROUNDED_UNIT");
                var piece=pieces.stream().filter(p->p.segment().locator().equals(fact.locator())).findFirst().orElseThrow(()->failure("FOREIGN_UNIT_SOURCE"));
                String text=piece.segment().text();
                int at=text.indexOf(fact.excerpt());
                if(at<0)throw failure("UNGROUNDED_UNIT");
                boolean core=false;
                for(;at>=0;at=text.indexOf(fact.excerpt(),at+1))if(at<piece.coreEnd()&&at+fact.excerpt().length()>piece.coreStart())core=true;
                if(core)facts.add(fact);
            }
            if(!facts.isEmpty())rows.add(new Row(index+":"+rows.size(),List.copyOf(facts)));
        }
        return new Result(List.copyOf(rows),result.model(),result.promptVersion(),result.issues());
    }
    private Result combine(List<Result> values) {
        var models=new HashSet<String>();var prompts=new HashSet<String>();var rows=new ArrayList<Row>();
        var issues=new LinkedHashSet<ExtractionIssue>();
        for(var result:values) {
            if(!result.model().equals("EXPLICIT_LABELS"))models.add(result.model());
            prompts.add(result.promptVersion());rows.addAll(result.rows());issues.addAll(result.issues());
        }
        if(models.size()>1||prompts.size()>1)throw failure("MODEL_OR_PROMPT_DRIFT");
        if(rows.size()>10000||issues.size()>2000||rows.stream().mapToLong(r->r.facts().size()).sum()>2000)throw failure("RESULT_LIMIT");
        return new Result(List.copyOf(rows),models.stream().findFirst().orElse("EXPLICIT_LABELS"),prompts.stream().findFirst().orElse(STRATEGY),List.copyOf(issues));
    }
    static List<List<Piece>> plan(List<Segment> source) {
        var units=new ArrayList<List<Piece>>();var current=new ArrayList<Piece>();int size=0;
        for(var segment:source) {
            if(segment.locator()==null||segment.locator().isBlank()||segment.locator().length()>300||segment.text()==null||segment.text().isEmpty())throw failure("INVALID_SOURCE");
            String text=segment.text();
            for(int start=0;start<text.length();) {
                int end=text.offsetByCodePoints(start,Math.min(1600,text.codePointCount(start,text.length())));
                if(end<text.length()) {int newline=text.lastIndexOf('\n',end-1);if(newline>=start)end=newline+1;}
                int left=text.offsetByCodePoints(start,-Math.min(120,text.codePointCount(0,start)));
                int right=text.offsetByCodePoints(end,Math.min(120,text.codePointCount(end,text.length())));
                if(start==0||text.charAt(start-1)=='\n')left=start;
                if(end==text.length()||text.charAt(end-1)=='\n')right=end;
                var piece=new Piece(new Segment(segment.locator(),text.substring(left,right)),start-left,end-left);
                if(!current.isEmpty()&&(size+piece.segment().text().length()>1800||current.size()>=6
                        ||current.stream().anyMatch(p->p.segment().locator().equals(segment.locator())))) {
                    units.add(List.copyOf(current));current.clear();size=0;
                }
                current.add(piece);size+=piece.segment().text().length();start=end;
            }
        }
        if(!current.isEmpty())units.add(List.copyOf(current));return List.copyOf(units);
    }
    private List<Result> decode(String value) {try{return new ArrayList<>(json.readValue(value,new TypeReference<List<Result>>(){}));}
        catch(Exception invalid){throw failure("CHECKPOINT_INVALID");}}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception invalid){throw failure("CHECKPOINT_INVALID");}}
    private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static MaterialExtractionUnknown failure(String code){return new MaterialExtractionUnknown(code);}
}
