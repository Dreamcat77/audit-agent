package com.fuquan.audit.budget.application;

import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import com.fuquan.audit.budget.application.ai.BudgetBatchReviewPort;
import com.fuquan.audit.budget.application.ai.BudgetBatchReviewPort.*;
import com.fuquan.audit.common.material.application.port.MaterialExtractionLedger;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Acknowledged review units share the existing durable ledger, without adding a queue or schema. */
@Service
public class BudgetBatchReviewService {
    static final String VERSION="budget-batch-review-v4";
    private final MaterialExtractionLedger ledger;private final BudgetBatchReviewPort ai;private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    public BudgetBatchReviewService(ObjectProvider<MaterialExtractionLedger> ledger,ObjectProvider<BudgetBatchReviewPort> ai,ObjectMapper json) {
        this(ledger.getIfAvailable(),ai.getIfAvailable(),json);
    }
    BudgetBatchReviewService(MaterialExtractionLedger ledger,BudgetBatchReviewPort ai,ObjectMapper json){this.ledger=ledger;this.ai=ai;this.json=json;}
    record Unit(String scope,List<Source> evidence) {}
    record Reviewed(List<Assessment> items,boolean complete) {}
    public Map<UUID,FileResult> review(UUID batchId,List<Input> inputs,Map<UUID,FileResult> existing) {
        var totals=BudgetBatchAmounts.totals(inputs);var checks=BudgetBatchAmounts.checks(totals);
        var semanticChecks=new ArrayList<>(checks);
        existing.values().stream().flatMap(r->r.lines().stream())
                .filter(l->Set.of("OVER_APPROVAL","UNAPPROVED_SUBJECT","MISSING_DECLARATION").contains(l.status()))
                .map(l->new Assessment("SUBJECT_"+l.status(),"INFO",Objects.toString(l.subject(),Objects.toString(l.subjectCode(),"预算科目")),
                        Objects.toString(l.subject(),Objects.toString(l.subjectCode(),"预算科目"))+"："+l.opinion(),"已列入科目差异，不重复输出。",l.sources()))
                .distinct().forEach(semanticChecks::add);
        var semantic=analyze(batchId,inputs,semanticChecks);
        String name=projectName(inputs);var all=new ArrayList<>(checks);all.addAll(semantic.items());
        var result=new LinkedHashMap<UUID,FileResult>();
        for(var entry:existing.entrySet()) {
            var original=entry.getValue();var assessments=new LinkedHashMap<String,Assessment>();
            for(var a:original.assessments())assessments.put(a.code()+"/"+a.explanation(),located(a,inputs));
            // Store the batch projection in the existing immutable file result CLOBs. Read aggregation de-duplicates it.
            for(var a:all)assessments.put(a.code()+"/"+a.explanation(),located(a,inputs));
            var lines=original.lines().stream().map(l->new Line(l.project(),l.projectCode(),l.subject(),l.subjectCode(),l.year(),l.department(),
                    l.declaredAmount(),l.approvalAmount(),l.status(),l.opinion(),l.sources().stream().map(e->locate(e,inputs)).toList())).toList();
            result.put(entry.getKey(),new FileResult(original.status(),original.coverage(),original.summary(),lines,List.copyOf(assessments.values()),name,totals,
                    semantic.complete()?"COMPLETE":"PARTIAL"));
        }
        return result;
    }
    private static Assessment located(Assessment a,List<Input> inputs){return new Assessment(a.code(),a.level(),a.title(),a.explanation(),a.basis(),a.sources().stream().map(e->locate(e,inputs)).toList());}
    private static Evidence locate(Evidence e,List<Input> inputs){return inputs.stream().filter(i->i.taskId().equals(e.taskId())&&i.snapshot()!=null).findFirst().map(i->BudgetSourceText.locate(e,i.snapshot())).orElse(e);}
    static String projectName(List<Input> inputs) {
        var names=new LinkedHashMap<String,Integer>();
        for(var i:inputs)if(i.snapshot()!=null)for(var r:i.snapshot().rows())for(var f:r.facts())if(f.field().equals("projectName")&&!Set.of("REFERENCE","GENERAL").contains(Objects.toString(f.subjectScope(),""))) {
            String name=f.value().strip().replaceFirst("[，,。；;]+$","");
            if(name.length()>3&&!name.matches(".*(?:委托协议书|通知书|审批表|申报表|批复文件|预算表)$"))names.merge(name,1,Integer::sum);
        }
        return names.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("预算材料分析");
    }
    private Reviewed analyze(UUID batchId,List<Input> inputs,List<Assessment> checks) {
        if(ledger==null||ai==null)return new Reviewed(List.of(),false);
        var plan=plan(inputs);if(plan.isEmpty())return new Reviewed(List.of(),false);
        boolean covered=plan.stream().filter(u->u.scope().equals("FRAGMENT")||plan.size()==1&&u.scope().equals("CROSS_FILE")).flatMap(u->u.evidence().stream()).mapToLong(e->e.text().length()).sum()
                ==inputs.stream().filter(i->i.snapshot()!=null).flatMap(i->i.snapshot().segments().stream()).mapToLong(s->s.text().length()).sum()
                &&inputs.stream().allMatch(i->"PARSED".equals(i.status())&&i.snapshot()!=null&&i.snapshot().issues().isEmpty());
        String fingerprint=hash(encode(List.of(VERSION,inputs,checks)));UUID id=UUID.nameUUIDFromBytes((VERSION+"/"+batchId).getBytes(StandardCharsets.UTF_8));
        var entry=ledger.find(id).orElse(null);
        if(entry==null) {
            try{ledger.insert(new MaterialExtractionLedger.Entry(id,fingerprint,hash(VERSION),plan.size(),0,0,0,"READY",null,Instant.now(),"[]",null));}
            catch(org.springframework.dao.DataIntegrityViolationException race){if(ledger.find(id).isEmpty())throw race;}
            entry=ledger.find(id).orElseThrow();
        }
        if(!entry.sourceHash().equals(fingerprint)||!entry.policyHash().equals(hash(VERSION))||entry.unitCount()!=plan.size())throw new IllegalStateException("BUDGET_REVIEW_SOURCE_DRIFT");
        var results=decode(entry.resultsJson());
        if(entry.nextUnit()!=results.size())throw new IllegalStateException("BUDGET_REVIEW_CHECKPOINT_INVALID");
        if(entry.status().equals("CALLING")) {
            if(entry.updatedAt().plusSeconds(190).isBefore(Instant.now()))ledger.unknown(id,entry.token(),"EXECUTOR_LOST",Instant.now());
            return joined(results,false);
        }
        if(entry.status().equals("UNKNOWN"))return joined(results,false);
        if(entry.status().equals("COMPLETE"))return joined(results,covered);
        ledger.startDeadline(id,Instant.now().plusMillis(Math.max(1,900000-entry.usedMillis())));
        entry=ledger.find(id).orElseThrow();
        for(int n=entry.nextUnit();n<plan.size();n++) {
            // Reserve enough wall time for one bounded external call. Do not start a call that can overrun the batch lease.
            if(entry.deadlineAt()==null||Instant.now().plusSeconds(190).isAfter(entry.deadlineAt())){
                String ended=UUID.randomUUID().toString();if(ledger.begin(id,n,ended,Instant.now()))ledger.unknown(id,ended,"REVIEW_TIME_LIMIT",Instant.now());
                return joined(results,false);
            }
            String token=UUID.randomUUID().toString();if(!ledger.begin(id,n,token,Instant.now()))return joined(results,false);
            long started=System.nanoTime();
            try(var span=PipelineDiagnostics.start("BUDGET","BATCH_SEMANTIC_UNIT",batchId,Map.of("unitIndex",n,"unitCount",plan.size(),"inputChars",plan.get(n).evidence().stream().mapToInt(e->e.text().length()).sum()))) {
                try {
                    var unit=plan.get(n);var request=new Request(batchId,hash(encode(unit)),unit.scope(),unit.evidence(),checks.stream().map(Assessment::explanation).limit(20).toList());
                    var response=ai.analyze(request);var validated=validate(response,unit,inputs);results.add(validated);
                    if(Thread.currentThread().isInterrupted()) {ledger.unknown(id,token,"EXECUTOR_LOST",Instant.now());return joined(results.subList(0,results.size()-1),false);}
                    if(!ledger.acknowledge(id,n,token,1,Math.max(1,(System.nanoTime()-started)/1000000),encode(results),Instant.now()))
                        throw new IllegalStateException("BUDGET_REVIEW_ACK_UNCERTAIN");
                    entry=ledger.find(id).orElseThrow();span.success(Map.of("itemCount",validated.items().size()));
                }catch(RuntimeException failure) {
                    span.fail(failure);ledger.unknown(id,token,"UNACKNOWLEDGED_CALL",Instant.now());
                    // Read the acknowledged checkpoint again, never publish the just-failed or uncertain result.
                    return joined(decode(ledger.find(id).orElseThrow().resultsJson()),false);
                }
            }
        }
        return joined(results,covered);
    }
    static List<Unit> plan(List<Input> inputs) {
        var sources=new ArrayList<Source>();
        for(var input:inputs)if(input.snapshot()!=null)for(var segment:input.snapshot().segments())for(int start=0;start<segment.text().length();) {
            int end=Math.min(start+2200,segment.text().length());if(end<segment.text().length()){int newline=segment.text().lastIndexOf('\n',end);if(newline>start+800)end=newline+1;}
            String text=segment.text().substring(start,end);String id="E-"+hash(input.taskId()+"/"+segment.locator()+"/"+start+"/"+text).substring(0,24);
            sources.add(new Source(id,input.taskId(),input.fileName(),segment.locator()+"/record:"+start+":"+end,text));start=end;
        }
        var plan=new ArrayList<Unit>();var group=new ArrayList<Source>();int chars=0;
        for(var s:sources) {
            if(!group.isEmpty()&&(chars+s.text().length()>6500||group.size()==12)){plan.add(new Unit("FRAGMENT",List.copyOf(group)));group.clear();chars=0;}
            group.add(s);chars+=s.text().length();
        }
        if(!group.isEmpty())plan.add(new Unit("FRAGMENT",List.copyOf(group)));
        // Give every uploaded document room in the cross-document pass, not just the first file.
        var cross=new ArrayList<Source>();int remaining=18000;
        var files=inputs.stream().filter(i->i.snapshot()!=null&&!i.snapshot().segments().isEmpty()).toList();
        for(int i=0;i<files.size();i++) {
            UUID task=files.get(i).taskId();int allowance=Math.min(6000,remaining/Math.max(1,files.size()-i)),used=0;
            var own=sources.stream().filter(s->s.taskId().equals(task)).sorted(Comparator.comparingInt((Source s)->score(s.text())).reversed()).toList();
            for(var s:own)if(used+s.text().length()<=allowance&&cross.size()<40){cross.add(s);used+=s.text().length();remaining-=s.text().length();}
        }
        // One long file also needs synthesis across its fragments; file count is not context coverage.
        if(!cross.isEmpty()&&(sources.stream().map(Source::taskId).distinct().count()>1||plan.size()>1)) {
            if(plan.size()==1&&cross.size()==sources.size())plan.set(0,new Unit("CROSS_FILE",plan.get(0).evidence()));
            else plan.add(new Unit("CROSS_FILE",List.copyOf(cross)));
        }
        // Keep call count bounded. Unreviewed units never become a global 'passed' result.
        if(plan.size()>96) {
            var bounded=new ArrayList<>(plan.subList(0,95));
            var last=plan.get(plan.size()-1);bounded.add(last.scope().equals("CROSS_FILE")?last:plan.get(95));
            return List.copyOf(bounded);
        }
        return List.copyOf(plan);
    }
    private static int score(String text){var m=java.util.regex.Pattern.compile("预算|经费|批复|申请|用途|审批|支出|调整|合计|限价|项目名称").matcher(text);int n=0;while(m.find())n++;return n;}
    private Reviewed validate(Result result,Unit unit,List<Input> inputs) {
        if(result==null||result.items()==null||result.items().size()>8)throw new IllegalArgumentException("BUDGET_REVIEW_INVALID_RESPONSE");
        var accepted=new ArrayList<Assessment>();var catalog=new HashMap<String,Source>();unit.evidence().forEach(e->catalog.put(e.id(),e));
        for(var item:result.items()) {
            if(item==null||item.evidenceIds()==null||item.evidenceIds().isEmpty()||item.evidenceIds().size()>6
                    ||!catalog.keySet().containsAll(item.evidenceIds()))continue;
            if(item.code()==null||!Set.of("PURPOSE_SCOPE","APPROVAL_CONDITION","IDENTITY_CONFLICT","CLAUSE_CONFLICT","AMBIGUITY","DUPLICATION_RISK","BUDGET_CONTENT").contains(item.code()))continue;
            if(item.basis()==null||!Set.of("CLAUSE_ISSUE","OBSERVATION").contains(item.basis())||item.title()==null||item.explanation()==null||item.suggestion()==null)continue;
            if(item.title().isBlank()||item.explanation().isBlank()||item.evidenceIds().size()!=new HashSet<>(item.evidenceIds()).size())continue;
            String prose=item.title()+item.explanation()+item.suggestion();
            if(prose.length()>1000||prose.matches("(?s).*(?:projectName|projectNo|purchaserName|字段|Java|checks|facts).*"))continue;
            if(item.basis().equals("CLAUSE_ISSUE")&&prose.matches("(?s).*(?:未上传|未提供|缺少.{0,12}(?:文件|材料)|未见.{0,8}(?:批复|材料)|超预算|超出批复|合计错误|大小写不一致|拆分违规).*"))continue;
            if(item.excerpts()==null||!item.excerpts().keySet().equals(new HashSet<>(item.evidenceIds()))
                    ||item.excerpts().entrySet().stream().anyMatch(e->e.getValue()==null||e.getValue().isBlank()||e.getValue().length()>1200
                    ||catalog.get(e.getKey()).text().indexOf(e.getValue())<0
                    ||catalog.get(e.getKey()).text().indexOf(e.getValue())!=catalog.get(e.getKey()).text().lastIndexOf(e.getValue())))continue;
            String cited=String.join("\n",item.excerpts().values());
            if(item.code().equals("IDENTITY_CONFLICT")&&prose.matches("(?s).*(?:编号|编码).*")) {
                var ids=new LinkedHashSet<String>();
                var matcher=java.util.regex.Pattern.compile("(?:项目编号|项目编码)\\s*[:：]\\s*([A-Za-z0-9][A-Za-z0-9._/-]{1,80})").matcher(cited);
                while(matcher.find())ids.add(matcher.group(1));
                // A conflict requires both operands, not a single genuine but unrelated quote.
                if(ids.size()<2)continue;
                boolean scanned=item.evidenceIds().stream().map(catalog::get)
                        .anyMatch(s->s.locator().startsWith("image/")||s.locator().contains("/ocr"));
                if(scanned&&ids.stream().map(s->s.replace('O','0').replace('o','0')).distinct().count()==1)continue;
            }
            var numbers=java.util.regex.Pattern.compile("[0-9]+(?:[,.][0-9]+)*").matcher(prose);boolean numeric=true;
            while(numbers.find())if(!cited.contains(numbers.group()))numeric=false;
            if(!numeric)continue;
            var refs=item.evidenceIds().stream().distinct().map(catalog::get).map(s->locate(new Evidence(s.taskId(),s.fileName(),"semantic",item.excerpts().get(s.id()),s.locator(),item.excerpts().get(s.id())),inputs)).toList();
            accepted.add(new Assessment("SEMANTIC_"+item.code(),item.basis().equals("CLAUSE_ISSUE")?"WARNING":"INFO",item.title(),item.explanation(),item.suggestion(),refs));
        }
        return new Reviewed(List.copyOf(accepted),result.complete()&&accepted.size()==result.items().size());
    }
    private Reviewed joined(List<Reviewed> results,boolean complete) {
        var unique=new LinkedHashMap<String,Assessment>();
        for(var r:results)for(var a:r.items())unique.put(a.code()+"/"+a.sources().stream().map(e->e.taskId()+"/"+e.start()+"/"+e.excerpt()).sorted().toList(),a);
        return new Reviewed(List.copyOf(unique.values()),complete&&results.stream().allMatch(Reviewed::complete));
    }
    private List<Reviewed> decode(String value){try{return json.readValue(value,json.getTypeFactory().constructCollectionType(ArrayList.class,Reviewed.class));}catch(Exception e){throw new IllegalStateException("BUDGET_REVIEW_CHECKPOINT_INVALID",e);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("BUDGET_REVIEW_SERIALIZATION",e);}}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
