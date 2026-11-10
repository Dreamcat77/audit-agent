package com.fuquan.audit.contract.application.analysis;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Source role and subject checks shared by recall, AI grounding and publication. */
final class ContractAnalysisSemantics {
    private ContractAnalysisSemantics() {}
    private static final Set<String> REFERENCE=Set.of("REFERENCE","QUALIFICATION");
    private static final Pattern HEADING=Pattern.compile("(?m)^\\h*([^\\r\\n]{2,100})\\h*$");
    private static final Pattern REFERENCE_HEADING=Pattern.compile(".*(?:类似项目业绩|同类项目业绩|历史业绩|历史合同|业绩证明|业绩合同|主要业绩|财务报表|财务审计报告|资产负债表|利润表|现金流量表|人员履历|个人简历|项目经验).*" );
    private static final Pattern CURRENT_HEADING=Pattern.compile("(?:第[一二三四五六七八九十百0-9]+[章部分节].*|(?:合同条款|投标函|开标一览表|报价一览表|分项报价表|中标通知书|采购需求))");
    static String key(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("[\\s\\p{Z}]+","");}
    static String title(String value){
        String v=value.strip().replaceAll("^[\\s：:，,。；;、·]+|[\\s：:，,。；;、·]+$","");
        if(v.startsWith("《")&&v.endsWith("》"))v=v.substring(1,v.length()-1).strip();
        return v;
    }
    static String role(Source source){
        if(com.fuquan.audit.contract.application.project.ProjectFileClassifier.isAuxiliary(source.name()))return "ANNEX";
        return switch(new com.fuquan.audit.contract.application.project.ProjectFileClassifier().classify(source.name())){
            case CONTRACT_BODY->"CONTRACT";case BID_RESPONSE->"BID";case TENDER_DOCUMENT->"TENDER";
            case AWARD_NOTICE->"AWARD";default->"OTHER";
        };
    }
    /** Only actual section headings change scope. A TOC entry is not a section boundary. */
    static boolean reference(String text,int at){
        boolean reference=false;var headings=HEADING.matcher(text.substring(0,Math.min(text.length(),Math.max(0,at))));
        while(headings.find()){
            String line=headings.group(1).strip();
            if(line.matches(".*(?:[.．…·]{2,}|\\s{2,})\\s*\\d{1,4}\\s*$"))continue;
            if(REFERENCE_HEADING.matcher(line).matches()&&!line.matches(".*[。；;：:].+"))reference=true;
            else if(CURRENT_HEADING.matcher(line).matches())reference=false;
        }
        return reference;
    }
    static String section(String text,int at){
        String result="";var headings=HEADING.matcher(text.substring(0,Math.min(text.length(),Math.max(0,at))));
        while(headings.find()){
            String line=headings.group(1).strip();
            if(!line.matches(".*[.．…·]{2,}.*")&&(REFERENCE_HEADING.matcher(line).matches()||CURRENT_HEADING.matcher(line).matches()))result=line;
        }
        return result;
    }
    static String currentNamingText(String text){
        boolean historical=false;var result=new StringBuilder();
        for(String raw:text.split("\\R",-1)){
            String line=raw.strip();
            if(!line.matches(".*(?:[.．…·]{2,}|\\s{2,})\\s*\\d{1,4}\\s*$")){
                if(REFERENCE_HEADING.matcher(line).matches()&&!line.matches(".*[。；;：:].+"))historical=true;
                else if(CURRENT_HEADING.matcher(line).matches())historical=false;
            }
            if(!historical)result.append(raw).append('\n');
        }
        return result.toString();
    }
    static boolean applicable(String scope,List<Evidence> evidence,String text){
        if(REFERENCE.contains(Objects.toString(scope,"")))return false;
        return evidence.stream().allMatch(e->!reference(text,e.start()));
    }
    private static String local(String excerpt,String value){
        String context=key(excerpt),needle=key(value);int at=context.indexOf(needle);
        if(at<0){needle=needle.replaceFirst("(?:万元|元|万)$","");at=context.indexOf(needle);}
        if(at<0)return context;
        int from=Math.max(0,at-64),to=Math.min(context.length(),at+needle.length()+24);
        String prefix=context.substring(from,at);
        var labels=Pattern.compile("(?:甲方|乙方|(?:中标|成交)(?:供应商(?:名称)?|单位|人|金额|价)|投标人(?:名称)?|采购人|供应商|采购代理机构|招标代理机构|代理机构|合同总价|合同金额|投标总价|投标报价|预算金额|最高限价|招标编号|统一社会信用代码)[（(盖章）)]*[：:]|(?:预算(?:金额|总额)?|中标(?:价|金额)|投标(?:总价|报价)|合同(?:总价|金额))(?:为|是|[：:])").matcher(prefix);
        int last=-1;while(labels.find())last=labels.start();if(last>=0)from+=last;
        String suffix=context.substring(at+needle.length(),to);
        var next=Pattern.compile("(?:甲方|乙方|投标人|采购人|预算|中标|投标|合同金额|最高限价|统一社会信用代码)").matcher(suffix);
        if(next.find())to=at+needle.length()+next.start();
        return context.substring(from,to);
    }
    static Optional<Fact> fact(Fact fact,Source source,String text){
        if(fact.evidence().isEmpty()||!applicable(null,fact.evidence(),text))return Optional.empty();
        String type=fact.type(),value=fact.value();
        if(value!=null&&fact.evidence().stream().anyMatch(e->{int at=e.excerpt().indexOf(fact.value());return at>=0?reference(text,e.start()+at):ContractAnalysisEvidence.numericValueOffsets(fact.type(),fact.value(),e.excerpt()).stream().anyMatch(n->reference(text,e.start()+n));}))return Optional.empty();
        if(value==null||!ContractAnalysisEvidence.usableFact(type,value))return Optional.empty();
        String context=fact.evidence().stream().map(e->local(e.excerpt(),fact.value())).collect(java.util.stream.Collectors.joining("\n"));
        String compact=key(context);String role=role(source);
        boolean contract=compact.matches("(?s).*(?:合同(?:总价|金额|价款)|总价款|价税合计|甲方[：:]|乙方[：:]).*");
        boolean bid=compact.matches("(?s).*(?:投标(?:总价|报价|价|人)|响应报价|报价总额).*" );
        boolean award=compact.matches("(?s).*(?:中标|成交)(?:金额|价|供应商|单位|人).*" );
        if(Set.of("TOTAL_AMOUNT","BID_AMOUNT","AWARD_AMOUNT","BUDGET_AMOUNT","CEILING_AMOUNT").contains(type)){
            if(compact.matches("(?s).*(?:预算|最高限价|控制价).*" )&&!contract)type=compact.contains("预算")?"BUDGET_AMOUNT":"CEILING_AMOUNT";
            else if(bid&&!contract)type="BID_AMOUNT";
            else if(award&&!contract)type="AWARD_AMOUNT";
            else if(contract)type="TOTAL_AMOUNT";
            else if(type.equals("TOTAL_AMOUNT")&&Set.of("BID","TENDER","AWARD").contains(role)&&!contract)return Optional.empty();
        }
        if(Set.of("PARTY_A","PARTY_B").contains(type)){
            if(compact.matches("(?s).*甲方[：:].*")&&!compact.matches("(?s).*乙方[：:].*"))type="PARTY_A";
            else if(compact.matches("(?s).*乙方[：:].*")&&!compact.matches("(?s).*甲方[：:].*"))type="PARTY_B";
        }
        if(type.equals("SIGN_DATE")&&Set.of("BID","TENDER","AWARD").contains(role)
                &&!compact.matches("(?s).*合同.{0,12}(?:签订|签约|签署|订立).*"))return Optional.empty();
        if(type.equals("AWARD_SUPPLIER")&&!award){
            if(bid)type="BIDDER";else return Optional.empty();
        }
        if(Set.of("PARTY_A","PARTY_B").contains(type)&&!compact.matches("(?s).*(?:甲方|乙方|买方|卖方|委托方|受托方|承揽方|定作方|出租方|承租方|发包人|承包人|采购人|供应商)[：:].*")){
            if(compact.matches("(?s).*投标人(?:名称)?[（(盖章）)]*[：:].*"))type="BIDDER";
            else if(compact.matches("(?s).*(?:采购代理|招标代理|代理机构).*"))type="AGENCY";
            else if(Set.of("BID","TENDER").contains(role))return Optional.empty();
        }
        if(Set.of("PARTY_A","PARTY_B").contains(type)&&Set.of("BID","TENDER").contains(role)){
            if(compact.matches("(?s).*(?:采购代理|招标代理|代理机构).*"))type="AGENCY";
            else if(compact.matches("(?s).*采购人[：:].*" )&&!compact.matches("(?s).*(?:甲方|乙方)[：:].*"))type="PURCHASER";
            else if(bid&&!compact.matches("(?s).*(?:甲方|乙方)[：:].*"))type="BIDDER";
        }
        if(type.equals("CONTRACT_NO")&&compact.matches("(?s).*(?:招标编号|采购编号|项目编号|统一社会信用代码|营业执照编号).*" )
                &&!compact.matches("(?s).*合同(?:编号|号)[：:].*"))return Optional.empty();
        if(type.equals("AMOUNT_WORDS")&&Set.of("BID","TENDER","AWARD").contains(role)&&!contract)return Optional.empty();
        if(type.equals("AMOUNT_WORDS")&&fact.evidence().stream().allMatch(e->{
            int at=e.excerpt().indexOf(fact.value());
            if(at<0)return false;
            while(at>=0){if(ContractAmountPairs.contractRole(text,e.start()+at))return false;at=e.excerpt().indexOf(fact.value(),at+1);}
            return true;
        }))return Optional.empty();
        if(type.startsWith("ITEM_")&&ContractAnalysisRules.money(value).isEmpty())return Optional.empty();
        if(type.equals("CONTRACT_NAME")){
            value=title(value);
            if(value.isBlank()||value.matches(".*(?:利润表|现金流量表|资产负债表|个人简历|验收单).*"))return Optional.empty();
        }
        if(type.equals("PROJECT_NAME")&&!com.fuquan.audit.common.material.ProjectNameResolver.isProjectName(value)){
            org.slf4j.LoggerFactory.getLogger(ContractAnalysisSemantics.class).info("CONTRACT_FACT_SELECTION type=PROJECT_NAME outcome=REJECTED reasonCode=DOCUMENT_TITLE_OR_PLACEHOLDER");
            return Optional.empty();
        }
        if(Set.of("PARTY_A","PARTY_B").contains(type))value=com.fuquan.audit.contract.domain.ContractPartyName.canonical(value);
        return Optional.of(new Fact(type,value,fact.method(),fact.evidence()));
    }
    static Output project(Output out,Source source,String text,boolean ocr){
        out=ContractFindingSupport.documentProjection(out,source,text);
        var scope=new ContractAnalysisEvidence.Scope(source,text,0,text.length(),ocr);
        var clauses=out.clauses().stream().filter(cl->applicable(null,cl.evidence(),text))
                .filter(cl->!ContractAnalysisEvidence.materialCoverageClaim(cl.summary())).toList();
        var checks=out.checks().stream().filter(ch->ch.evidence().isEmpty()||applicable(null,ch.evidence(),text))
                .filter(ch->!Set.of("TERMINATION_COMPLETENESS","SIGN_DATE_COMPLETENESS").contains(ch.code())
                    ||ContractFindingSupport.supports(new Finding(ch.code(),"WARNING","",ch.explanation(),"",ch.evidence(),"DETERMINISTIC_DOCUMENT"),source,text))
                .filter(ch->!Set.of("BID","TENDER","AWARD").contains(role(source))||!ch.code().startsWith("F02_")).toList();
        var findings=new ArrayList<Finding>();
        for(var f:out.findings()){
            // Batch attachment coverage belongs to the frozen-material comparison,
            // including when reopening earlier model outputs through this projection.
            if(f.origin().equals("AI_REVIEW_SUGGESTION")&&ContractAnalysisEvidence.materialCoverageClaim(f.title()+f.explanation()))continue;
            if(!applicable(null,f.evidence(),text))continue;
            if(!ContractFindingSupport.supports(f,source,text))continue;
            if(Set.of("BID","TENDER","AWARD").contains(role(source))&&f.origin().startsWith("DETERMINISTIC"))continue;
            if(ContractAnalysisEvidence.ocrTranscriptionConcern(f.title()+f.explanation(),f.evidence(),List.of(scope)))
                findings.add(ContractAnalysisEvidence.transcriptionNote(f));
            else findings.add(ContractFindingSupport.narrow(f,source,text));
        }
        return new Output(out.role(),out.profile(),out.complete(),facts(out.facts(),source,text),clauses,checks,findings,out.rejectedCitations(),out.model(),out.version());
    }
    static List<Fact> facts(List<Fact> facts,Source source,String text){return ContractFactConsolidation.merge(facts.stream().map(f->fact(f,source,text)).flatMap(Optional::stream).toList(),text);}
}
