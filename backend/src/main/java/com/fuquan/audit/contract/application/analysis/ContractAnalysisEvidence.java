package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import org.springframework.stereotype.Component;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Ground only to frozen source ranges. Whitespace tolerance preserves exact original offsets. */
@Component
public class ContractAnalysisEvidence {
    public record Scope(Source source,String text,int start,int end,boolean ocr) {
        public Scope(Source source,String text,int start,int end){this(source,text,start,end,false);}
        public Scope {if(start<0||end>text.length()||end<=start)throw new IllegalArgumentException("invalid source scope");}
        public String supplied(){return text.substring(start,end);}
    }
    public Optional<Evidence> locate(Scope scope,String quote){
        if(quote==null||quote.isBlank()||quote.length()>4000)return Optional.empty();
        String supplied=scope.supplied();int index=supplied.indexOf(quote),end;
        if(index>=0){if(supplied.indexOf(quote,index+1)>=0)return Optional.empty();end=index+quote.length();}
        else {
            StringBuilder compact=new StringBuilder();List<Integer> offsets=new ArrayList<>();
            for(int i=0;i<supplied.length();i++)if(!space(supplied.charAt(i))){compact.append(supplied.charAt(i));offsets.add(i);}
            String q=compact(quote);if(q.length()<2)return Optional.empty();
            index=compact.indexOf(q);if(index<0||compact.indexOf(q,index+1)>=0)return Optional.empty();
            end=offsets.get(index+q.length()-1)+1;index=offsets.get(index);
        }
        if(end-index>4000)return Optional.empty();
        var s=scope.source();return Optional.of(new Evidence(s.fileId(),s.sha256(),s.parseResultId(),s.parseVersion(),s.textSha256(),
            scope.start()+index,scope.start()+end,supplied.substring(index,end)));
    }
    public Output validate(ContractAnalysisAiPort.Result result,List<Scope> scopes,String kind){
        if(result==null||!"contract-case-v1".equals(result.version())||result.model()==null||result.model().isBlank()
                ||!Set.of("CONTRACT","SUPPLEMENT","ANNEX","TENDER","BID","AWARD","ACCEPTANCE","PAYMENT","OTHER").contains(result.role())
                ||!Set.of("PROCUREMENT","SERVICE","LEASE","GENERAL").contains(result.profile())||result.facts()==null||result.clauses()==null||result.findings()==null
                ||result.facts().size()>80||result.clauses().size()>24||result.findings().size()>12||result.rejectedItems()<0||result.rejectedItems()>116)throw new IllegalArgumentException("MODEL_SCHEMA_INVALID");
        int rejected=result.rejectedItems();List<Fact> facts=new ArrayList<>();List<Clause> clauses=new ArrayList<>();List<Finding> findings=new ArrayList<>();
        for(var f:result.facts()){
            var evidence=ground(f.citations(),scopes);
            if(!FACTS.contains(f.type())||!usableFact(f.type(),f.value())||evidence.isEmpty()
                    ||evidence.stream().noneMatch(e->containsValue(f.type(),f.value(),e.excerpt()))
                    ||f.type().equals("CONTRACT_NO")&&evidence.stream().allMatch(e->parentNumber(e.excerpt(),f.value()))
                    ||f.type().equals("CONTRACT_REFERENCE")&&evidence.stream().noneMatch(e->contractReference(e.excerpt(),f.value()))

                    ||kind.equals("CROSS_FILE")||f.type().equals("LINE_TABLE_COMPLETE")&&!kind.equals("FILE")){rejected++;continue;}
            if(!validScope(f.subjectScope())){rejected++;continue;}
            var sourceScope=scopes.stream().filter(s->s.source().fileId().equals(evidence.get(0).fileId())).findFirst().orElseThrow();
            if(!ContractAnalysisSemantics.applicable(f.subjectScope(),evidence,sourceScope.text()))continue;
            var semantic=ContractAnalysisSemantics.fact(new Fact(f.type(),f.value(),"AI_SOURCE_VERIFIED",evidence),sourceScope.source(),sourceScope.text());
            if(semantic.isPresent())facts.add(semantic.get());else rejected++;
        }
        for(var clause:result.clauses()){
            var evidence=ground(clause.citations(),scopes);
            if(!TOPICS.contains(clause.topic())||clause.summary()==null||clause.summary().isBlank()||clause.summary().length()>600||evidence.isEmpty()
                ||!kind.equals("FILE")&&globalAbsence(clause.summary()) ||materialCoverageClaim(clause.summary())
                ||kind.equals("CROSS_FILE")&&evidence.stream().map(Evidence::fileId).distinct().count()<2){rejected++;continue;}
            if(!validScope(clause.subjectScope())){rejected++;continue;}
            if(!current(clause.subjectScope(),evidence,scopes))continue;
            clauses.add(new Clause(clause.topic(),clause.summary(),evidence));
        }
        for(var f:result.findings()){
            var evidence=ground(f.citations(),scopes);
            if(!CODES.contains(f.code())||!Set.of("INFO","WARNING").contains(f.level())||f.title()==null||f.title().isBlank()||f.title().length()>100
                ||f.explanation()==null||f.explanation().isBlank()||f.explanation().length()>800||f.suggestion()==null||f.suggestion().length()>400||evidence.isEmpty()
                ||!kind.equals("FILE")&&globalAbsence(f.explanation()) ||materialCoverageClaim(f.title()+f.explanation())
                ||!kind.equals("FILE")&&fragmentAbsence(f.title()+f.explanation()+f.suggestion())
                ||(f.code().equals("CROSS_FILE_CONFLICT")||kind.equals("CROSS_FILE")&&f.level().equals("WARNING"))&&evidence.stream().map(Evidence::fileId).distinct().count()<2){rejected++;continue;}
            if(f.basis()!=null&&!Set.of("CLAUSE_ISSUE","MATERIAL_VERIFICATION","OPTIONAL_IMPROVEMENT").contains(f.basis())){rejected++;continue;}
            // File-level citations cannot establish the absence of another uploaded
            // document. The deterministic batch attachment check owns that conclusion.
            if(!validScope(f.subjectScope())){rejected++;continue;}
            if(!current(f.subjectScope(),evidence,scopes))continue;
            var candidate=new Finding(f.code(),f.level(),f.title(),f.explanation(),f.suggestion(),evidence,"AI_REVIEW_SUGGESTION");
            if(scopes.stream().filter(s->evidence.stream().anyMatch(e->e.fileId().equals(s.source().fileId())))
                    .anyMatch(s->!ContractFindingSupport.supports(candidate,s.source(),s.text()))){rejected++;continue;}
            if(ocrTranscriptionConcern(f.title()+f.explanation(),evidence,scopes)){
                findings.add(transcriptionNote(candidate));continue;
            }
            boolean followUp=f.basis()!=null&&!f.basis().equals("CLAUSE_ISSUE");
            String code=kind.equals("CROSS_FILE")&&!followUp&&f.level().equals("WARNING")?"CROSS_FILE_CONFLICT":f.code();
            var accepted=new Finding(code,followUp?"INFO":f.level(),f.title(),f.explanation(),f.suggestion(),evidence,"AI_REVIEW_SUGGESTION");
            if(kind.equals("CROSS_FILE"))accepted=ancillaryPartyDifference(accepted,scopes.stream().map(Scope::source).toList());
            for(var scope:scopes)accepted=ContractFindingSupport.narrow(accepted,scope.source(),scope.text());
            findings.add(accepted);
        }
        org.slf4j.LoggerFactory.getLogger(ContractAnalysisEvidence.class).info(
                "CONTRACT_MODEL_GROUNDING scope={} candidateCount={} acceptedCount={} evidenceRejectedCount={} clauseCount={} findingCount={}",
                Set.of("FILE","FRAGMENT","FOCUSED","CROSS_FILE").contains(kind)?kind:"OTHER",result.facts().size(),facts.size(),rejected,clauses.size(),findings.size());
        return new Output(result.role(),result.profile(),result.complete()&&rejected==0,facts.stream().distinct().toList(),clauses.stream().distinct().toList(),List.of(),findings.stream().distinct().toList(),rejected,result.model(),result.version());
    }
    private static boolean validScope(String scope){return scope==null||Set.of("CURRENT_PROJECT","REFERENCE","QUALIFICATION","GENERAL").contains(scope);}
    static Finding ancillaryPartyDifference(Finding finding,List<Source> sources){
        if(!finding.origin().equals("AI_REVIEW_SUGGESTION")||!finding.code().equals("CROSS_FILE_CONFLICT")
                ||!finding.level().equals("WARNING")||finding.evidence().stream().map(Evidence::fileId).distinct().count()!=2
                ||!(finding.title()+finding.explanation()).matches("(?s).*(?:主体(?:资格|名称)?|签约主体|(?:甲方|乙方)(?:主体)?名称).*(?:不同|不一致|不适格|资格).*"))return finding;
        var ids=finding.evidence().stream().map(Evidence::fileId).collect(java.util.stream.Collectors.toSet());
        var roles=sources.stream().filter(s->ids.contains(s.fileId())).map(ContractAnalysisSemantics::role).collect(java.util.stream.Collectors.toSet());
        if(!roles.containsAll(Set.of("CONTRACT","ANNEX"))||finding.evidence().stream().anyMatch(e->
                !e.excerpt().strip().matches("(?:甲方|乙方|委托方|受托方|采购人|供应商)(?:[（(][^）)\\r\\n]{0,24}[）)])?\\h*[:：]\\h*[^\\r\\n]{2,100}")))return finding;
        // Two name captions prove different names, not lack of legal personality or authorization.
        return new Finding("MATERIAL_SCOPE_NOTE","INFO","配套材料签约主体不同",
                "主合同与配套材料列示的签约主体名称不同，具体名称见所引原文。","",finding.evidence(),finding.origin());
    }
    private static boolean current(String scope,List<Evidence> evidence,List<Scope> scopes){
        if(Set.of("REFERENCE","QUALIFICATION").contains(Objects.toString(scope,"")))return false;
        return evidence.stream().allMatch(e->scopes.stream().anyMatch(s->s.source().fileId().equals(e.fileId())&&!ContractAnalysisSemantics.reference(s.text(),e.start())));
    }
    static boolean ocrTranscriptionConcern(String description,List<Evidence> evidence,List<Scope> scopes){
        description=description.replaceAll("(?:没有|不存在|并无|无|未见)(?:乱码|识别错误)|(?:并非|不是)(?:识别错误|OCR异常)", "");
        if(description.matches("(?s).*(?:乱码|无法辨认|不可辨认|识别错误|OCR.{0,8}(?:错误|异常)|扫描.{0,8}(?:模糊|不清)).*"))return true;
        if(!description.matches("(?s).*(?:错字|错别字|误植|文字错误|大写.{0,12}(?:错误|有误|不规范)|(?:错误|有误).{0,12}大写).*"))return false;
        return evidence.stream().anyMatch(e->scopes.stream().anyMatch(s->s.ocr()&&s.source().fileId().equals(e.fileId()))
                &&e.excerpt().matches("(?s).*(?:大写|壹|贰|叁|肆|伍|陆|柒|捌|玖).*"));
    }
    static Finding transcriptionNote(Finding finding){
        boolean money=(finding.title()+finding.explanation()).contains("大写");
        return new Finding("MATERIAL_SCOPE_NOTE","INFO",money?"金额原文需核对":"相关原文读取不清",
                money?"扫描文字中的金额大写存在识别疑点，暂不据此认定合同金额错误。":"部分文字读取不清，相关内容未作确定判断。",
                "",finding.evidence(),"OCR_TRANSCRIPTION_UNCERTAIN");
    }
    private List<Evidence> ground(List<ContractAnalysisAiPort.Citation> citations,List<Scope> scopes){
        if(citations==null||citations.isEmpty()||citations.size()>3)return List.of();var result=new ArrayList<Evidence>();
        for(var c:citations){if(c==null)return List.of();
            var matches=scopes.stream().filter(s->s.source().fileId().equals(c.document())).map(s->locate(s,c.text())).flatMap(Optional::stream).distinct().toList();
            if(matches.size()!=1)return List.of();result.add(matches.get(0));}
        return result.stream().distinct().toList();
    }
    static boolean containsValue(String type,String value,String excerpt){
        boolean monetary=Set.of("TOTAL_AMOUNT","BID_AMOUNT","AWARD_AMOUNT","BUDGET_AMOUNT","CEILING_AMOUNT").contains(type);
        String literal=compact(value);
        if(compact(excerpt).contains(literal)&&(!monetary||java.util.regex.Pattern.compile("(?<![0-9.,，零壹贰叁肆伍陆柒捌玖拾佰仟万亿\\-−])"+java.util.regex.Pattern.quote(literal)+"(?![0-9.])").matcher(compact(excerpt)).find()))return true;
        return !numericValueOffsets(type,value,excerpt).isEmpty();
    }
    static List<Integer> numericValueOffsets(String type,String value,String excerpt){
        if(!Set.of("TOTAL_AMOUNT","BID_AMOUNT","AWARD_AMOUNT","BUDGET_AMOUNT","CEILING_AMOUNT").contains(type))return List.of();
        var expected=ContractAnalysisRules.money(value);if(expected.isEmpty())return List.of();
        var offsets=new ArrayList<Integer>();
        var headers=java.util.regex.Pattern.compile("(?:报价|金额|价款|预算|总价|限价|合计)[（(](万元|元)[）)]|单位[：:]?(万元|元)").matcher(compact(excerpt));
        var units=new HashSet<String>();while(headers.find())units.add(Objects.toString(headers.group(1),headers.group(2)));
        var numbers=java.util.regex.Pattern.compile("(?<![0-9.\\-−])([0-9]+(?:[,，][0-9]{3})*(?:\\.[0-9]{1,4})?)(?![0-9.])\\h*(万元|元|万)?").matcher(excerpt);
        while(numbers.find()){
            String unit=numbers.group(2);
            if(unit==null){if(units.size()!=1)continue;unit=units.iterator().next();}
            var actual=ContractAnalysisRules.money(numbers.group(1)+unit);
            if(actual.isPresent()&&actual.get().compareTo(expected.get())==0)offsets.add(numbers.start());
        }
        return List.copyOf(offsets);
    }
    static String compact(String text){return text.codePoints().filter(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();}
    static boolean usableFact(String type,String value){
        if(value==null||value.isBlank()||value.length()>400||placeholder(value))return false;
        if(Set.of("PARTY_A","PARTY_B","AWARD_SUPPLIER").contains(type))return com.fuquan.audit.contract.domain.ContractPartyName.isPresent(value);
        // Unit names printed on an empty uppercase-amount form are not a zero amount.
        if(!"AMOUNT_WORDS".equals(type))return true;
        if(new com.fuquan.audit.contract.domain.ChineseRmbUppercaseParser().parse(compact(value)).isPresent())return true;
        String amount=compact(value).replaceAll("人民币|金额|大写|小写|包含税费|含税", "");
        return !amount.matches("[佰拾万仟亿元圆角分整正：:()（）_＿—¥￥]+");
    }
    private static boolean placeholder(String value){return compact(value).matches("[_＿—–\\-…□○〇年月日/.:：()（）]+|待填写?|待定|未填写?|未知|不详|暂无|(?i:null|unknown|n/a)");}
    private static boolean parentNumber(String excerpt,String value){
        var matcher=java.util.regex.Pattern.compile("(?:关联|原|主)合同(?:编号|号)\\h*[:：]\\h*([^\\s：:，,；;。]+)").matcher(excerpt);
        while(matcher.find())if(compact(matcher.group(1)).equals(compact(value)))return true;
        return false;
    }
    private static boolean contractReference(String excerpt,String value){
        return java.util.regex.Pattern.compile("(?:关联|原|主)合同(?:编号|号|名称)?\\h*(?:[:：]|为|是)?\\h*[《【]?\\h*"
                +java.util.regex.Pattern.quote(compact(value))).matcher(compact(excerpt)).find();
    }
    private static boolean space(char c){return Character.isWhitespace(c)||Character.isSpaceChar(c);}
    private static boolean globalAbsence(String value){return value.matches("(?s).*(整份合同|全部材料|本合同|该合同|本批材料).{0,10}(缺少|未提供|没有|未约定|不存在).*")||value.matches("(?s).*(未上传|没有上传).*");}
    static boolean materialCoverageClaim(String value){return value.matches("(?s).*(当前文档|当前材料|本次材料|上传材料|提供的材料|本次上传(?:的)?(?:文件|文档|材料)|当前上传(?:的)?(?:文件|文档|材料)).{0,20}(未包含|未提供|未附|缺少|缺失).*")
        ||value.matches("(?s).*(附件|相关材料).{0,12}(未提供|未上传|未包含|缺失).*")
        ||value.matches("(?s).*(未提供|未上传|未附|未包含).{0,12}(附件|相关材料).*");}
    private static boolean fragmentAbsence(String value){return value.matches("(?s).*(?:当前|本次|该|此)?(?:片段|分段|局部).{0,24}(?:未包含|未体现|未见|缺少|没有|未涉及).*")
        ||value.matches("(?s).*(?:请|需|应)提供完整合同(?:文本)?.*");}
    private static final Set<String> FACTS=Set.of("CONTRACT_NAME","CONTRACT_TYPE","PROJECT_NAME","CONTRACT_NO","PROJECT_NO","CONTRACT_REFERENCE","PARTY_A","PARTY_B","TOTAL_AMOUNT","AMOUNT_WORDS","AWARD_AMOUNT","AWARD_SUPPLIER","BID_AMOUNT","BIDDER","PURCHASER","AGENCY","BUDGET_AMOUNT","CEILING_AMOUNT","SIGN_DATE","TAX_BASIS","CURRENCY","ITEM_QUANTITY","ITEM_UNIT_PRICE","ITEM_AMOUNT","LINE_TABLE_COMPLETE");
    private static final Set<String> TOPICS=Set.of("PAYMENT","DELIVERY","ACCEPTANCE","BREACH","TERM","TERMINATION","DISPUTE","WARRANTY","CONFIDENTIALITY","SUBJECT","STRUCTURE","QUALIFICATION","ATTACHMENT","EFFECTIVENESS","OTHER");
    private static final Set<String> CODES=Set.of("PAYMENT_CONFLICT","PAYMENT_TRIGGER","ACCEPTANCE_AMBIGUITY","OBLIGATION_IMBALANCE","BREACH_AMBIGUITY","TERM_CONFLICT","TERMINATION_AMBIGUITY","TAX_AMBIGUITY","CROSS_FILE_CONFLICT","OTHER_CONCRETE_RISK","MATERIAL_SCOPE_NOTE","OPTIONAL_CLAUSE_ADVICE");
}
