package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.BudgetMaterialStoragePort;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.common.material.application.port.DocumentTextPort;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

/** Reads the entire bounded document. Explicit table labels need no model paraphrase. */
@Component
public class BudgetUploadedMaterialReader {
    private com.fuquan.audit.common.material.visual.MaterialVisualEvidence visualQuality;
    @org.springframework.beans.factory.annotation.Autowired public void setVisualQuality(com.fuquan.audit.common.material.visual.MaterialVisualEvidence value){this.visualQuality=value;}
    private final DocumentTextPort text;
    private final MaterialFieldAiPort ai;
    private final BudgetMaterialStoragePort storage;
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("科目编码","subjectCode"),Map.entry("科目代码","subjectCode"),Map.entry("科目名称","subjectName"),Map.entry("预算科目","subjectName"),
        Map.entry("申报金额","declaredAmount"),Map.entry("申请金额","declaredAmount"),Map.entry("本次申报金额","declaredAmount"),Map.entry("本次申请金额","declaredAmount"),
        Map.entry("批复参考金额","approvalAmount"),Map.entry("批复参考额度","approvalAmount"),Map.entry("批复金额","approvalAmount"),Map.entry("批准金额","approvalAmount"),
        Map.entry("项目编号","projectCode"),Map.entry("项目编码","projectCode"),Map.entry("项目名称","projectName"),
        Map.entry("预算年度","budgetYear"),Map.entry("年度","budgetYear"),Map.entry("申报部门","departmentName"),Map.entry("申请部门","departmentName"),Map.entry("部门名称","departmentName"),Map.entry("供应商名称","supplierName"),
        Map.entry("支出用途","spendingPurpose"),Map.entry("批准用途","approvedPurpose"),
        Map.entry("已完成审批环节","actualApprovalStep"),Map.entry("审批日期","approvalDate"),
        Map.entry("申报日期","applicationDate"),Map.entry("申请日期","applicationDate"),
        Map.entry("申报编号","applicationNo"),Map.entry("申请编号","applicationNo"),
        Map.entry("项目预算","projectBudgetAmount"),Map.entry("项目预算金额","projectBudgetAmount"),Map.entry("预算金额","projectBudgetAmount"),Map.entry("预算总额","projectBudgetAmount"),
        Map.entry("申报总额","declaredTotalAmount"),Map.entry("申请总额","declaredTotalAmount"),Map.entry("申报合计","declaredTotalAmount"),
        Map.entry("批复总额","approvedTotalAmount"),Map.entry("批复控制数","approvedTotalAmount"),Map.entry("最高限价","ceilingAmount"));
    private static final Set<String> SUPPORTED_FIELDS=java.util.stream.Stream.concat(LABELS.values().stream(),
            java.util.stream.Stream.of("parentProjectName","applicationNo","applicationDate","actualApprovalStep","requiredApprovalStep",
                    "policyClause","approvalThreshold","thresholdCondition","spendingPurpose","approvedPurpose","requiredPreviousStep","approvalDate"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Pattern LABEL=Pattern.compile("("+String.join("|",LABELS.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).toList())+")(?:[（(](万元|千元|元)[）)])?\\s*[:：]\\s*([^；;|。\\r\\n]+)");
    private static final Pattern UNIT=Pattern.compile("(?:金额单位|单位)\\s*[:：]\\s*(?:人民币)?(万元|千元|元)");
    private static final Set<String> TABLE_DETAIL_COLUMNS=Set.of("数量","单价","计量单位","序号");
    private static final Pattern ROLE=Pattern.compile("(本次申请|本次申报|申请金额|申报金额|申请|申报|批复参考金额|批复金额|批准金额|核准|获批|批复|批准)\\s*(?:为|计|[:：])?\\s*(?:人民币)?([0-9０-９,，.．+¥￥零壹贰叁肆伍陆柒捌玖貳參陸拾佰仟万萬亿億元圆圓角分整正]+)");
    /** Repairs only a line break inside a known label or a hyphenated project code.
     * The offset map keeps every excerpt an exact slice of the original text. */
    private record LabelledLayout(String text,int[] offsets,int sourceLength) {
        int sourceOffset(int position) {return position>=offsets.length?sourceLength:offsets[position];}
        String excerpt(String original,int start,int end) {
            return original.substring(sourceOffset(start),end<=start?sourceOffset(start):sourceOffset(end-1)+1);
        }
    }
    private static LabelledLayout labelledLayout(String source) {
        boolean[] skip=new boolean[source.length()];
        for(String label:LABELS.keySet()) {
            String expression=label.codePoints().mapToObj(c->Pattern.quote(new String(Character.toChars(c))))
                .collect(java.util.stream.Collectors.joining("[\\t \\r\\n]*"))+"(?=\\s*[:：])";
            var matcher=Pattern.compile(expression).matcher(source);
            while(matcher.find()) {int end=matcher.end();while(end<source.length()&&Character.isWhitespace(source.charAt(end)))end++;for(int i=matcher.start();i<end;i++)if(Character.isWhitespace(source.charAt(i)))skip[i]=true;}
        }
        var code=Pattern.compile("项目(?:编号|编码)\\s*[:：]\\s*[A-Za-z0-9._/-]*[-_/]([\\t ]*\\R[\\t ]*)(?=[A-Za-z0-9])").matcher(source);
        while(code.find())for(int i=code.start(1);i<code.end(1);i++)skip[i]=true;
        var text=new StringBuilder();int[] offsets=new int[source.length()];int count=0;
        for(int i=0;i<source.length();i++)if(!skip[i]){text.append(source.charAt(i));offsets[count++]=i;}
        return new LabelledLayout(text.toString(),Arrays.copyOf(offsets,count),source.length());
    }
    private static String labelName(String label) {return label.replaceAll("[（(].*","").strip();}
    private static String unit(String value) {return value.contains("万元")?"万元":value.contains("千元")?"千元":value.contains("元")?"元":null;}
    private static String withUnit(String value,String unit) {
        return unit!=null&&value.matches("[\\d,+. -]+|[零壹贰叁肆伍陆柒捌玖貳參陸拾佰仟万萬亿億]+")?value+unit:value;
    }
    private static boolean missingAmount(String field,String value) {
        return field.endsWith("Amount")&&Set.of("未提供","未填写","未明确","待定","待核定","暂无","—","--","/","N/A").contains(value.strip());
    }
    public BudgetUploadedMaterialReader(DocumentTextPort text,MaterialFieldAiPort ai,BudgetMaterialStoragePort storage) {this.text=text;this.ai=ai;this.storage=storage;}
    public BudgetUploadedFacts.Snapshot read(BudgetReviewTask task) throws IOException {
        DocumentTextPort.Result document;
        try(var input=storage.open(task.tempFilePath())) {document=text.read(input,task.fileName(),task.id().toString(),BudgetReviewService.MAX_FILE_SIZE,512,192000);}
        return extract(document,task.id().toString());
    }
    public BudgetUploadedFacts.Snapshot extract(DocumentTextPort.Result document,String id) throws IOException {
        var rows=new ArrayList<Row>();var semantic=new ArrayList<Segment>();
        var issues=new LinkedHashSet<ExtractionIssue>();
        for(String gap:document.coverageGaps())if(gap.startsWith("UNREAD:"))
            issues.add(new ExtractionIssue("document","document/coverage","DOCUMENT_PARTIALLY_READ"));
        Map<String,List<String>> headers=new HashMap<>();
        Map<String,String> sheetUnits=new HashMap<>();
        Map<String,Segment> headerSources=new HashMap<>();
        Set<String> semanticLocators=new HashSet<>();
        String activeRecord=null,previousLocator=null;var activeFields=new HashSet<String>();
        for(var segment:document.segments()) {
            String body=segment.text();
            if(!adjacentWordNodes(previousLocator,segment.locator())) {activeRecord=null;activeFields.clear();}
            previousLocator=segment.locator();
            if(segment.locator().startsWith("excel/")&&segment.locator().contains("/row:")) {
                String sheet=segment.locator().substring(0,segment.locator().lastIndexOf("/row:"));
                var declaredUnit=UNIT.matcher(body);
                if(declaredUnit.find()) {
                    sheetUnits.put(sheet,declaredUnit.group(1));
                    // A standalone unit is fully understood, but a shared heading
                    // must still preserve the year and department beside it.
                    if(body.substring(0,declaredUnit.start()).isBlank()
                        &&body.substring(declaredUnit.end()).replaceAll("[\\s|。.;；]+","").isEmpty())continue;
                }
                var cells=Arrays.asList(body.split("\\s*\\|\\s*",-1));
                boolean header=cells.size()>1&&cells.stream().anyMatch(v->LABELS.containsKey(labelName(v)));
                if(header) {headers.put(sheet,cells);headerSources.put(sheet,segment);continue;}
                var labels=headers.get(sheet);
                if(labels!=null&&labels.size()==cells.size()) {
                    var facts=new ArrayList<Fact>();
                    for(int col=0;col<cells.size();col++) {
                        String value=cells.get(col).strip(),label=labels.get(col).strip();
                        String field=LABELS.get(labelName(label));
                        if(field!=null&&!value.isBlank()&&!missingAmount(field,value)) {
                            String amountUnit=unit(label)==null?sheetUnits.get(sheet):unit(label);
                            if(field.endsWith("Amount"))value=withUnit(value,amountUnit);
                            // Header and cell are read deterministically; locator includes the real column.
                            facts.add(new Fact(field,value,segment.locator()+"/col:"+(col+1),label+"："+value));
                        }
                    }
                    if(!facts.isEmpty())rows.add(new Row(segment.locator(),facts));
                    // A recognized subject column is not proof that all amount columns
                    // are understood. Send the original header AND row to the model.
                    if(labels.stream().anyMatch(l->!l.isBlank()&&!LABELS.containsKey(labelName(l))&&!TABLE_DETAIL_COLUMNS.contains(labelName(l)))) {
                        var h=headerSources.get(sheet);
                        if(semanticLocators.add(h.locator()))semantic.add(h);
                        if(semanticLocators.add(segment.locator()))semantic.add(segment);
                    }
                    continue;
                }
            }
            // One PDF/TXT span may contain many real rows. Sharing a locator
            // cannot authorize joining the project from one line to another's amount.
            var layout=labelledLayout(body);
            int layoutOffset=0;
            for(String line:layout.text().split("\\R",-1)) {
                int lineNumber=(int)body.substring(0,layout.sourceOffset(layoutOffset)).chars().filter(c->c=='\n').count();
                var facts=new ArrayList<Fact>();var matcher=LABEL.matcher(line);
                while(matcher.find()) {
                    String field=LABELS.get(matcher.group(1)),value=matcher.group(3).strip();
                    String excerpt=layout.excerpt(body,layoutOffset+matcher.start(),layoutOffset+matcher.end()),amountUnit=matcher.group(2);int resume=-1;
                    // OCR and Word commonly put several labels on one line without
                    // semicolons. A field must end before the next real label.
                    var nextLabel=Pattern.compile("(?:"+String.join("|",LABELS.keySet().stream().map(Pattern::quote).toList())+"|金额单位|单位)\\s*[:：]").matcher(value);
                    if(nextLabel.find()) {
                        String prefix=value.substring(0,nextLabel.start()).strip();
                        resume=matcher.start(3)+matcher.group(3).indexOf(value)+nextLabel.start();
                        value=prefix;
                        excerpt=layout.excerpt(body,layoutOffset+matcher.start(),layoutOffset+resume).strip();
                    }
                    if(!value.isBlank()&&!missingAmount(field,value)) {
                        if(field.endsWith("Amount"))value=withUnit(value,amountUnit);
                        // A labelled amount is exact, but its label can still belong to a historical example.
                        String prefix=line.substring(0,matcher.start());
                        String reference=prefix.matches("(?s).*(?:历史|旧|以往|往年|上年|其他|参考|示例)(?:项目|案例|年度)?(?:的)?\\s*$")
                                &&!prefix.matches("(?s).*(?:非|不是|不属于|并非|不含)(?:历史|旧|其他|参考|示例)[^。；;]*$")?"REFERENCE":null;
                        facts.add(new Fact(field,value,segment.locator()+"/line:"+lineNumber,excerpt,reference,null));
                    }
                    if(resume>=0)matcher.region(resume,line.length());
                }
                String physical=segment.locator()+"/line:"+lineNumber;
                boolean subject=facts.stream().anyMatch(f->Set.of("subjectName","subjectCode").contains(f.field()));
                boolean continuation=!facts.isEmpty()&&facts.stream().allMatch(f->Set.of("declaredAmount","approvalAmount","supplierName").contains(f.field()))
                    &&facts.stream().noneMatch(f->activeFields.contains(f.field()));
                // Only adjacent, explicitly labelled fields belong to this record.
                // Never cross prose, a project heading, or a repeated amount role.
                if(subject) {activeRecord=physical;activeFields.clear();}
                else if(!continuation&&!line.isBlank()) {activeRecord=null;activeFields.clear();}
                if(!facts.isEmpty()) {
                    rows.add(new Row(activeRecord!=null&&(subject||continuation)?activeRecord:physical,facts));
                    if(activeRecord!=null)facts.forEach(f->activeFields.add(f.field()));
                }
                layoutOffset+=line.length()+1;
            }
            // Labelled, structured spans are completely read locally. Mixed prose still goes to AI.
            String remainder=LABEL.matcher(body).replaceAll("").replaceAll("[\\s；;|。]+","");
            if(!remainder.isEmpty())semantic.add(segment);
        }
        if(!semantic.isEmpty()) {
            Result result;
            try{result=ai.extract("BUDGET",id,document.sha256(),semantic);}
            catch(RuntimeException unavailable){
                if(Thread.currentThread().isInterrupted())throw unavailable;
                // A failed optional extraction cannot erase independently read text and explicit amounts.
                issues.add(new ExtractionIssue("document","document/extraction","MODEL_EXTRACTION_INCOMPLETE"));
                result=new Result(List.of(),"UNAVAILABLE","budget-partial");
            }
            if(result==null||result.rows()==null||result.rows().size()>10000)throw new IOException("BUDGET_AI_RESULT_INVALID");
            issues.addAll(result.issues());
            var sources=new HashMap<String,String>();for(var s:semantic)sources.put(s.locator(),s.text());
            // Local labelled facts already have a physical row. A model may
            // repeat one using a broad or repeated excerpt; retain the precise
            // local evidence rather than trying to assign that excerpt a row.
            // Different values, roles or locators still require full validation.
            var explicitFacts=rows.stream().flatMap(r->r.facts().stream()).toList();
            var scopes=modelScopes(result.rows(),sources);
            int modelRowNumber=0;
            for(var row:result.rows()) {
                int mentionId=modelRowNumber++;
                if(row==null||row.facts()==null)throw new IOException("BUDGET_AI_RESULT_INVALID");
                var novelFacts=new ArrayList<Fact>();
                var enrichedFacts=new HashMap<Fact,Fact>();
                boolean unsafeIdentity=false;
                for(var fact:row.facts()) {
                  try {
                    String original=fact==null?null:sources.get(fact.locator());
                    if(fact==null||!SUPPORTED_FIELDS.contains(fact.field())||original==null||fact.value()==null||fact.value().isBlank()
                            ||fact.excerpt()==null||fact.excerpt().isBlank()||!original.contains(fact.excerpt())||!fact.excerpt().contains(fact.value()))
                        throw new IOException("BUDGET_AI_EVIDENCE_INVALID");
                    if(missingAmount(fact.field(),fact.value()))continue;
                    // A verbatim person name is still the wrong semantic role.
                    // Discard that optional candidate, retaining the labelled
                    // department and independently verified budget amounts.
                    if(fact.field().equals("departmentName") && Pattern.compile(
                            "(?:申请人|申报人|经办人|联系人|负责人)\\s*[:：]\\s*"+Pattern.quote(fact.value())+"(?:$|[\\s；;，,。|])")
                            .matcher(fact.excerpt()).find()) {
                        org.slf4j.LoggerFactory.getLogger(getClass()).info("BUDGET_AI_FACT_REJECTED field=departmentName reasonCode=PERSON_IS_NOT_DEPARTMENT");
                        continue;
                    }
                    if(fact.field().equals("actualApprovalStep")&&Set.of("同意","批准","通过","审核通过","审批通过").contains(fact.value()))continue;
                    // An explicitly labelled approval date is not an application date.
                    String modelLocator=fact.locator(),modelValue=fact.value();
                    if(fact.field().equals("applicationDate")&&original.contains("审批日期")
                            &&explicitFacts.stream().anyMatch(f->f.field().equals("approvalDate")&&f.value().equals(modelValue)
                            &&f.locator().startsWith(modelLocator+"/")))continue;
                    if(explicitDuplicate(explicitFacts,fact,original)) {
                        // Keep the deterministic value/position, while preserving grounded semantic scope.
                        // Otherwise a locally read label could silently lose the model's historical/annual context.
                        if(fact.subjectScope()!=null||fact.amountScope()!=null) {
                            String modelLine=physicalLine(original,fact.excerpt());
                            for(int n=0;n<rows.size();n++) {
                                var local=rows.get(n);var refined=new ArrayList<Fact>();boolean changed=false;
                                for(var known:local.facts()) {
                                    boolean same=known.field().equals(fact.field())&&known.locator().replaceFirst("/line:[0-9]+$","").equals(fact.locator());
                                    if(same)try{same=physicalLine(original,known.excerpt()).equals(modelLine);}catch(IOException ambiguous){same=false;}
                                    if(same) {
                                        String subject="REFERENCE".equals(known.subjectScope())?"REFERENCE":fact.subjectScope()!=null?fact.subjectScope():known.subjectScope();
                                        refined.add(new Fact(known.field(),known.value(),known.locator(),known.excerpt(),subject,fact.amountScope()!=null?fact.amountScope():known.amountScope()));changed=true;
                                    }else refined.add(known);
                                }
                                if(changed)rows.set(n,new Row(local.rowKey(),List.copyOf(refined)));
                            }
                        }
                        continue;
                    }
                    Scope scope=scopes.get(row);
                    boolean scoped=scope!=null&&scope.locator().equals(fact.locator());
                    fact=scoped?scopeEvidence(original,fact,scope):resolveEvidence(original,fact,row.facts());
                    if(fact.field().endsWith("Amount"))fact=amountEvidence(fact,scoped?original.substring(scope.start(),scope.end()):physicalLine(original,fact.excerpt()));
                    // A model may not replace an explicit amount by another labelled amount.
                    if(Set.of("declaredAmount","approvalAmount").contains(fact.field())) {
                        // Validate against its original physical row as well as the
                        // supplied excerpt: a cropped "5000元" must not conceal
                        // the source's explicit approval label.
                        String physical=scoped?original.substring(scope.start(),scope.end()):physicalLine(original,fact.excerpt());
                        var sourceRoles=ROLE.matcher(physical);boolean knownRole=false,matchingRole=false;
                        while(sourceRoles.find()) {
                            var sourceAmount=roleMoney(sourceRoles.group(2));
                            var candidate=BudgetUploadedMaterialAnalyzer.money(fact.value());
                            if(sourceAmount!=null&&candidate!=null&&sourceAmount.compareTo(candidate)==0) {
                                knownRole=true;
                                String role=sourceRoles.group(1).contains("申请")||sourceRoles.group(1).contains("申报")?"declaredAmount":"approvalAmount";
                                if(role.equals(fact.field()))matchingRole=true;
                            }
                        }
                        if(knownRole&&!matchingRole)throw new IOException("BUDGET_AI_AMOUNT_LABEL_MISMATCH");
                        var labels=LABEL.matcher(fact.excerpt());boolean found=false,matched=false;
                        while(labels.find())if(LABELS.get(labels.group(1)).endsWith("Amount")) {
                            found=true;if(LABELS.get(labels.group(1)).equals(fact.field())&&labels.group(3).strip().equals(fact.value()))matched=true;
                        }
                        if(found&&!matched)throw new IOException("BUDGET_AI_AMOUNT_LABEL_MISMATCH");
                        var roles=ROLE.matcher(fact.excerpt());boolean sawRole=false,correctRole=false;
                        while(roles.find()) {
                            sawRole=true;
                            String role=roles.group(1).contains("申请")||roles.group(1).contains("申报")?"declaredAmount":"approvalAmount";
                            var actual=roleMoney(roles.group(2));
                            var candidate=BudgetUploadedMaterialAnalyzer.money(fact.value());
                            if(role.equals(fact.field())&&actual!=null&&candidate!=null&&actual.compareTo(candidate)==0)correctRole=true;
                        }
                        if(sawRole&&!correctRole)throw new IOException("BUDGET_AI_AMOUNT_LABEL_MISMATCH");
                    }
                    enrichedFacts.put(fact,spreadsheetAmount(fact,original,headers,sheetUnits));
                    novelFacts.add(fact);
                  } catch(IOException rejected) {
                    String field=fact!=null&&SUPPORTED_FIELDS.contains(fact.field())?fact.field():"unknown";
                    String locator=fact!=null&&sources.containsKey(fact.locator())?fact.locator():semantic.get(0).locator();
                    String reason=switch(rejected.getMessage()) {
                        case "BUDGET_AI_ROW_AMBIGUOUS" -> "ROW_UNRESOLVED";
                        case "BUDGET_AI_AMOUNT_LABEL_MISMATCH" -> "AMOUNT_ROLE_UNVERIFIED";
                        default -> "EVIDENCE_UNVERIFIED";
                    };
                    issues.add(new ExtractionIssue(field,locator,reason));
                    if(!field.endsWith("Amount"))unsafeIdentity=true;
                  }
                }
                // Losing an identity must not attach a retained amount to a different
                // document heading. Other independently verified rows remain usable.
                if(unsafeIdentity)continue;
                // A model-local row key is not authority to splice fields from
                // separate source rows/projects. Document headings scope later in the analyzer.
                var located=new LinkedHashMap<String,List<Fact>>();
                for(var fact:novelFacts) {
                    String original=sources.get(fact.locator());
                    Scope scope=scopes.get(row);
                    if(scope!=null&&scope.locator().equals(fact.locator())) {
                        String locator=fact.locator()+"/record:"+scope.start()+":"+scope.end();
                        located.computeIfAbsent(locator,k->new ArrayList<>()).add(new Fact(fact.field(),fact.value(),locator,fact.excerpt(),fact.subjectScope(),fact.amountScope()));
                        continue;
                    }
                    int position=original.indexOf(fact.excerpt());
                    String prefix=original.substring(0,position);
                    int line=prefix.split("\\R",-1).length-1;
                    physicalLine(original,fact.excerpt()); // Reject ambiguous multi-row evidence.
                    String locator=fact.locator().startsWith("excel/")?fact.locator():fact.locator()+"/line:"+line;
                    // A list of subjects beside a document total must not acquire
                    // that total simply because PDF puts them on the same line.
                    if(!fact.locator().startsWith("excel/")&&row.facts().stream().noneMatch(f->f.field().endsWith("Amount"))
                        &&row.facts().stream().anyMatch(f->Set.of("subjectCode","subjectName").contains(f.field())))locator+="/mention:"+mentionId;
                    var enriched=enrichedFacts.get(fact);
                    located.computeIfAbsent(locator,k->new ArrayList<>()).add(enriched==fact
                        ?new Fact(fact.field(),fact.value(),locator,fact.excerpt(),fact.subjectScope(),fact.amountScope()):enriched);
                }
                for(var group:located.entrySet())rows.add(new Row(group.getKey(),List.copyOf(group.getValue())));
            }
        }
        if(rows.size()>10000||issues.size()>2000)throw new IOException("BUDGET_RESULT_LIMIT");
        // Remove exact duplicates only; conflicting evidence must survive.
        var distinct=new ArrayList<Row>();var seen=new HashSet<Row>();
        for(var row:rows)if(seen.add(row))distinct.add(row);
        var repeatedPdfSegments=redundantPdfSegments(document.segments());
        var allFacts=distinct.stream().flatMap(r->r.facts().stream()).toList();
        var reconciledRows=new ArrayList<Row>();
        for(var row:distinct) {
            var kept=new ArrayList<Fact>();
            for(var fact:row.facts()) {
                if(repeatedPdfSegments.stream().anyMatch(locator->fact.locator().startsWith(locator+"/")))continue;
                if(pdfOcrEcho(fact)) {
                    var nativeFacts=allFacts.stream().filter(nativeFact->samePdfPage(nativeFact.locator(),fact.locator())
                        &&nativeFact.field().equals(fact.field())).toList();
                    if(nativeFacts.stream().anyMatch(nativeFact->samePdfValue(nativeFact,fact)))continue;
                    if(Set.of("projectCode","subjectCode").contains(fact.field())&&nativeFacts.stream()
                        .anyMatch(nativeFact->ocrConfusableCode(nativeFact.value(),fact.value()))) {
                        issues.add(new ExtractionIssue(fact.field(),fact.locator().replaceFirst("/line:[0-9]+$",""),"OCR_IDENTITY_UNCERTAIN"));
                        continue;
                    }
                }
                kept.add(fact);
            }
            if(!kept.isEmpty())reconciledRows.add(new Row(row.rowKey(),List.copyOf(kept)));
        }
        distinct=reconciledRows;
        // OCR identifiers must never be silently corrected (O/0 are both legal).
        // Keep the source values and make the uncertainty visible to downstream comparison.
        for(var segment:document.segments())if(segment.locator().contains("/ocr/")||segment.locator().startsWith("image/")) {
            var segmentFacts=distinct.stream().flatMap(r->r.facts().stream()).filter(f->f.locator().startsWith(segment.locator()+"/")).toList();
            var codes=segmentFacts.stream().filter(f->f.field().equals("projectCode")).map(Fact::value).distinct().toList();
            var variants=new HashMap<String,String>();
            boolean uncertain=false;
            for(String code:codes) {
                String prior=variants.putIfAbsent(code.replace('O','0').replace('o','0'),code);
                if(prior!=null&&!prior.equals(code))uncertain=true;
            }
            if(uncertain) {
                issues.add(new ExtractionIssue("projectCode",segment.locator(),"OCR_IDENTITY_UNCERTAIN"));
                if(segmentFacts.stream().filter(f->f.field().equals("projectName")).map(Fact::value).distinct().count()==1) {
                    var safeRows=new ArrayList<Row>();
                    for(var row:distinct) {
                        var kept=row.facts().stream().filter(f->!(f.field().equals("projectCode")&&f.locator().startsWith(segment.locator()+"/"))).toList();
                        if(!kept.isEmpty())safeRows.add(new Row(row.rowKey(),kept));
                    }
                    distinct=safeRows;
                }
            }
        }
        // Merge only after uncertainty filtering so discarded OCR identities cannot
        // re-enter the published snapshot through an earlier aggregate.
        var merged=new LinkedHashMap<String,LinkedHashSet<Fact>>();
        var quoteQuality=visualQuality==null?null:visualQuality.quotes(id,document.sha256(),document.segments());
        for(var row:distinct)for(var fact:row.facts()){
            if(quoteQuality!=null&&quoteQuality.uncertain(fact.locator(),fact.excerpt())){
                issues.add(new ExtractionIssue(fact.field(),fact.locator(),"EVIDENCE_UNVERIFIED"));continue;
            }
            merged.computeIfAbsent(row.rowKey(),k->new LinkedHashSet<>()).add(fact);
        }
        return new BudgetUploadedFacts.Snapshot(merged.entrySet().stream().map(e->new Row(e.getKey(),List.copyOf(e.getValue()))).toList(),List.copyOf(issues),document.segments(),document.sha256(),document.method());
    }

    private static boolean adjacentWordNodes(String previous,String current) {
        if(previous==null)return false;
        var pattern=Pattern.compile("docx/body/node:([0-9]{1,8})/part:[0-9]+");
        var a=pattern.matcher(previous);var b=pattern.matcher(current);
        return a.matches()&&b.matches()&&Integer.parseInt(b.group(1))==Integer.parseInt(a.group(1))+1;
    }

    private static Set<String> redundantPdfSegments(List<Segment> segments) {
        var canonical=new HashMap<String,String>();var duplicates=new HashSet<String>();
        var pdf=Pattern.compile("pdf/page:([0-9]{1,4})/(?:text|ocr)/part:[0-9]{1,5}");
        for(var segment:segments) {
            var match=pdf.matcher(segment.locator());if(!match.matches())continue;
            String priorPage=canonical.putIfAbsent(segment.text().strip(),match.group(1));
            if(priorPage!=null&&!priorPage.equals(match.group(1)))duplicates.add(segment.locator());
        }
        return duplicates;
    }
    private static boolean pdfOcrEcho(Fact fact) {
        return fact.locator().startsWith("pdf/page:")&&fact.locator().contains("/ocr/");
    }
    private static boolean samePdfPage(String a,String b) {
        var page=Pattern.compile("^pdf/page:([0-9]{1,4})/");var left=page.matcher(a);var right=page.matcher(b);
        return left.find()&&right.find()&&left.group(1).equals(right.group(1))
            &&a.contains("/text/")&&b.contains("/ocr/");
    }
    private static boolean samePdfValue(Fact nativeFact,Fact ocrFact) {
        if(nativeFact.value().equals(ocrFact.value()))return true;
        return nativeFact.field().endsWith("Amount")&&sameExplicitMoney(nativeFact,ocrFact);
    }
    private static boolean sameExplicitMoney(Fact explicit,Fact candidate) {
        var labels=LABEL.matcher(explicit.excerpt());String explicitUnit=null;boolean matchingLabel=false;
        while(labels.find())if(LABELS.get(labels.group(1)).equals(explicit.field())) {
            matchingLabel=true;if(labels.group(2)!=null)explicitUnit=labels.group(2);
        }
        if(!matchingLabel)return false;
        var expected=BudgetUploadedMaterialAnalyzer.money(explicit.value());
        var actual=BudgetUploadedMaterialAnalyzer.money(withUnit(candidate.value(),explicitUnit));
        return expected!=null&&actual!=null&&expected.compareTo(actual)==0;
    }
    private static boolean ocrConfusableCode(String a,String b) {
        return !a.equals(b)&&a.replace('O','0').replace('o','0').equals(b.replace('O','0').replace('o','0'));
    }

    private static boolean explicitDuplicate(List<Fact> explicitFacts,Fact fact,String source) {
        if(fact.locator()==null||fact.excerpt()==null||source==null)return false;
        String candidateLine;
        try {candidateLine=physicalLine(source,fact.excerpt());}catch(IOException unresolved){return false;}
        var sameLine=explicitFacts.stream().filter(known->known.field().equals(fact.field())
            &&known.locator().replaceFirst("/line:[0-9]+$","").equals(fact.locator()))
            .filter(known->{try{return physicalLine(source,known.excerpt()).equals(candidateLine);}catch(IOException unresolved){return false;}}).toList();
        if(sameLine.size()!=1)return false;
        var known=sameLine.get(0);
        if(known.value().equals(fact.value()))return true;
        if(!fact.field().endsWith("Amount"))return false;
        var labels=LABEL.matcher(known.excerpt());String explicitUnit=null;boolean matchingLabel=false;
        while(labels.find())if(LABELS.get(labels.group(1)).equals(fact.field())) {
            matchingLabel=true;if(labels.group(2)!=null)explicitUnit=labels.group(2);
        }
        if(!matchingLabel)return false;
        var knownAmount=BudgetUploadedMaterialAnalyzer.money(known.value());
        var candidateAmount=BudgetUploadedMaterialAnalyzer.money(withUnit(fact.value(),explicitUnit));
        return knownAmount!=null&&candidateAmount!=null&&knownAmount.compareTo(candidateAmount)==0;
    }
    private record Scope(String locator,int start,int end) {}
    private static java.math.BigDecimal roleMoney(String source) {
        return BudgetUploadedMaterialAnalyzer.money(source.replaceFirst("(?<=[元圆圓整正角分])[,，]+$",""));
    }
    private static Map<Row,Scope> modelScopes(List<Row> modelRows,Map<String,String> sources) {
        var anchors=new LinkedHashMap<Row,Integer>();var locators=new HashMap<Row,String>();
        for(var row:modelRows) {
            if(row==null||row.facts()==null||row.facts().isEmpty())continue;
            // Each scope needs an independently unique subject in the source.
            // A row key itself never establishes a record or project boundary.
            if(row.facts().stream().filter(f->f!=null&&Set.of("subjectName","projectName").contains(f.field()))
                .collect(java.util.stream.Collectors.groupingBy(Fact::field,java.util.stream.Collectors.mapping(Fact::value,java.util.stream.Collectors.toSet())))
                .values().stream().anyMatch(v->v.size()>1))continue;
            String anchorType=row.facts().stream().anyMatch(f->f!=null&&f.field().equals("subjectName"))?"subjectName"
                    :row.facts().stream().anyMatch(f->f!=null&&f.field().equals("requiredApprovalStep"))?"requiredApprovalStep":"actualApprovalStep";
            for(var fact:row.facts())if(fact!=null&&fact.field().equals(anchorType)) {
                if(fact.locator()!=null&&fact.locator().startsWith("excel/"))continue;
                String source=sources.get(fact.locator());
                if(source==null||fact.value()==null||fact.excerpt()==null||!source.contains(fact.excerpt())||!fact.excerpt().contains(fact.value()))continue;
                int start=source.indexOf(fact.value());
                if(start>=0&&source.indexOf(fact.value(),start+1)<0) {anchors.put(row,start);locators.put(row,fact.locator());}
            }
        }
        var result=new HashMap<Row,Scope>();
        for(var entry:anchors.entrySet()) {
            Row row=entry.getKey();String locator=locators.get(row),source=sources.get(locator);int anchor=entry.getValue();
            int end=source.length();
            for(var other:anchors.entrySet())if(!other.getKey().equals(row)&&locators.get(other.getKey()).equals(locator)&&other.getValue()>anchor)end=Math.min(end,other.getValue());
            int first=anchor,last=anchor;boolean proved=true;
            for(var fact:row.facts()) {
                if(fact==null||!locator.equals(fact.locator())||fact.value()==null||fact.excerpt()==null||!fact.excerpt().contains(fact.value())){proved=false;break;}
                // Prefer the quoted context to disambiguate repeated project codes,
                // suppliers and subject codes in vertically extracted OCR tables.
                var positions=new ArrayList<Integer>();
                int lineStart=Math.max(source.lastIndexOf('\n',anchor-1),source.lastIndexOf('\r',anchor-1))+1;
                int lower=Set.of("subjectCode","subjectName").contains(fact.field())?Math.max(0,source.lastIndexOf('\n',Math.max(0,lineStart-2))+1):lineStart;
                for(int at=source.indexOf(fact.excerpt());at>=0;at=source.indexOf(fact.excerpt(),at+1))
                    if(at>=lower&&at+fact.excerpt().length()<=end)positions.add(at);
                if(positions.size()!=1){proved=false;break;}
                int at=positions.get(0);first=Math.min(first,at);last=Math.max(last,at+fact.excerpt().length());
            }
            if(proved&&last-first<=1000) {
                // Include the source's trailing currency unit and role wording,
                // even when the model cropped its quotation to a bare number.
                while(last<end&&source.charAt(last)!='\n'&&source.charAt(last)!='\r')last++;
                if(last-first>1000)continue;
                String slice=source.substring(first,last);
                // Do not cross another model-observed subject/project identity.
                for(var other:modelRows)if(other!=row&&other!=null&&other.facts()!=null)for(var fact:other.facts())
                    if(fact!=null&&locator.equals(fact.locator())&&Set.of("subjectName","projectName").contains(fact.field())
                        &&fact.value()!=null&&slice.contains(fact.value())&&row.facts().stream().noneMatch(f->f.field().equals(fact.field())&&f.value().equals(fact.value())))proved=false;
                if(proved)result.put(row,new Scope(locator,first,last));
            }
        }
        return result;
    }
    private static Fact scopeEvidence(String source,Fact fact,Scope scope)throws IOException {
        String region=source.substring(scope.start(),scope.end());
        if(!region.contains(fact.excerpt())||!fact.excerpt().contains(fact.value()))throw new IOException("BUDGET_AI_EVIDENCE_INVALID");
        return fact;
    }
    private static Fact amountEvidence(Fact fact,String context)throws IOException {
        // A substring such as 16800 in 168000 is not the cited amount.
        var token=Pattern.compile("(?<![0-9０-９])"+Pattern.quote(fact.value())+"(?![0-9０-９]|[.,，．][0-9０-９])");
        if(!token.matcher(context).find())throw new IOException("BUDGET_AI_EVIDENCE_INVALID");
        if(!fact.value().matches("[0-9０-９,.，．+ ]+"))return fact;
        var units=Pattern.compile("(?<![0-9０-９])"+Pattern.quote(fact.value())+"(?:[ \\t]*(?:万元|千元|元|圆|圓))").matcher(context);
        var values=new LinkedHashSet<String>();while(units.find())values.add(units.group());
        if(values.size()>1)throw new IOException("BUDGET_AI_ROW_AMBIGUOUS");
        if(values.isEmpty())return fact;
        String value=values.iterator().next();
        return new Fact(fact.field(),value,fact.locator(),fact.excerpt().contains(value)?fact.excerpt():context,fact.subjectScope(),fact.amountScope());
    }
    private static Fact spreadsheetAmount(Fact fact,String source,Map<String,List<String>> headers,Map<String,String> sheetUnits)throws IOException {
        if(!fact.field().endsWith("Amount")||!fact.locator().startsWith("excel/")||!fact.locator().contains("/row:"))return fact;
        String sheet=fact.locator().substring(0,fact.locator().lastIndexOf("/row:"));
        var labels=headers.get(sheet);var cells=Arrays.asList(source.split("\\s*\\|\\s*",-1));
        if(labels==null||labels.size()!=cells.size())return fact;
        var columns=new ArrayList<Integer>();
        for(int i=0;i<cells.size();i++)if(cells.get(i).strip().equals(fact.value().strip()))columns.add(i);
        if(columns.size()!=1)return fact; // Repeated numeric cells do not prove a column.
        String label=labels.get(columns.get(0)),role=LABELS.get(labelName(label));
        if(role!=null&&role.endsWith("Amount")&&!role.equals(fact.field())
                ||label.matches(".*(?:单价|总计|合计|总预算|预算总额|最高限价).*"))
            throw new IOException("BUDGET_AI_AMOUNT_LABEL_MISMATCH");
        String declaredUnit=unit(label)==null?sheetUnits.get(sheet):unit(label);
        String value=withUnit(fact.value(),declaredUnit);
        // The source column and its real header together establish the unit.
        // This follows the deterministic table path's original-unit convention.
        return new Fact(fact.field(),value,fact.locator()+"/col:"+(columns.get(0)+1),label+"："+value,fact.subjectScope(),fact.amountScope());
    }
    private static Fact singleLineEvidence(String source,Fact fact)throws IOException {
        if(fact.excerpt().indexOf('\n')<0&&fact.excerpt().indexOf('\r')<0)return fact;
        // A model may quote a paragraph instead of the exact line. Narrow only
        // when the verbatim value belongs to exactly one physical source line.
        // Repeated values and multi-line values remain ambiguous; never join rows.
        var lines=Arrays.stream(fact.excerpt().split("\\R",-1)).filter(line->line.contains(fact.value())).toList();
        if(lines.size()!=1)throw new IOException("BUDGET_AI_ROW_AMBIGUOUS");
        String excerpt=lines.get(0);
        physicalLine(source,excerpt);
        return new Fact(fact.field(),fact.value(),fact.locator(),excerpt,fact.subjectScope(),fact.amountScope());
    }

    private static Fact resolveEvidence(String source,Fact fact,List<Fact> peers)throws IOException {
        try {var narrowed=singleLineEvidence(source,fact);physicalLine(source,narrowed.excerpt());return narrowed;}
        catch(IOException ambiguity) {
            if(!"BUDGET_AI_ROW_AMBIGUOUS".equals(ambiguity.getMessage()))throw ambiguity;
            // A repeated short name can be localized by another independently unique
            // quotation on the SAME physical line, never by the model's row key alone.
            var candidates=new LinkedHashSet<String>();
            for(var peer:peers) {
                if(peer==null||peer==fact||!Objects.equals(peer.locator(),fact.locator())
                        ||peer.excerpt()==null||peer.value()==null||!peer.excerpt().contains(peer.value()))continue;
                try {
                    var anchor=singleLineEvidence(source,peer);
                    String line=physicalLine(source,anchor.excerpt());
                    if(line.contains(fact.value())&&(line.contains(fact.excerpt())||fact.excerpt().contains(line)))candidates.add(line);
                }catch(IOException unresolved) { /* A non-unique peer supplies no authority. */ }
            }
            if(candidates.size()!=1)throw ambiguity;
            String line=candidates.iterator().next();physicalLine(source,line);
            return new Fact(fact.field(),fact.value(),fact.locator(),line,fact.subjectScope(),fact.amountScope());
        }
    }

    private static String physicalLine(String source,String excerpt)throws IOException {
        int start=source.indexOf(excerpt);
        if(start<0)throw new IOException("BUDGET_AI_EVIDENCE_INVALID");
        if(excerpt.indexOf('\n')>=0||excerpt.indexOf('\r')>=0)
            throw new IOException("BUDGET_AI_ROW_AMBIGUOUS");
        int left=Math.max(source.lastIndexOf('\n',start-1),source.lastIndexOf('\r',start-1))+1;
        int right=source.length();
        for(char end:new char[]{'\n','\r'}) {int at=source.indexOf(end,start);if(at>=0)right=Math.min(right,at);}
        for(int next=source.indexOf(excerpt,start+1);next>=0;next=source.indexOf(excerpt,next+1))
            if(next>=right)throw new IOException("BUDGET_AI_ROW_AMBIGUOUS");
        return source.substring(left,right);
    }
}
