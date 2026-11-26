package com.fuquan.audit.budget.application;

import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.math.*;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Component;

/** Deterministic material comparison. No mapping/configuration repository is consulted. */
@Component
public class BudgetUploadedMaterialAnalyzer {
    private record Grounded(Input file,Map<String,List<Fact>> fields,Map<String,String> departmentAliases) {
        String normalized(String field,Fact fact) {String value=identityKey(field,fact.value());return field.equals("departmentName")?departmentAliases.getOrDefault(value,value):value;}
        String one(String field) {var values=fields.getOrDefault(field,List.of()).stream().map(f->normalized(field,f)).distinct().toList();return values.size()==1?values.get(0):null;}
        String display(String field) {return one(field)==null?null:field.equals("subjectName")?subjectBoundary(fields.get(field).get(0).value()):fields.get(field).get(0).value();}
        boolean has(String field) {return fields.containsKey(field);}
    }
    private static final Set<String> IDENTITY=Set.of("projectCode","projectName","budgetYear","departmentName");
    private static final Set<String> HEADING=Set.of("projectCode","projectName","budgetYear","departmentName","subjectCode","subjectName");
    private static boolean identityRole(Fact fact){
        if(!com.fuquan.audit.common.material.SourceIdentityPolicy.plausible(fact.field(),fact.value()))return false;
        if(BudgetIdentityEvidence.subjectAsProject(fact,Objects.toString(fact.excerpt(),"")))return false;
        if(fact.field().equals("departmentName")) {
            String excerpt=Objects.toString(fact.excerpt(),"");
            if(excerpt.matches("(?s).*?(?:编制部门|经办部门|记录部门|审批单位|批复单位)\\s*[:：\\t].*"))return false;
            // A model can include the next labelled field in a value; that is
            // not a separate department and must not manufacture a conflict.
            if(fact.value().matches("(?s).*\\s+(?:部门|申报部门|编制部门|预算年度)\\s*[:：].*"))return false;
        }
        if(!fact.field().equals("projectCode"))return true;
        String role=com.fuquan.audit.common.material.SourceIdentityPolicy.role(fact.value(),fact.excerpt());
        return role==null||role.equals("projectNo");
    }
    private record Comparison(List<Grounded> candidates,boolean ambiguous,Amounts declared,Amounts approved,List<Evidence> evidence) {}
    public Map<UUID,FileResult> analyze(List<Input> inputs) {return analyze(inputs,false);}
    public Map<UUID,FileResult> analyzeBatch(List<Input> inputs) {return analyze(inputs,true);}
    private Map<UUID,FileResult> analyze(List<Input> inputs,boolean batchScope) {
        var all=new ArrayList<Grounded>();
        var departmentAliases=BudgetIdentityEvidence.departmentAliases(inputs);
        for(var input:inputs) {
            if(!input.status().equals("PARSED")||input.snapshot()==null)continue;
            var scope=new HashMap<String,List<Fact>>();
            // A separate document heading can scope its own rows; a project on a detail row cannot.
            for(var row:input.snapshot().rows())if(row.facts().stream().noneMatch(f->Set.of("declaredAmount","approvalAmount").contains(f.field())))
                for(var fact:row.facts())if(HEADING.contains(fact.field())&&identityRole(fact)&&!Set.of("REFERENCE","GENERAL").contains(Objects.toString(fact.subjectScope(),"")))scope.computeIfAbsent(fact.field(),k->new ArrayList<>()).add(fact);
            // A list of subjects is not one document-wide subject heading.
            for(String field:List.of("subjectCode","subjectName"))if(scope.getOrDefault(field,List.of()).stream().map(f->identityKey(field,f.value())).distinct().count()>1)scope.remove(field);
            for(var row:input.snapshot().rows()) {
                var fields=new LinkedHashMap<String,List<Fact>>();
                for(var fact:row.facts())if(identityRole(fact)&&!Set.of("REFERENCE","GENERAL").contains(Objects.toString(fact.subjectScope(),"")))fields.computeIfAbsent(BudgetAmountEvidence.isOtherAmount(fact)?"otherAmount":fact.field(),k->new ArrayList<>()).add(fact);
                if(fields.keySet().stream().noneMatch(Set.of("subjectCode","subjectName","declaredAmount","approvalAmount")::contains))continue;
                boolean compatibleHeading=true;
                for(var field:List.of("projectCode","projectName"))if(fields.containsKey(field)&&scope.containsKey(field)) {
                    var headingValues=scope.get(field).stream().map(f->identityKey(field,f.value())).distinct().toList();
                    var rowValues=fields.get(field).stream().map(f->identityKey(field,f.value())).distinct().toList();
                    if(!headingValues.equals(rowValues))compatibleHeading=false;
                }
                if(compatibleHeading)for(var field:HEADING)if(!fields.containsKey(field)&&scope.containsKey(field))fields.put(field,List.copyOf(scope.get(field)));
                all.add(new Grounded(input,fields,departmentAliases));
            }
        }
        var index=new HashMap<List<String>,List<Grounded>>();
        for(var grounded:all)for(var key:keys(grounded,batchScope))index.computeIfAbsent(key,k->new ArrayList<>()).add(grounded);
        var results=new LinkedHashMap<UUID,FileResult>();
        var comparisons=new HashMap<List<String>,Comparison>();
        boolean incomplete=inputs.stream().anyMatch(f->!f.status().equals("PARSED")||f.snapshot()==null||!f.snapshot().issues().isEmpty());
        // An optional policy/description candidate cannot erase a verified
        // amount comparison. Unknown text/identity/amount gaps still prevent
        // conclusions that depend on complete financial coverage.
        // A document-level or unrelated extraction gap does not invalidate an
        // otherwise grounded subject comparison.  The old global switch made
        // every row INSUFFICIENT when one page had an optional/unreadable
        // fragment, even if this row had a unique subject code and both
        // amounts with exact evidence.  Keep the batch coverage warning in
        // the summary, but only suppress a financial conclusion when the
        // relevant identity/amount fields themselves are uncertain.
        // Failed/unreadable documents can hide a relevant counterpart anywhere in
        // the batch. Field-level extraction issues are narrower: suppress only
        // rows whose source span overlaps the uncertain field, never every row.
        boolean unreadableInput=inputs.stream().anyMatch(f->!f.status().equals("PARSED")||f.snapshot()==null);
        boolean batchRelevantIssue=inputs.stream().filter(f->f.snapshot()!=null).flatMap(f->f.snapshot().issues().stream())
                .anyMatch(BudgetUploadedMaterialAnalyzer::relevantCoverageIssue);
        for(var input:inputs) {
            var lines=new ArrayList<Line>();
            var knownTotals=BudgetBatchAmounts.totals(List.of(input));
            for(var target:all)if(target.file.taskId().equals(input.taskId())) {
                // Project totals already have a dedicated comparison. Repeated
                // cover-page amounts without a subject are not extra details.
                if(target.one("subjectName")==null&&target.one("subjectCode")==null
                        &&target.fields.entrySet().stream().filter(e->Set.of("declaredAmount","approvalAmount").contains(e.getKey()))
                        .allMatch(e->e.getValue().stream().allMatch(f->knownTotals.stream()
                                .anyMatch(t->"TOTAL".equals(t.scope())&&t.role().equals(f.field().equals("declaredAmount")?"declaredTotalAmount":"approvedTotalAmount")
                                        &&money(f.value())!=null&&t.amount().compareTo(money(f.value()))==0))))continue;
                boolean redundantIdentityOnly=target.fields.keySet().stream().noneMatch(Set.of("declaredAmount","approvalAmount")::contains)
                    &&all.stream().anyMatch(other->other.file.taskId().equals(target.file.taskId())
                        &&other.fields.keySet().stream().anyMatch(Set.of("declaredAmount","approvalAmount")::contains)
                        &&Objects.equals(target.one("projectCode"),other.one("projectCode"))
                        &&Objects.equals(target.one("projectName"),other.one("projectName"))
                        &&Objects.equals(target.one("subjectCode"),other.one("subjectCode"))
                        &&Objects.equals(target.one("subjectName"),other.one("subjectName"))
                        &&Objects.equals(target.one("budgetYear"),other.one("budgetYear"))
                        &&Objects.equals(target.one("departmentName"),other.one("departmentName")));
                if(redundantIdentityOnly)continue;
                boolean conflictingIdentity=target.fields.entrySet().stream().filter(e->!e.getKey().endsWith("Amount"))
                        .anyMatch(e->e.getValue().stream().map(f->target.normalized(e.getKey(),f)).distinct().count()>1);
                boolean projectNameConflict=!batchScope&&projectNameConflict(target,all);
                var context=new ArrayList<String>();
                for(var field:List.of("projectCode","projectName","subjectCode","subjectName","budgetYear","departmentName"))context.add(Objects.toString(target.one(field),""));
                if(target.one("projectCode")==null&&target.one("projectName")==null)context.add(target.file.taskId().toString());
                // Ambiguous identities and ungrouped rows cannot share resolutions.
                var comparison=conflictingIdentity||keys(target,batchScope).isEmpty()?compare(target,index,batchScope):comparisons.computeIfAbsent(context,k->compare(target,index,batchScope));
                boolean rowUncertain=comparison.candidates().stream().anyMatch(candidate->issueAffects(candidate));
                // Incomplete batch coverage blocks absence claims, because a
                // missing counterpart may be hidden in another file. A positive
                // comparison still depends only on the candidate rows' evidence.
                boolean amountIncomplete=unreadableInput||batchRelevantIssue||rowUncertain;
                boolean ambiguous=comparison.ambiguous;
                var declared=comparison.declared;var approved=comparison.approved;
                boolean invalid=declared.invalid||approved.invalid;
                BigDecimal d=declared.values.size()==1?declared.values.get(0):null,a=approved.values.size()==1?approved.values.get(0):null;
                String status,opinion;
                if(projectNameConflict) {
                    status="CONFLICT";
                    opinion=projectNameConflictOpinion(target,all);
                    d=null;a=null;
                } else if(ambiguous||conflictingIdentity||declared.values.size()>1||approved.values.size()>1) {
                    status="CONFLICT";opinion="材料中的项目、年度、部门或金额存在不一致，暂不能确认适用的申报与批复，请核对材料版本。";
                    if(ambiguous||conflictingIdentity){d=null;a=null;}
                } else if(invalid) {status="INVALID_AMOUNT";opinion="材料中有金额的格式、单位、精度、数值范围或重复明细无法确认，暂不作金额比较。";}
                else if(declared.repeatedRows||approved.repeatedRows) {status="INSUFFICIENT";opinion="同一供应商存在多条申报记录，尚不能确认是否重复。已保留单条识别金额，未自动相加，也未判定合计是否超过批复。";}
                else if(target.one("subjectName")==null&&target.one("subjectCode")==null) {status="INSUFFICIENT";opinion="已保留材料中的金额，但未能确认对应预算科目，暂不作金额比较。";}
                else if(d!=null&&a==null&&!amountIncomplete&&hasCounterpartScope(target,all,"approvalAmount",batchScope)) {
                    status="UNAPPROVED_SUBJECT";opinion="疑似错支：本批同一项目的批复材料中未找到此申报科目，请核对是否超出批复范围，或尚有批复材料未上传。";
                }
                else if(d==null&&a!=null&&!amountIncomplete&&hasCounterpartScope(target,all,"declaredAmount",batchScope)) {
                    status="MISSING_DECLARATION";opinion="疑似漏项：本批同一项目的申报材料中未找到此批复科目，请核对是否遗漏申报，或尚有申报材料未上传。";
                }
                else if(d==null&&a==null) {status="INSUFFICIENT";opinion="已识别到预算科目，但材料中没有可核实的申报金额或批复金额，因此未参与金额比较。请核对对应表格的金额列是否为空或未被识别。";}
                else if(d==null||a==null) {status="INSUFFICIENT";opinion=d==null?"已读取现有材料，尚缺少可对应的申报金额，暂不能判断是否超过批复。":"已读取申报信息，现有材料未能确认对应批复金额，暂不能判断是否超过批复。";}
                else if(rowUncertain) {status="INSUFFICIENT";opinion="该科目相关内容尚有未核实信息，暂不能形成金额比较结论。";}
                else if(d.compareTo(a)>0) {status="OVER_APPROVAL";opinion="材料所列申报金额超过对应批复金额 "+d.subtract(a).toPlainString()+" 元，建议核对申报依据与批复版本。";}
                else if(unreadableInput) {status="INSUFFICIENT";opinion="已核实部分的申报金额未超过对应批复金额，但本批仍有材料未能确认，暂不能判断整体预算是否存在超支、错支或漏项。";}
                else {status="WITHIN_APPROVAL";opinion="材料所列申报金额未超过对应批复金额。本结果仅供工作参考，不代表正式审批结论。";}
                lines.add(new Line(Optional.ofNullable(target.display("projectName")).orElse(target.display("projectCode")),target.display("projectCode"),target.display("subjectName"),target.display("subjectCode"),target.display("budgetYear"),target.display("departmentName"),d,a,status,opinion,comparison.evidence));
            }
            // Equal duplicate facts/rows are not additional spending. Keep their evidence together.
            var unique=new LinkedHashMap<String,Line>();
            for(var line:lines) {
                String identity=Objects.toString(line.project(),"")+"\u0000"+Objects.toString(line.subjectCode(),"")+"\u0000"+Objects.toString(line.subject(),"")+"\u0000"+line.year()+"\u0000"+line.department()+"\u0000"+line.declaredAmount()+"\u0000"+line.approvalAmount()+"\u0000"+line.status();
                var old=unique.get(identity);
                if(old==null)unique.put(identity,line);
                else if(old.sources()!=line.sources()) {var refs=new LinkedHashSet<>(old.sources());refs.addAll(line.sources());unique.put(identity,new Line(old.project(),old.projectCode(),old.subject(),old.subjectCode(),old.year(),old.department(),old.declaredAmount(),old.approvalAmount(),old.status(),old.opinion(),List.copyOf(refs)));}
            }
            boolean hasProjectBudget=input.snapshot()!=null&&input.snapshot().rows().stream().flatMap(r->r.facts().stream())
                .anyMatch(f->f.field().equals("projectBudgetAmount")&&money(f.value())!=null
                    &&!Set.of("REFERENCE","GENERAL").contains(Objects.toString(f.subjectScope(),"")));
            String summary=input.status().equals("UNKNOWN")?"这份材料的处理状态尚未确认，不能视为没有预算信息。"
                :input.status().equals("FAILED")?"这份材料未能完成读取；同批其他材料的可用信息仍已保留。"
                :unique.isEmpty()?(hasProjectBudget?"已识别项目预算金额，尚未确认可比对的预算科目明细。":"已读取这份材料，未找到可确认的预算科目或金额信息；不能据此认定预算没有问题。")
                :"根据本批上传材料整理了以下预算信息与核对意见，供工作参考。";
            if(incomplete)summary+="本批仍有材料或部分信息未能核实，结果依据尚不完整；未列出的内容不代表没有问题。";
            if(input.snapshot()!=null&&!input.snapshot().issues().isEmpty()) {
                var reasons=input.snapshot().issues().stream().map(ExtractionIssue::reasonCode).collect(java.util.stream.Collectors.toSet());
                summary+="已保留能够核实的预算信息。";
                if(reasons.contains("ROW_UNRESOLVED"))summary+="部分重复或跨段内容无法确认属于哪一项，未参与金额比较。";
                if(reasons.contains("AMOUNT_ROLE_UNVERIFIED"))summary+="部分金额无法确认是申报还是批复，未参与金额比较。";
                if(reasons.contains("EVIDENCE_UNVERIFIED"))summary+="部分提取内容未能与原文核实，未采用。";
                if(reasons.contains("OCR_IDENTITY_UNCERTAIN"))summary+="扫描文字中的项目编号存在字母 O 与数字 0 不一致，尚不能确认相关明细是否属于同一项目，未自动合并，请核对原件。";
            }
            if(input.snapshot()!=null&&input.snapshot().rows().stream().flatMap(r->r.facts().stream()).anyMatch(BudgetAmountEvidence::isOtherAmount))
                summary+="材料中的项目总预算、单价或合计未作为科目申报或批复金额使用。";
            boolean unresolved=incomplete||unique.isEmpty()||unique.values().stream()
                .anyMatch(line->!Set.of("WITHIN_APPROVAL","OVER_APPROVAL","UNAPPROVED_SUBJECT","MISSING_DECLARATION").contains(line.status()));
            results.put(input.taskId(),new FileResult("COMPLETED",unresolved?"PARTIAL":"COMPLETE",summary,List.copyOf(unique.values())));
        }
        return BudgetBatchChecks.enrich(inputs,results);
    }
    private static boolean issueAffects(Grounded candidate) {
        if(candidate.file().snapshot()==null)return true;
        var relevant=Set.of("projectCode","projectName","budgetYear","departmentName","subjectCode","subjectName","declaredAmount","approvalAmount");
        var facts=candidate.fields().values().stream().flatMap(Collection::stream).toList();
        return candidate.file().snapshot().issues().stream().anyMatch(issue->{
            // A missed optional semantic pass must not erase exact, source-backed
            // amount facts. Incomplete page/text coverage can affect any row in
            // that document and therefore still blocks a definitive comparison.
            // A document-level optional/model extraction gap is batch context,
            // not proof that every grounded row is uncertain. It still blocks
            // absence claims through batchRelevantIssue, while exact rows with
            // both amounts remain comparable.
            if(issue.field().equals("document"))return false;
            if(!relevant.contains(issue.field()))return false;
            var sameField=facts.stream().filter(f->f.field().equals(issue.field())).toList();
            return sameField.isEmpty()||sameField.stream().anyMatch(f->locatorOverlaps(issue.locator(),f.locator()));
        });
    }
    private static boolean relevantCoverageIssue(ExtractionIssue issue) {
        if(issue.field().equals("document"))return issue.reasonCode().equals("DOCUMENT_PARTIALLY_READ");
        return Set.of("projectCode","projectName","budgetYear","departmentName","subjectCode","subjectName","declaredAmount","approvalAmount")
                .contains(issue.field());
    }
    private static boolean locatorOverlaps(String issue,String fact) {
        if(issue==null||fact==null)return false;
        // A bare segment/page locator is document-wide context, not a row
        // locator. Applying it to every row made one unverified model value
        // suppress unrelated exact comparisons. Row/column locators are
        // specific enough to affect the matching fact only.
        if(issue.equals(fact))return true;
        return issue.startsWith(fact+"/");
    }
    private static Comparison compare(Grounded target,Map<List<String>,List<Grounded>> index,boolean batchScope) {
        var nearby=new LinkedHashSet<Grounded>();nearby.add(target);
        for(var key:keys(target,batchScope))nearby.addAll(index.getOrDefault(key,List.of()));
        var family=nearby.stream().filter(other->other==target||(batchScope||identity(target,other,"projectCode","projectName")||sameDocumentContext(target,other))&&identity(target,other,"subjectCode","subjectName")).toList();
        var ambiguousFields=new HashSet<String>();
        for(var field:List.of("budgetYear","departmentName"))if(family.stream().map(r->r.one(field)).filter(Objects::nonNull).distinct().count()>1)ambiguousFields.add(field);
        var candidates=family.stream().filter(other->other==target||related(target,other,batchScope)
            && ambiguousFields.stream().noneMatch(f->target.one(f)==null||other.one(f)==null)).toList();
        boolean ambiguous=ambiguousFields.stream().anyMatch(f->target.one(f)==null);
        var evidence=new LinkedHashSet<Evidence>();
        for(var c:candidates)for(var field:c.fields.entrySet())for(var fact:field.getValue())evidence.add(new Evidence(c.file.taskId(),c.file.fileName(),fact.field(),fact.value(),fact.locator(),fact.excerpt()));
        return new Comparison(candidates,ambiguous,amounts(candidates,"declaredAmount"),amounts(candidates,"approvalAmount"),List.copyOf(evidence));
    }
    private static boolean projectNameConflict(Grounded target,List<Grounded> all) {
        if(target.one("projectCode")==null||target.one("projectName")==null)return false;
        return all.stream().filter(other->other!=target
                &&target.one("projectCode").equals(other.one("projectCode"))
                &&identity(target,other,"subjectCode","subjectName")
                &&Objects.equals(target.one("budgetYear"),other.one("budgetYear"))
                &&Objects.equals(target.one("departmentName"),other.one("departmentName")))
            .map(other->other.one("projectName")).filter(Objects::nonNull)
            .anyMatch(name->!target.one("projectName").equals(name));
    }
    private static String projectNameConflictOpinion(Grounded target,List<Grounded> all) {
        var names=new LinkedHashSet<String>();
        all.stream().filter(other->target.one("projectCode")!=null&&target.one("projectCode").equals(other.one("projectCode"))
                &&identity(target,other,"subjectCode","subjectName")
                &&Objects.equals(target.one("budgetYear"),other.one("budgetYear"))
                &&Objects.equals(target.one("departmentName"),other.one("departmentName")))
            .map(other->other.display("projectName")).filter(Objects::nonNull).forEach(names::add);
        return "项目编号一致，但材料中的项目名称不一致（"+String.join(" / ",names.stream().limit(2).toList())
            +"）。为避免将不同项目金额误合并，当前未进行跨名称金额比较，请核对是否属于同一项目。";
    }
    private static boolean hasCounterpartScope(Grounded target,List<Grounded> all,String amountField,boolean batchScope) {
        // File proximity and a missing project are never proof of a common scope.
        // An unidentified/conflicting year or department cannot bridge known versions.
        var family=all.stream().filter(other->batchScope||identity(target,other,"projectCode","projectName")).toList();
        for(String field:List.of("budgetYear","departmentName")) {
            if(target.has(field)&&target.one(field)==null)return false;
            if(target.one(field)==null&&family.stream().map(r->r.one(field)).filter(Objects::nonNull).distinct().count()>1)return false;
        }
        return family.stream().anyMatch(other-> {
            if(other.one("subjectCode")==null&&other.one("subjectName")==null)return false;
            for(String field:List.of("budgetYear","departmentName")) {
                if(other.has(field)&&other.one(field)==null)return false;
                if(target.one(field)!=null&&!target.one(field).equals(other.one(field)))return false;
            }
            var amounts=amounts(List.of(other),amountField);
            return !amounts.invalid&&amounts.values.size()==1;
        });
    }
    private static boolean related(Grounded a,Grounded b,boolean batchScope) {
        if(a==b)return true;
        // Same file is not proof that two unidentified rows are the same project/subject.
        boolean project=batchScope||identity(a,b,"projectCode","projectName")||sameDocumentContext(a,b),subject=identity(a,b,"subjectCode","subjectName");
        if(!project||!subject)return false;
        for(var f:List.of("budgetYear","departmentName")) {
            if(a.has(f)&&a.one(f)==null||b.has(f)&&b.one(f)==null)return false;
            if(a.one(f)!=null&&b.one(f)!=null&&!a.one(f).equals(b.one(f)))return false;
        }
        return true;
    }
    private static boolean sameDocumentContext(Grounded a,Grounded b) {
        return a.file.taskId().equals(b.file.taskId())&&!a.has("projectCode")&&!a.has("projectName")&&!b.has("projectCode")&&!b.has("projectName");
    }
    private static List<List<String>> keys(Grounded row,boolean batchScope) {
        var keys=new ArrayList<List<String>>();
        if(batchScope)for(var subject:List.of("subjectCode","subjectName"))if(row.one(subject)!=null)keys.add(List.of("batch",subject,row.one(subject)));
        for(var project:List.of("projectCode","projectName"))for(var subject:List.of("subjectCode","subjectName"))
            if(row.one(project)!=null&&row.one(subject)!=null)keys.add(List.of(project,row.one(project),subject,row.one(subject)));
        if(!row.has("projectCode")&&!row.has("projectName"))for(var subject:List.of("subjectCode","subjectName"))
            if(row.one(subject)!=null)keys.add(List.of("single-document",row.file.taskId().toString(),subject,row.one(subject)));
        return keys;
    }
    private static boolean identity(Grounded a,Grounded b,String code,String name) {
        if(a.has(code)&&a.one(code)==null||b.has(code)&&b.one(code)==null||a.has(name)&&a.one(name)==null||b.has(name)&&b.one(name)==null)return false;
        if(a.one(code)!=null&&b.one(code)!=null)return a.one(code).equals(b.one(code))
            // An explicit project identifier is stronger than its narrative title.
            // Subject codes retain stricter semantics; different subjects must not mix.
            &&(code.equals("projectCode")||a.one(name)==null||b.one(name)==null||a.one(name).equals(b.one(name)));
        return a.one(name)!=null&&a.one(name).equals(b.one(name));
    }
    private record Amounts(List<BigDecimal> values,boolean invalid,boolean repeatedRows) {
        Amounts(List<BigDecimal> values,boolean invalid) {this(values,invalid,false);}
    }
    private static Amounts amounts(List<Grounded> rows,String field) {
        var values=new ArrayList<BigDecimal>();boolean invalid=false;
        var details=new LinkedHashMap<String,List<BigDecimal>>();boolean unidentified=false;
        var sourceRows=new HashMap<List<String>,Set<String>>();
        for(var row:rows)for(var fact:row.fields.getOrDefault(field,List.of())) {
            if(Set.of("未载明","未填写","待批复","待定","暂无","无","—","-","/").contains(Objects.toString(fact.value(),"").strip()))continue;
            BigDecimal amount=money(fact.value());
            if(amount==null)invalid=true;
            else {
                if(values.stream().noneMatch(v->v.compareTo(amount)==0))values.add(amount);
                String supplier=row.one("supplierName");
                if(field.equals("declaredAmount")) {
                    var sourceKey=List.of(row.file.taskId().toString(),Objects.toString(supplier,""));
                    sourceRows.computeIfAbsent(sourceKey,k->new HashSet<>()).add(fact.locator().replaceFirst("/col:[0-9]+$",""));
                }
                if(supplier==null)unidentified=true;
                else {
                    var detail=details.computeIfAbsent(supplier,k->new ArrayList<>());
                    if(detail.stream().noneMatch(v->v.compareTo(amount)==0))detail.add(amount);
                }
            }
        }
        // Distinct source rows for one supplier may be separate expenses. Equal
        // amounts alone do not prove a duplicate; without a detail identity we
        // must not silently undercount or invent a sum.
        if(sourceRows.values().stream().anyMatch(locators->locators.size()>1))
            return new Amounts(values,invalid,true);
        // Distinct cited spending details must not collapse just because their
        // numeric amounts coincide. Repeated evidence for the same supplier is
        // not another expense. Ambiguous versions remain conflicts, never sums.
        if(field.equals("declaredAmount")&&!unidentified&&details.size()>1&&details.values().stream().allMatch(v->v.size()==1)) {
            var total=details.values().stream().map(v->v.get(0)).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(total.setScale(2).precision()>18)return new Amounts(List.of(),true);
            return new Amounts(List.of(total),invalid);
        }
        return new Amounts(values,invalid);
    }
    public static BigDecimal money(String value) {
        return com.fuquan.audit.common.domain.RmbAmountParser.parse(value).orElse(null);
    }
    private static String key(String value) {return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+","").toLowerCase(Locale.ROOT);}
    // A model may include a sentence separator after a subject. Preserve all
    // internal punctuation, codes and the original cited value/excerpt.
    private static String subjectBoundary(String value) {return value.strip().replaceFirst("[,，;；]+$","").strip();}
    static String identityKey(String field,String value) {
        String normalized=key(field.equals("subjectName")?subjectBoundary(value):value);
        if(field.equals("projectName")) {
            // Ignore only punctuation and a common OCR-added trailing 的.
            // Never use fuzzy/prefix matching to join distinct project scopes.
            return normalized.replaceAll("[，,。；;：:、]+$", "").replaceFirst("(?<=项目)的$", "");
        }
        // A Chinese year suffix is presentation, not a different approval version.
        return field.equals("budgetYear")&&normalized.matches("[0-9]{4}(?:年|年度)?")
            ?normalized.substring(0,4):normalized;
    }
}
