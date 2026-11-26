package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.common.domain.RmbAmountParser;
import com.fuquan.audit.procurement.application.TenderRuleMatcher;
import com.fuquan.audit.procurement.application.port.ProcurementTenderRuleRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;
import static com.fuquan.audit.procurement.autocase.CaseReviewAiPort.*;

@Service
public class CaseReviewEngine {
    private final CaseReviewAiPort ai;private final ProcurementTenderRuleRepository rules;private final Clock clock;private final long timeoutMs;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"case-semantic-review");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public CaseReviewEngine(CaseReviewAiPort ai,ProcurementTenderRuleRepository rules,Clock clock,
            @Value("${audit.procurement.auto-case.review-timeout-ms:180000}") long timeoutMs) {
        this.ai=ai;this.rules=rules;this.clock=clock;this.timeoutMs=Math.max(1,Math.min(timeoutMs,300000));
    }
    @jakarta.annotation.PreDestroy public void close(){executor.shutdownNow();}
    public Request prepare(UUID id,String module,List<Document> docs) {
        var context=CaseReviewContext.select(module,docs);
        var facts=new ArrayList<InputFact>();
        for(var d:context.documents())for(String type:CaseFactPolicy.TYPES)for(var f:CaseFactPolicy.facts(d,type)) {
            var evidence=CaseReviewContext.factEvidence(context,d,f);
            if(!evidence.isEmpty())facts.add(new InputFact(d.id(),f.row(),f.type(),f.value(),evidence.stream().map(SourceEvidence::id).toList(),f.subjectScope(),f.amountScope()));
        }
        var inventory=docs.stream().map(d->new UploadedDocument(d.id(),d.name(),CaseReviewContext.role(d),d.status(),
                d.segments().stream().anyMatch(s->!s.text().isBlank()))).toList();
        var checks=new ArrayList<ReviewCheck>();
        if(module.equals("AGENT_PRECHECK"))checks.addAll(deterministic(context,docs));
        checks.add(new ReviewCheck("MATERIAL_COVERAGE","SYSTEM_SCOPE",
                "本批已保存 "+inventory.size()+" 份材料，本项选取 "+context.scope().size()+" 份正文进行分析。"
                +"未选入本项的材料仍保留在本批目录中，不据此认定未上传；分类或正文不完整时，仅对可核实范围出具结果。",List.of()));
        var signals=new ArrayList<RuleSignal>();
        if(module.equals("TENDER_COMPLIANCE"))for(var rule:rules.findEnabled())for(var e:context.evidence()) {
            if(context.scope().stream().anyMatch(s->s.documentId().equals(e.documentId())&&Set.of("BID","EVALUATION_REPORT").contains(s.role())))continue;
            try {if(TenderRuleMatcher.hasAffirmativeMatch(rule,e.excerpt())) {
                signals.add(new RuleSignal(rule.ruleCode(),rule.ruleName(),e.id(),rule.ruleExplanation()));
                checks.add(new ReviewCheck("F10_RULE:"+rule.ruleCode(),"RULE_CANDIDATE",
                        rule.ruleName()+"："+rule.ruleExplanation()+"；依据："+rule.legalBasis()+"；建议："+rule.remediationSuggestion(),List.of(e)));
            }}
            catch(java.util.regex.PatternSyntaxException invalid){throw new IllegalStateException("F10_RULE_CONFIGURATION_INVALID",invalid);}
        }
        var topics=CaseReviewContext.requiredTopics(module,context.evidence());
        String digest=AuditCaseService.hash(CaseReviewContext.VERSION+module+context.scope()+context.evidence()+facts+checks+signals+inventory+topics);
        return new Request(id,module,digest,CaseReviewContext.VERSION,context.scope(),context.evidence(),List.copyOf(facts),List.copyOf(checks),List.copyOf(signals),inventory,topics);
    }
    public ModuleReview pending(Request input,List<Document> docs) {
        return new ModuleReview("PENDING",input.inputDigest(),input.version(),null,null,null,input.documents(),
                CaseReviewContext.select(input.module(),docs).gaps(),input.checks(),deterministicFindings(input.checks()),0,null);
    }
    public ModuleReview running(ModuleReview value) {
        return state(value,"RUNNING",null,null,value.findings(),value.checks(),0,null);
    }
    public ModuleReview unknown(ModuleReview value,String error) {
        return state(value,"UNKNOWN",value.model(),clock.instant(),value.findings(),value.checks(),value.rejectedCitations(),error);
    }
    public record ReviewCall(Request input,ModuleReview running,Future<Result> future,long startedNanos,ModuleReview immediate) implements AutoCloseable {
        @Override public void close(){if(future!=null&&!future.isDone())future.cancel(true);}
    }
    /** Submit only after its durable RUNNING checkpoint has been committed. */
    public ReviewCall start(Request input,ModuleReview running) {
        long started=System.nanoTime();
        if(input.evidence().isEmpty())return new ReviewCall(input,running,null,started,state(running,"DATA_INSUFFICIENT",null,clock.instant(),running.findings(),running.checks(),0,"NO_READABLE_SOURCE"));
        var cancellation=com.fuquan.audit.common.application.TaskCancellationRegistry.child();
        try {return new ReviewCall(input,running,executor.submit(()->{try(var registration=cancellation.get()){
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            return ai.analyze(input);
        }}),started,null);}
        catch(RejectedExecutionException busy){return new ReviewCall(input,running,null,started,unknown(running,"MODEL_CAPACITY_BUSY"));}
    }
    public ModuleReview execute(Request input,ModuleReview running) {
        try(var call=start(input,running)){return await(call);}
    }
    public ModuleReview await(ReviewCall call) {
        if(call.immediate()!=null)return call.immediate();
        var input=call.input();var running=call.running();var future=call.future();
        try {
            long remaining=Math.max(1,timeoutMs-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-call.startedNanos()));
            Result result=future.get(remaining,TimeUnit.MILLISECONDS);
            if(result==null||result.model()==null||result.model().isBlank()||!input.version().equals(result.version())||result.findings()==null||result.observations()==null
                    ||result.findings().size()>40||result.observations().size()>40)throw new IllegalArgumentException("invalid semantic envelope");
            var catalog=new HashMap<String,SourceEvidence>();input.evidence().forEach(e->catalog.put(e.id(),e));
            var findings=new ArrayList<>(running.findings());var checks=new ArrayList<>(running.checks());int rejected=0;
            for(var candidate:result.findings()) {
                var evidence=resolve(candidate.evidenceIds(),catalog);
                if(duplicatesExactCeilingCalculation(candidate,evidence,input.checks()))continue;
                // Award/quote arithmetic is system-owned. A valid quotation and
                // budget do not establish that an award even exists. Keep the
                // deterministic finding once; never relabel excess budget as an award mismatch.
                if("AWARD_QUOTE_MISMATCH".equals(candidate.code())) {
                    if(input.checks().stream().noneMatch(c->c.code().equals(candidate.code())&&c.status().equals("CONFLICT")))rejected++;
                    continue;
                }
                String text=candidate.title()+" "+candidate.explanation()+" "+candidate.suggestion();
                // A missing uploaded comparator is a scope limitation, not a
                // source-proven risk. Keep all independent available observations.
                boolean cross=Set.of("IDENTITY_CONFLICT","BUDGET_CONFLICT","QUANTITY_CONFLICT","AWARD_QUOTE_MISMATCH","PAYMENT_CONFLICT","CLARIFICATION_CONFLICT","BUSINESS_CONFLICT").contains(candidate.code());
                if(evidence.isEmpty()||cross&&evidence.size()<2
                        ||!CODES.contains(candidate.code())||!Set.of("INFO","WARNING","CRITICAL").contains(Objects.toString(candidate.level(),""))
                        ||candidate.basis()!=null&&!Set.of("CLAUSE_ISSUE","MATERIAL_SCOPE","IMPROVEMENT").contains(candidate.basis())
                        ||!validText(candidate.title(),200)||!validText(candidate.explanation(),2000)||!validText(candidate.suggestion(),800)
                        ||text.matches("(?s).*(违法|违规已确认|串标成立|现实中不存在|已经验收完成|已经支付完成).*")
                        ||contradictedMaterialAbsence(text,input.uploadedDocuments())) {
                    rejected++;
                    // Only schema-owned metadata; neither document names, source
                    // excerpts nor model explanations belong in technical logs.
                    org.slf4j.LoggerFactory.getLogger(CaseReviewEngine.class).info(
                            "CASE_FINDING_REJECTED module={} code={} reason={}",input.module(),
                            CODES.contains(Objects.toString(candidate.code(),""))?candidate.code():"UNSUPPORTED",
                            evidence.isEmpty()?"CITATION_INVALID":cross&&evidence.size()<2?"CROSS_REFERENCE_INCOMPLETE":
                            !CODES.contains(Objects.toString(candidate.code(),""))?"CODE_INVALID":
                            text.matches("(?s).*(违法|违规已确认|串标成立|现实中不存在|已经验收完成|已经支付完成).*")?"UNSUPPORTED_ASSERTION":
                            contradictedMaterialAbsence(text,input.uploadedDocuments())?"UPLOADED_MATERIAL_PRESENT":"TEXT_OR_LEVEL_INVALID");
                    continue;
                }
                if(ocrOnlyProjectNumberVariant(candidate,evidence)) {
                    rejected++;
                    checks.add(new ReviewCheck("IDENTITY_OCR_READING","SOURCE_GROUNDED_OBSERVATION",
                            "扫描材料中的项目编号存在 O/0 识别差异，尚不能据此认定原文件编号有误。",evidence));
                    org.slf4j.LoggerFactory.getLogger(CaseReviewEngine.class).info(
                            "CASE_FINDING_REJECTED module={} code={} reason=OCR_VARIANT_UNCERTAIN",input.module(),candidate.code());
                    continue;
                }
                if("MATERIAL_SCOPE".equals(candidate.basis())||CaseAvailableResults.scopeOnly(text,evidence))continue;
                var scoring=CaseInspectionScoring.compatibleUpgrade(candidate,input,evidence);
                if(scoring!=null){checks.add(scoring);continue;}
                if("INFO".equals(candidate.level())||"IMPROVEMENT".equals(candidate.basis()))
                    checks.add(new ReviewCheck(candidate.code(),"SOURCE_GROUNDED_OBSERVATION",candidate.explanation(),evidence));
                else findings.add(new ReviewFinding(candidate.code(),candidate.level(),candidate.title(),candidate.explanation(),candidate.suggestion(),evidence,"MODEL"));
            }
            for(var observation:result.observations()) {
                var evidence=resolve(observation.evidenceIds(),catalog);
                if(evidence.isEmpty()||!validText(observation.explanation(),2000)||!OBSERVATIONS.contains(observation.code())
                        ||observation.explanation().matches("(?s).*(违法|全部合规|全部通过|已经验收完成|已经支付完成).*")
                        ||contradictedMaterialAbsence(observation.explanation(),input.uploadedDocuments())){rejected++;continue;}
                if(CaseAvailableResults.scopeOnly(observation.explanation(),evidence))continue;
                checks.add(new ReviewCheck(observation.code(),"SOURCE_GROUNDED_OBSERVATION",observation.explanation(),evidence));
            }
            var required=new LinkedHashSet<>(input.requiredTopics());
            var observed=checks.stream().filter(c->c.status().equals("SOURCE_GROUNDED_OBSERVATION")).map(ReviewCheck::code).collect(java.util.stream.Collectors.toSet());
            observed.addAll(deterministicallyCoveredTopics(checks));
            // A supported concrete issue also proves that its subject was reviewed;
            // do not demand a duplicate positive observation for the same issue.
            for(var finding:findings)observed.addAll(topicsForFinding(finding.code()));
            boolean complete=result.complete()&&rejected==0&&running.coverageGaps().isEmpty()&&observed.containsAll(required);
            var missing=new TreeSet<>(required);missing.removeAll(observed);
            String reason=complete?null:!running.coverageGaps().isEmpty()?"SOURCE_COVERAGE_INCOMPLETE":rejected>0?"MODEL_ITEMS_REJECTED":!missing.isEmpty()?"MODEL_TOPICS_INCOMPLETE":"MODEL_REPORTED_INCOMPLETE";
            org.slf4j.LoggerFactory.getLogger(CaseReviewEngine.class).info(
                    "CASE_REVIEW_COVERAGE module={} outcome={} reason={} requiredTopics={} missingTopics={} rejectedCount={} sourceGapCount={} modelComplete={}",
                    input.module(),complete?"COMPLETED":"PARTIAL",reason,required,missing,rejected,running.coverageGaps().size(),result.complete());
            return state(running,complete?"COMPLETED":"PARTIAL",result.model(),clock.instant(),findings,checks,rejected,reason);
        }catch(TimeoutException timeout){future.cancel(true);return unknown(running,"MODEL_TIMEOUT_OUTCOME_UNKNOWN");}
        catch(InterruptedException interrupted){future.cancel(true);Thread.currentThread().interrupt();return unknown(running,"MODEL_INTERRUPTED_OUTCOME_UNKNOWN");}
        catch(ExecutionException failure){return unknown(running,"MODEL_CALL_FAILED");}
        catch(RuntimeException invalid){return state(running,"FAILED",null,clock.instant(),running.findings(),running.checks(),0,"MODEL_INVALID_RESPONSE");}
    }
    private static final Set<String> CODES=Set.of("IDENTITY_CONFLICT","BUDGET_CONFLICT","QUANTITY_CONFLICT","AWARD_QUOTE_MISMATCH","DATE_SEQUENCE","TECHNICAL_AMBIGUITY","RESTRICTIVE_CONDITION","SCORE_INCONSISTENCY","EVALUATION_BASIS","QUOTE_SCOPE","PAYMENT_CONFLICT","CLARIFICATION_CONFLICT","CLAUSE_AMBIGUITY","PROCEDURAL_EVIDENCE","BUSINESS_CONFLICT");
    private static final Set<String> OBSERVATIONS=Set.of("IDENTITY","BUDGET_VS_AWARD","QUANTITY","DATE_AND_PLAN","TECHNICAL_REQUIREMENTS","SCORING_METHOD","PAYMENT_CLARIFICATION");
    private static Set<String> topicsForFinding(String code) {
        return switch(code) {
            case "IDENTITY_CONFLICT" -> Set.of("IDENTITY");
            case "BUDGET_CONFLICT","AWARD_QUOTE_MISMATCH","QUOTE_BUDGET","QUOTE_CEILING","QUOTE_LINE_ARITHMETIC" -> Set.of("BUDGET_VS_AWARD");
            case "QUANTITY_CONFLICT" -> Set.of("QUANTITY");
            case "DATE_SEQUENCE" -> Set.of("DATE_AND_PLAN");
            case "TECHNICAL_AMBIGUITY","RESTRICTIVE_CONDITION","QUOTE_SCOPE" -> Set.of("TECHNICAL_REQUIREMENTS");
            case "SCORE_INCONSISTENCY","EVALUATION_BASIS" -> Set.of("SCORING_METHOD");
            case "PAYMENT_CONFLICT","CLARIFICATION_CONFLICT" -> Set.of("PAYMENT_CLARIFICATION");
            default -> Set.of();
        };
    }
    static Set<String> deterministicallyCoveredTopics(List<ReviewCheck> checks) {
        if(checks==null)return Set.of();
        boolean identity=checks.stream().anyMatch(c->c.code().startsWith("IDENTITY_")
                &&!Set.of("UNKNOWN","NOT_EVALUATED","SYSTEM_SCOPE").contains(c.status())&&!c.evidence().isEmpty());
        return identity?Set.of("IDENTITY"):Set.of();
    }
    private static boolean duplicatesExactCeilingCalculation(Candidate candidate,List<SourceEvidence> evidence,List<ReviewCheck> checks) {
        if(!"BUDGET_CONFLICT".equals(candidate.code())||evidence.isEmpty()
                ||!java.util.regex.Pattern.compile("最高限价|限价|控制价").matcher(candidate.title()+" "+candidate.explanation()).find())return false;
        var cited=evidence.stream().map(e->e.documentId()+"/"+e.locator()+"/"+e.excerpt()).collect(java.util.stream.Collectors.toSet());
        return checks.stream().filter(c->c.code().equals("QUOTE_CEILING")&&c.status().equals("CONFLICT")&&!c.evidence().isEmpty())
                .anyMatch(c->c.evidence().stream().map(e->e.documentId()+"/"+e.locator()+"/"+e.excerpt()).allMatch(cited::contains));
    }
    /** Uploaded inventory is system-owned, never inferred from a selected source block. */
    static boolean contradictedMaterialAbsence(String text,List<UploadedDocument> inventory) {
        var terms=Map.of("QUOTATION","报价单|询价单","BUDGET","预算批复|批复文件","DEMAND","需求申请书|需求申请表",
                "TECHNICAL","技术需求表|技术需求文件","EVALUATION","评审办法|评分办法|评分细则","CLARIFICATION","澄清文件|澄清答复","AWARD","中标通知书|成交通知书","TENDER","招标文件|采购文件");
        for(var entry:terms.entrySet())if(inventory.stream().anyMatch(d->d.role().equals(entry.getKey()))) {
            String role="(?:"+entry.getValue()+")";
            // A document can exist while lacking a scoring standard or clause.
            // "采购文件未提供评分细则" describes the contents, not a missing file.
            String modifiers="(?:(?:本批|本次|对应|相关|完整|正式|有效|独立|单独|相应|所需|可用|该项目|任何|一份|的|\\s)){0,12}";
            if(java.util.regex.Pattern.compile("(?:未提供|未上传|没有上传|缺少|缺乏|缺失|未见)"+modifiers+role
                    +"|"+role+"(?:均|都|尚|仍|并|还|全部|的|\\s){0,5}(?:未提供|未上传|没有上传|缺失)(?=$|[。；;，,\\n])").matcher(text).find())return true;
        }
        return false;
    }
    static boolean ocrOnlyProjectNumberVariant(Candidate candidate,List<SourceEvidence> evidence) {
        if(!Set.of("IDENTITY_CONFLICT","CLARIFICATION_CONFLICT").contains(candidate.code())
                ||!Objects.toString(candidate.title(),"").contains("项目编号")||evidence.size()<2)return false;
        String claim=Objects.toString(candidate.title(),"")+Objects.toString(candidate.explanation(),"");
        // Mixed business issues must never be hidden merely because a cited
        // page happens to contain an OCR variant in its heading.
        if(java.util.regex.Pattern.compile("金额|报价|价格|预算|限价|付款|期限|月份|数量|评分|资格|品牌|地域").matcher(claim).find())return false;
        var nativeNumbers=new HashSet<String>();var ocrNumbers=new HashSet<String>();
        var pattern=java.util.regex.Pattern.compile("项目编号\\s*[：:]\\s*([A-Za-z0-9]+(?:-[A-Za-z0-9]+)+)(?=$|[\\s。；;，,）)])");
        for(var e:evidence) {
            var matcher=pattern.matcher(e.excerpt());
            while(matcher.find())(e.locator().contains("/ocr/")?ocrNumbers:nativeNumbers).add(matcher.group(1));
        }
        if(nativeNumbers.size()!=1||ocrNumbers.size()!=1)return false;
        String nativeValue=nativeNumbers.iterator().next(),ocrValue=ocrNumbers.iterator().next();
        if(nativeValue.length()<8||nativeValue.length()!=ocrValue.length())return false;
        int different=0;
        for(int i=0;i<nativeValue.length();i++)if(nativeValue.charAt(i)!=ocrValue.charAt(i)) {
            if(!((nativeValue.charAt(i)=='O'&&ocrValue.charAt(i)=='0')||(nativeValue.charAt(i)=='0'&&ocrValue.charAt(i)=='O')))return false;
            different++;
        }
        return different==1;
    }
    private static boolean validText(String s,int max){return s!=null&&!s.isBlank()&&s.length()<=max;}
    private static List<SourceEvidence> resolve(List<String> ids,Map<String,SourceEvidence> catalog) {
        if(ids==null||ids.isEmpty()||ids.size()>12||ids.stream().anyMatch(id->!catalog.containsKey(id)))return List.of();
        return ids.stream().distinct().map(catalog::get).toList();
    }
    private ModuleReview state(ModuleReview v,String status,String model,Instant end,List<ReviewFinding> findings,List<ReviewCheck> checks,int rejected,String error) {
        return new ModuleReview(status,v.inputDigest(),v.version(),model,v.startedAt()==null?clock.instant():v.startedAt(),end,v.scope(),v.coverageGaps(),checks,findings,rejected,error);
    }
    private static List<ReviewFinding> deterministicFindings(List<ReviewCheck> checks) {
        return checks.stream().filter(c->(c.status().equals("CONFLICT")||c.status().equals("STATISTICAL_SIGNAL"))&&!c.evidence().isEmpty()).map(c->new ReviewFinding(c.code(),
                "WARNING",c.code().equals("QUOTE_CEILING")?"报价超过最高限价":c.code().equals("QUOTE_BUDGET")?"报价超过预算":c.code().equals("QUOTE_LINE_ARITHMETIC")?"报价明细计算不一致":c.code().equals("QUOTE_CONCENTRATION")?"供应商报价较集中":c.code().equals("AWARD_QUOTE_MISMATCH")?"中标金额与同一供应商报价不一致":"金额或数量存在差异",c.explanation(),
                "核对所列金额、数量及其对应采购范围。",c.evidence(),"DETERMINISTIC")).toList();
    }
    private static List<ReviewCheck> deterministic(CaseReviewContext.Context ctx,List<Document> docs) {
        var checks=new ArrayList<ReviewCheck>();
        for(String type:List.of("projectName","projectNo","purchaserName")) {
            var values=new TreeMap<String,List<SourceEvidence>>();
            for(var d:docs)for(var f:CaseFactPolicy.facts(d,type)) {
                var e=CaseReviewContext.factEvidence(ctx,d,f);if(!e.isEmpty())values.computeIfAbsent(f.value().replaceAll("\\s+",""),x->new ArrayList<>()).addAll(e);
            }
            checks.add(new ReviewCheck("IDENTITY_"+type,values.size()>1?"SEMANTIC_REVIEW_REQUIRED":values.isEmpty()?"UNKNOWN":"CONSISTENT_IN_OBSERVED",
                    CaseReviewText.identityLabel(type)+"："+String.join(" / ",values.keySet()),values.values().stream().flatMap(List::stream).distinct().toList()));
        }
        var budgets=new TreeMap<BigDecimal,List<SourceEvidence>>();
        for(var d:docs)for(var f:CaseFactPolicy.totals(d,"budgetAmount"))ProcurementMaterialSemantics.money(d,f).ifPresent(v->{var e=CaseReviewContext.factEvidence(ctx,d,f);if(!e.isEmpty())budgets.computeIfAbsent(v,x->new ArrayList<>()).addAll(e);});
        for(var d:docs)for(String type:List.of("budgetAmount","ceilingAmount","quoteAmount"))for(var f:CaseFactPolicy.facts(d,type))if(!ProcurementMaterialSemantics.total(f)){
            var ev=CaseReviewContext.factEvidence(ctx,d,f);
            if(!ev.isEmpty())checks.add(new ReviewCheck("PRICE_BASIS","SOURCE_GROUNDED_OBSERVATION",
                    ("ANNUAL".equals(f.amountScope())?"年度金额":"PACKAGE".equals(f.amountScope())?"分包金额":"单位或范围尚不明确的金额")+"："+f.value()+"；保留原文计价范围，未直接与项目总预算比较。",ev));
        }
        checks.add(new ReviewCheck("BUDGET_CONFLICT",budgets.size()>1?"SEMANTIC_REVIEW_REQUIRED":budgets.isEmpty()?"UNKNOWN":"CONSISTENT_IN_OBSERVED",
                "仅比较预算角色金额，不将成交价或已付款作为预算："+budgets.keySet(),budgets.values().stream().flatMap(List::stream).distinct().toList()));
        var quantities=new TreeMap<String,Map<BigDecimal,List<SourceEvidence>>>();
        for(var d:docs) {
            var rows=new TreeMap<String,List<Fact>>();
            for(String type:List.of("itemName","quantity"))CaseFactPolicy.facts(d,type).forEach(f->rows.computeIfAbsent(f.row(),x->new ArrayList<>()).add(f));
            for(var row:rows.values()) {
                var items=row.stream().filter(f->f.type().equals("itemName")).map(Fact::value).distinct().toList();
                if(items.size()!=1)continue;
                for(var f:row)if(f.type().equals("quantity"))try {
                    var quantity=com.fuquan.audit.common.domain.ChineseNumber.parse(f.value().strip().replaceFirst("(?:台|套|项|个|份|件|批)$","")).orElseThrow();var ev=new ArrayList<SourceEvidence>();
                    for(var related:row)ev.addAll(CaseReviewContext.factEvidence(ctx,d,related));
                    if(!ev.isEmpty())quantities.computeIfAbsent(items.get(0),x->new TreeMap<>()).computeIfAbsent(quantity,x->new ArrayList<>()).addAll(ev);
                }catch(IllegalArgumentException | NoSuchElementException unknown){ /* Units or ambiguous text are left for cited semantic analysis. */ }
            }
        }
        for(var item:quantities.entrySet())checks.add(new ReviewCheck("QUANTITY_CONFLICT",item.getValue().size()>1?"SEMANTIC_REVIEW_REQUIRED":"CONSISTENT_IN_OBSERVED",
                "同名采购项 "+item.getKey()+" 数量："+item.getValue().keySet()+"；不同名称/配置不强行合并",item.getValue().values().stream().flatMap(List::stream).distinct().toList()));
        var quote=CaseFactPolicy.report(UUID.randomUUID(),docs);
        for(var offer:quote.offers()) {
            var d=docs.stream().filter(x->x.id().equals(offer.documentId())).findFirst().orElseThrow();var ev=new ArrayList<SourceEvidence>();
            ev.addAll(CaseMultiOffers.quoteEvidence(ctx,d,offer));
            if(quote.budget()!=null)ev.addAll(budgets.getOrDefault(quote.budget(),List.of()));
            checks.add(new ReviewCheck("QUOTE_BUDGET",quote.budget()==null?"UNKNOWN":offer.amount().compareTo(quote.budget())>0?"CONFLICT":"WITHIN_BUDGET",
                    offer.supplier()+" 报价 "+offer.amount()+"；预算上限 "+quote.budget(),ev.stream().distinct().toList()));
            if(quote.ceilingAmount()!=null) {
                var ceilingEvidence=new ArrayList<SourceEvidence>();
                ceilingEvidence.addAll(CaseMultiOffers.quoteEvidence(ctx,d,offer));
                for(var source:docs)for(var f:CaseFactPolicy.totals(source,"ceilingAmount"))
                    if(ProcurementMaterialSemantics.money(source,f).filter(v->v.compareTo(quote.ceilingAmount())==0).isPresent())
                        ceilingEvidence.addAll(CaseReviewContext.factEvidence(ctx,source,f));
                BigDecimal difference=offer.amount().subtract(quote.ceilingAmount());
                checks.add(new ReviewCheck("QUOTE_CEILING",difference.signum()>0?"CONFLICT":"WITHIN_CEILING",
                        offer.supplier()+" 报价 "+offer.amount()+" 元；最高限价 "+quote.ceilingAmount()+" 元；"
                                +(difference.signum()>0?"超出 "+difference.toPlainString()+" 元。":"未超过最高限价。"),ceilingEvidence.stream().distinct().toList()));
            }
            if(offer.lineValidation().equals("CONFLICT")) {
                var lineRefs=d.facts().stream().filter(f->Set.of("quantity","unitPrice","lineAmount","quoteAmount").contains(f.type()))
                        .flatMap(f->CaseReviewContext.factEvidence(ctx,d,f).stream()).distinct().toList();
                checks.add(new ReviewCheck("QUOTE_LINE_ARITHMETIC","CONFLICT",offer.supplier()+"的报价明细计算与所列合计或总价不一致，请核对数量、单价及金额。",lineRefs));
            }
            if(Set.of("SUM_VERIFIED","VERIFIED").contains(offer.lineValidation())) {
                var lineRefs=d.facts().stream().filter(f->Set.of("lineAmount","quoteAmount").contains(f.type()))
                        .flatMap(f->CaseReviewContext.factEvidence(ctx,d,f).stream()).distinct().toList();
                checks.add(new ReviewCheck("QUOTE_LINE_SUM","MATCHED",offer.supplier()+"的已提取明细金额合计与报价总额一致。"
                        +(offer.lineValidation().equals("SUM_VERIFIED")?"原表未列单价，未进行单价乘数量核算。":"已同时核对数量与单价乘积。"),lineRefs));
            }
        }
        if(quote.calculation()!=null&&quote.calculation().highlyConcentrated()) {
            var refs=docs.stream().flatMap(d->CaseFactPolicy.facts(d,"quoteAmount").stream().flatMap(f->CaseReviewContext.factEvidence(ctx,d,f).stream())).distinct().toList();
            checks.add(new ReviewCheck("QUOTE_CONCENTRATION","STATISTICAL_SIGNAL","本批至少三家供应商报价较集中（按既有5%离散程度参考线筛查）。可关注报价独立性及可比范围，不能据此认定围串标。",refs));
        }
        // Match both awarded supplier and price. Matching the lowest amount alone is insufficient.
        for(var award:docs)if(CaseReviewContext.role(award).equals("AWARD")) {
            var supplier=CaseFactPolicy.scalar(award,"supplierName");var amount=CaseFactPolicy.amount(award,"awardAmount");var ev=new ArrayList<SourceEvidence>();
            for(var f:award.facts())if(Set.of("supplierName","awardAmount").contains(f.type())&&f.verification().equals("SOURCE_VERIFIED"))ev.addAll(CaseReviewContext.factEvidence(ctx,award,f));
            var same=quote.offers().stream().filter(o->Objects.equals(o.supplier(),supplier)).toList();
            for(var offer:same)for(var d:docs)if(d.id().equals(offer.documentId()))for(var f:CaseFactPolicy.facts(d,"quoteAmount"))ev.addAll(CaseReviewContext.factEvidence(ctx,d,f));
            String status=supplier==null||amount==null||same.size()!=1?"UNKNOWN":same.get(0).amount().compareTo(amount)==0?"MATCHED":"CONFLICT";
            checks.add(new ReviewCheck("AWARD_QUOTE_MISMATCH",status,"中标供应商 "+supplier+"；中标金额 "+amount+"；仅核对同一供应商报价，不据最低价推断中标依据",ev.stream().distinct().toList()));
        }
        return List.copyOf(checks);
    }
}
