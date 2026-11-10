package com.fuquan.audit.budget.application;

import java.math.BigDecimal;
import java.util.*;
import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;

/** Automatic batch checks use uploaded facts; no generated name or guessed policy is authority. */
final class BudgetBatchChecks {
    private BudgetBatchChecks() {}
    private record Entry(Input input, Row row, String project, String year, String department, BigDecimal amount) {}
    static Map<UUID,FileResult> enrich(List<Input> inputs, Map<UUID,FileResult> results) {
        var findings=new ArrayList<Assessment>();
        var grouped=new LinkedHashMap<List<String>,List<Entry>>();
        for(var input:inputs) if(input.snapshot()!=null && input.status().equals("PARSED")) {
            var heading=new HashMap<String,String>();
            for(String field:List.of("projectCode","projectName","parentProjectName","budgetYear","departmentName")) {
                var values=input.snapshot().rows().stream().flatMap(r->r.facts().stream()).filter(f->f.field().equals(field)).map(Fact::value).distinct().toList();
                if(values.size()==1)heading.put(field,values.get(0));
            }
            for(var row:input.snapshot().rows()) {
                String project=coalesce(one(row,"parentProjectName"),one(row,"projectCode"),heading.get("parentProjectName"),heading.get("projectCode"),one(row,"projectName"),heading.get("projectName"));
                String projectType=coalesce(one(row,"parentProjectName"),heading.get("parentProjectName"))!=null?"parent:":coalesce(one(row,"projectCode"),heading.get("projectCode"))!=null?"code:":"name:";
                var declared=row.facts().stream().filter(f->f.field().equals("declaredAmount")&&!BudgetAmountEvidence.isOtherAmount(f)).toList();
                if(project==null||declared.size()!=1)continue;
                var amount=com.fuquan.audit.common.domain.RmbAmountParser.parse(declared.get(0).value());
                if(amount.isEmpty()||amount.get().signum()<=0)continue;
                String year=coalesce(one(row,"budgetYear"),heading.get("budgetYear")),department=coalesce(one(row,"departmentName"),heading.get("departmentName"));
                var entry=new Entry(input,row,project,year,department,amount.get());
                grouped.computeIfAbsent(List.of(projectType+key(project),key(year),key(department)),k->new ArrayList<>()).add(entry);
            }
        }
        for(var group:grouped.values()) {
            // Exact repeated source facts are not another application. Distinct
            // rows in the same document remain distinct, including equal amounts.
            var unique=new ArrayList<Entry>();
            for(var entry:group) {
                boolean repeated=unique.stream().anyMatch(old->!old.input().taskId().equals(entry.input().taskId())
                        &&signature(old.row()).equals(signature(entry.row())));
                if(!repeated)unique.add(entry);
            }
            {
                BigDecimal threshold=BudgetMaterialSplitAnalysis.ADDITIONAL_APPROVAL_THRESHOLD;
                var small=unique.stream().filter(e->e.amount().compareTo(threshold)<0).toList();
                var total=small.stream().map(Entry::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
                if(small.size()<2||total.compareTo(threshold)<0)continue;
                var sources=small.stream().flatMap(e->e.row().facts().stream().filter(f->Set.of("declaredAmount","projectName","projectCode","parentProjectName","subjectName","applicationNo","applicationDate").contains(f.field()))
                        .map(f->new Evidence(e.input().taskId(),e.input().fileName(),f.field(),f.value(),f.locator(),f.excerpt()))).distinct().toList();
                findings.add(new Assessment("AUTO_SPLIT_APPLICATION","WARNING","同一项目多笔申报合计达到100万元",
                        small.get(0).project()+"有 "+small.size()+" 笔申报分别低于 100 万元，累计 "+total.toPlainString()+" 元，合计已达到 100 万元，需关注合并申报及额外审批。涉及材料与金额见下列依据。",
                        "申报合计达到或超过100万元时提示额外审批；仅凭金额累计不认定拆分违规。",sources));
            }
        }
        approvalChecks(inputs,findings);
        var enriched=new LinkedHashMap<UUID,FileResult>();
        for(var entry:results.entrySet()) {
            var value=entry.getValue();
            var own=findings.stream().filter(f->f.sources().stream().anyMatch(s->s.taskId().equals(entry.getKey()))).toList();
            enriched.put(entry.getKey(),new FileResult(value.status(),value.coverage(),value.summary(),value.lines(),own));
        }
        return enriched;
    }
    private record Scoped(Input input,Row row,String project) {
        List<Evidence> refs() {return row.facts().stream().map(f->new Evidence(input.taskId(),input.fileName(),f.field(),f.value(),f.locator(),f.excerpt())).toList();}
    }
    private static List<Scoped> scopes(List<Input> inputs) {
        var out=new ArrayList<Scoped>();
        var codes=new HashMap<String,Set<String>>();
        for(var input:inputs)if(input.snapshot()!=null)for(var row:input.snapshot().rows()) {
            String name=one(row,"projectName"),code=one(row,"projectCode");
            if(name!=null&&code!=null)codes.computeIfAbsent(key(name),x->new HashSet<>()).add(key(code));
        }
        for(var input:inputs)if(input.snapshot()!=null) {
            var names=input.snapshot().rows().stream().map(r->one(r,"projectName")).filter(Objects::nonNull).map(BudgetBatchChecks::key).distinct().toList();
            var numbers=input.snapshot().rows().stream().map(r->one(r,"projectCode")).filter(Objects::nonNull).map(BudgetBatchChecks::key).distinct().toList();
            if(names.size()==1&&numbers.size()==1)codes.computeIfAbsent(names.get(0),x->new HashSet<>()).add(numbers.get(0));
        }
        for(var input:inputs)if(input.status().equals("PARSED")&&input.snapshot()!=null) {
            var headings=input.snapshot().rows().stream().map(r->identity(r,codes)).filter(Objects::nonNull).distinct().toList();
            var records=headings.size()<=1?procedureRows(input.snapshot().rows()):input.snapshot().rows();
            for(var row:records)out.add(new Scoped(input,row,coalesce(identity(row,codes),headings.size()==1?headings.get(0):null)));
        }
        return out;
    }
    /** Word paragraphs are separate source segments. Join one unambiguous
     * procedure record in one document, retaining every original citation.
     * Several steps/clauses/dates or project identities never get spliced. */
    private static List<Row> procedureRows(List<Row> original) {
        var rows=new ArrayList<Row>();
        for(var row:original) {
            var steps=row.facts().stream().filter(f->f.field().equals("requiredApprovalStep")).distinct().toList();
            String clause=one(row,"policyClause");
            boolean scoped=steps.size()>1&&clause!=null&&steps.stream().allMatch(s->clause.indexOf(s.value())>=0
                    &&clause.indexOf(s.value())==clause.lastIndexOf(s.value())
                    &&row.facts().stream().allMatch(f->f.locator().equals(s.locator())));
            if(!scoped) {rows.add(row);continue;}
            for(var step:steps) {
                var facts=new ArrayList<>(row.facts().stream().filter(f->!Set.of("requiredApprovalStep","requiredPreviousStep").contains(f.field())).toList());
                facts.add(step);
                for(var previous:steps)if(previous!=step) {
                    String sequence="先(?:经|由)?\\s*"+java.util.regex.Pattern.quote(previous.value())
                            +"[，,；;\\s]*(?:再|然后|后)(?:经|由)?\\s*"+java.util.regex.Pattern.quote(step.value());
                    if(java.util.regex.Pattern.compile(sequence).matcher(clause).find())
                        facts.add(new Fact("requiredPreviousStep",previous.value(),previous.locator(),clause));
                }
                rows.add(new Row(row.rowKey()+"/step:"+step.value(),List.copyOf(facts)));
            }
        }
        for(var fields:List.of(Set.of("requiredApprovalStep","policyClause","approvalThreshold","thresholdCondition","requiredPreviousStep"),
                Set.of("actualApprovalStep","approvalDate"))) {
            String anchor=fields.contains("requiredApprovalStep")?"requiredApprovalStep":"actualApprovalStep";
            var facts=rows.stream().flatMap(r->r.facts().stream()).filter(f->fields.contains(f.field())).distinct().toList();
            var values=facts.stream().collect(java.util.stream.Collectors.groupingBy(Fact::field,
                    java.util.stream.Collectors.mapping(Fact::value,java.util.stream.Collectors.toSet())));
            if(!values.containsKey(anchor)||values.values().stream().anyMatch(v->v.size()!=1)||values.size()<2)continue;
            if(rows.stream().anyMatch(r->r.facts().containsAll(facts)))continue;
            var replaced=new ArrayList<Row>();
            for(var row:rows) {
                var kept=row.facts().stream().filter(f->!fields.contains(f.field())).toList();
                if(!kept.isEmpty())replaced.add(new Row(row.rowKey(),kept));
            }
            replaced.add(new Row("procedure/"+anchor,facts));rows=replaced;
        }
        return List.copyOf(rows);
    }
    private static String identity(Row row,Map<String,Set<String>> codes) {
        String code=one(row,"projectCode"),name=one(row,"projectName");
        if(code!=null)return "code:"+key(code);
        if(name==null)return null;
        var matches=codes.getOrDefault(key(name),Set.of());
        return matches.size()==1?"code:"+matches.iterator().next():"name:"+key(name);
    }
    private static void approvalChecks(List<Input> inputs,List<Assessment> findings) {
        var rows=scopes(inputs);
        var projects=rows.stream().map(Scoped::project).filter(Objects::nonNull).distinct().toList();
        boolean complete=inputs.stream().allMatch(i->i.status().equals("PARSED")&&i.snapshot()!=null&&i.snapshot().issues().isEmpty());
        for(var policy:rows) {
            var row=policy.row();String required=one(row,"requiredApprovalStep"),clause=one(row,"policyClause");
            if(required==null||clause==null||stepKey(required).isBlank())continue;
            // An unscoped institutional rule may apply to each project, but an
            // unscoped approval record cannot discharge several projects' duties.
            var targets=policy.project()==null?projects:List.of(policy.project());
            for(String project:targets) {
                var applicable=rows.stream().filter(r->project.equals(r.project())||r.project()==null&&projects.size()==1).toList();
                String threshold=one(row,"approvalThreshold"),condition=one(row,"thresholdCondition");
                if(threshold!=null) {
                    var limit=com.fuquan.audit.common.domain.RmbAmountParser.parse(threshold);
                    var total=declaredTotal(applicable);
                    if(limit.isEmpty()||total.isEmpty()||condition==null)continue;
                    int comparison=total.get().compareTo(limit.get());
                    boolean applies=condition.contains("以上")||condition.contains("不低于")||condition.contains("不少于")?comparison>=0
                            :condition.contains("以下")||condition.contains("不超过")?comparison<=0
                            :condition.contains("超过")||condition.contains("大于")?comparison>0
                            :condition.contains("低于")||condition.contains("小于")?comparison<0:false;
                    if(!applies)continue;
                }
                var matched=applicable.stream().filter(r->one(r.row(),"actualApprovalStep")!=null&&stepKey(one(r.row(),"actualApprovalStep")).equals(stepKey(required))).toList();
                var refs=new ArrayList<>(policy.refs());matched.forEach(r->refs.addAll(r.refs()));
                applicable.stream().filter(r->one(r.row(),"declaredAmount")!=null).forEach(r->refs.addAll(r.refs()));
                String label=project.replaceFirst("^(?:name|code):","");
                findings.add(new Assessment("APPROVAL_PROCEDURE",matched.isEmpty()?"WARNING":"INFO",
                        matched.isEmpty()?"审批材料未体现制度要求的环节":"审批环节与上传制度相符",
                        label+"："+(matched.isEmpty()?"上传制度要求“"+required+"”，本批材料未找到对应已完成审批记录。"+(complete?"":"部分材料未完整读取，暂不能认定实际漏审。")
                                :"已找到“"+required+"”的审批记录。"),clause,refs));
                String previous=one(row,"requiredPreviousStep");
                if(previous!=null&&!matched.isEmpty()) {
                    var before=applicable.stream().filter(r->one(r.row(),"actualApprovalStep")!=null
                            &&stepKey(one(r.row(),"actualApprovalStep")).equals(stepKey(previous))).toList();
                    var earlier=before.stream().map(r->date(one(r.row(),"approvalDate"))).filter(Objects::nonNull).distinct().toList();
                    var later=matched.stream().map(r->date(one(r.row(),"approvalDate"))).filter(Objects::nonNull).distinct().toList();
                    if(before.isEmpty()||earlier.size()==1&&later.size()==1&&earlier.get(0).isAfter(later.get(0))) {
                        var sequenceRefs=new ArrayList<>(refs);before.forEach(r->sequenceRefs.addAll(r.refs()));
                        findings.add(new Assessment("APPROVAL_SEQUENCE","WARNING","审批先后顺序需要关注",
                                label+"：制度要求先“"+previous+"”，后“"+required+"”。"+(before.isEmpty()?"本批材料未体现前置环节。":"审批记录日期显示前置环节晚于后续环节。"),clause,sequenceRefs));
                    }
                }
            }
        }
        purposeChecks(rows,findings);
    }
    private static Optional<BigDecimal> declaredTotal(List<Scoped> scope) {
        var files=new LinkedHashMap<UUID,List<Scoped>>();
        for(var row:scope)if(one(row.row(),"declaredAmount")!=null)files.computeIfAbsent(row.input().taskId(),k->new ArrayList<>()).add(row);
        var applications=new LinkedHashMap<String,BigDecimal>();
        boolean unnumbered=false;
        for(var entry:files.entrySet()) {
            var details=new LinkedHashMap<String,BigDecimal>();var totals=new TreeSet<BigDecimal>();
            var numbers=scope.stream().filter(r->r.input().taskId().equals(entry.getKey())).map(r->one(r.row(),"applicationNo")).filter(Objects::nonNull).distinct().toList();
            if(numbers.size()>1)return Optional.empty();
            for(var record:entry.getValue()) {
                var amount=com.fuquan.audit.common.domain.RmbAmountParser.parse(one(record.row(),"declaredAmount"));
                if(amount.isEmpty()||amount.get().signum()<0)return Optional.empty();
                String subject=coalesce(one(record.row(),"subjectCode"),one(record.row(),"subjectName"));
                if(subject==null)totals.add(amount.get());
                else if(details.putIfAbsent(key(subject),amount.get())!=null)return Optional.empty();
            }
            var sum=details.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
            if(totals.size()>1||!totals.isEmpty()&&!details.isEmpty()&&totals.first().compareTo(sum)!=0)return Optional.empty();
            BigDecimal total=totals.isEmpty()?sum:totals.first();
            String number=numbers.isEmpty()?"file:"+entry.getKey():"application:"+numbers.get(0);
            unnumbered|=numbers.isEmpty();
            var prior=applications.putIfAbsent(number,total);
            if(prior!=null&&prior.compareTo(total)!=0)return Optional.empty();
        }
        if(applications.isEmpty())return Optional.empty();
        if(unnumbered&&applications.size()>1) {
            var versions=new TreeSet<>(applications.values());
            return versions.size()==1?Optional.of(versions.first()):Optional.empty();
        }
        return Optional.of(applications.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    private static java.time.LocalDate date(String value) {
        if(value==null)return null;
        var m=java.util.regex.Pattern.compile("([0-9]{4})[-/.年]([0-9]{1,2})[-/.月]([0-9]{1,2})日?").matcher(value.replaceAll("\\s+",""));
        if(!m.matches())return null;
        try{return java.time.LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));}
        catch(java.time.DateTimeException invalid){return null;}
    }
    private static void purposeChecks(List<Scoped> rows,List<Assessment> findings) {
        for(var declared:rows) {
            String purpose=one(declared.row(),"spendingPurpose"),subject=coalesce(one(declared.row(),"subjectCode"),one(declared.row(),"subjectName"));
            if(purpose==null||subject==null||declared.project()==null)continue;
            var approvals=rows.stream().filter(r->declared.project().equals(r.project())
                    &&subject.equals(coalesce(one(r.row(),"subjectCode"),one(r.row(),"subjectName")))&&one(r.row(),"approvedPurpose")!=null).toList();
            var approved=approvals.stream().map(r->one(r.row(),"approvedPurpose")).distinct().toList();
            if(approved.size()!=1||key(purpose).equals(key(approved.get(0))))continue;
            var refs=new ArrayList<>(declared.refs());approvals.forEach(r->refs.addAll(r.refs()));
            findings.add(new Assessment("SPENDING_PURPOSE_DIFFERENCE","WARNING","支出用途与批复表述不同",
                    subject+"：申报用途为“"+purpose+"”，批复用途为“"+approved.get(0)+"”。请关注是否超出批准用途；仅凭措辞差异不认定错支。",
                    "依据本批同一项目、同一科目的申报用途与批准用途原文比较。",refs));
        }
    }
    private static String one(Row row,String field) {var values=row.facts().stream().filter(f->f.field().equals(field)).map(Fact::value).distinct().toList();return values.size()==1?values.get(0):null;}
    private static String coalesce(String... values){for(String value:values)if(value!=null&&!value.isBlank())return value;return null;}
    private static String key(String value){return value==null?"":value.replaceAll("\\s+","");}
    private static String stepKey(String value){return key(value).replaceFirst("^(?:已经|已)","")
            .replaceFirst("(?<!未)(?:已经|已)(?=(?:书面)?(?:审核通过|审批通过|同意|批准|签字|盖章|审核|审批|通过)$)","")
            .replaceFirst("(?:审核通过|审批通过|同意|批准|签字|盖章|审核|审批|通过)$","");}
    private static String signature(Row row){return row.facts().stream().map(f->f.field()+"="+f.value()+"@"+f.excerpt()).sorted().collect(java.util.stream.Collectors.joining("|"));}
}
