package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import org.springframework.stereotype.Component;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Ground only to frozen source ranges. Whitespace tolerance preserves exact original offsets. */
@Component
public class ContractAnalysisEvidence {
    public record Scope(Source source,String text,int start,int end) {
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
            if(!FACTS.contains(f.type())||f.value()==null||f.value().isBlank()||f.value().length()>400||evidence.isEmpty()
                    ||evidence.stream().noneMatch(e->compact(e.excerpt()).contains(compact(f.value())))
                    ||f.type().equals("CONTRACT_NO")&&evidence.stream().allMatch(e->parentNumber(e.excerpt(),f.value()))
                    ||f.type().equals("TOTAL_AMOUNT")&&evidence.stream().allMatch(e->e.excerpt().matches("(?s).*(预算|限价|中标|成交).*"))&&evidence.stream().noneMatch(e->e.excerpt().matches("(?s).*(合同(?:金额|总价)|总价款|价税合计).*"))
                    ||kind.equals("CROSS_FILE")||f.type().equals("LINE_TABLE_COMPLETE")&&!kind.equals("FILE")){rejected++;continue;}
            facts.add(new Fact(f.type(),f.value(),"AI_SOURCE_VERIFIED",evidence));
        }
        for(var clause:result.clauses()){
            var evidence=ground(clause.citations(),scopes);
            if(!TOPICS.contains(clause.topic())||clause.summary()==null||clause.summary().isBlank()||clause.summary().length()>600||evidence.isEmpty()
                ||!kind.equals("FILE")&&globalAbsence(clause.summary()) ||materialCoverageClaim(clause.summary())
                ||kind.equals("CROSS_FILE")&&evidence.stream().map(Evidence::fileId).distinct().count()<2){rejected++;continue;}
            clauses.add(new Clause(clause.topic(),clause.summary(),evidence));
        }
        for(var f:result.findings()){
            var evidence=ground(f.citations(),scopes);
            if(!CODES.contains(f.code())||!Set.of("INFO","WARNING").contains(f.level())||f.title()==null||f.title().isBlank()||f.title().length()>100
                ||f.explanation()==null||f.explanation().isBlank()||f.explanation().length()>800||f.suggestion()==null||f.suggestion().length()>400||evidence.isEmpty()
                ||!kind.equals("FILE")&&globalAbsence(f.explanation()) ||materialCoverageClaim(f.title()+f.explanation())
                ||(f.code().equals("CROSS_FILE_CONFLICT")||kind.equals("CROSS_FILE")&&f.level().equals("WARNING"))&&evidence.stream().map(Evidence::fileId).distinct().count()<2){rejected++;continue;}
            if(f.basis()!=null&&!Set.of("CLAUSE_ISSUE","MATERIAL_VERIFICATION","OPTIONAL_IMPROVEMENT").contains(f.basis())){rejected++;continue;}
            // File-level citations cannot establish the absence of another uploaded
            // document. The deterministic batch attachment check owns that conclusion.
            boolean followUp=f.basis()!=null&&!f.basis().equals("CLAUSE_ISSUE");
            String code=kind.equals("CROSS_FILE")&&!followUp&&f.level().equals("WARNING")?"CROSS_FILE_CONFLICT":f.code();
            findings.add(new Finding(code,followUp?"INFO":f.level(),f.title(),f.explanation(),f.suggestion(),evidence,"AI_REVIEW_SUGGESTION"));
        }
        return new Output(result.role(),result.profile(),result.complete()&&rejected==0,facts.stream().distinct().toList(),clauses.stream().distinct().toList(),List.of(),findings.stream().distinct().toList(),rejected,result.model(),result.version());
    }
    private List<Evidence> ground(List<ContractAnalysisAiPort.Citation> citations,List<Scope> scopes){
        if(citations==null||citations.isEmpty()||citations.size()>3)return List.of();var result=new ArrayList<Evidence>();
        for(var c:citations){if(c==null)return List.of();
            var matches=scopes.stream().filter(s->s.source().fileId().equals(c.document())).map(s->locate(s,c.text())).flatMap(Optional::stream).distinct().toList();
            if(matches.size()!=1)return List.of();result.add(matches.get(0));}
        return result.stream().distinct().toList();
    }
    static String compact(String text){return text.codePoints().filter(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();}
    private static boolean parentNumber(String excerpt,String value){
        var matcher=java.util.regex.Pattern.compile("(?:关联|原|主)合同(?:编号|号)\\h*[:：]\\h*([^\\s：:，,；;。]+)").matcher(excerpt);
        while(matcher.find())if(compact(matcher.group(1)).equals(compact(value)))return true;
        return false;
    }
    private static boolean space(char c){return Character.isWhitespace(c)||Character.isSpaceChar(c);}
    private static boolean globalAbsence(String value){return value.matches("(?s).*(整份合同|全部材料|本合同|该合同|本批材料).{0,10}(缺少|未提供|没有|未约定|不存在).*")||value.matches("(?s).*(未上传|没有上传).*");}
    private static boolean materialCoverageClaim(String value){return value.matches("(?s).*(当前文档|当前材料|本次材料|上传材料|提供的材料).{0,20}(未包含|未提供|未附|缺少|缺失).*")
        ||value.matches("(?s).*(附件|相关材料).{0,12}(未提供|未上传|未包含|缺失).*")
        ||value.matches("(?s).*(未提供|未上传|未附).{0,12}(附件|相关材料).*");}
    private static final Set<String> FACTS=Set.of("CONTRACT_NAME","CONTRACT_TYPE","PROJECT_NAME","CONTRACT_NO","PROJECT_NO","CONTRACT_REFERENCE","PARTY_A","PARTY_B","TOTAL_AMOUNT","AMOUNT_WORDS","AWARD_AMOUNT","AWARD_SUPPLIER","SIGN_DATE","TAX_BASIS","CURRENCY","ITEM_QUANTITY","ITEM_UNIT_PRICE","ITEM_AMOUNT","LINE_TABLE_COMPLETE");
    private static final Set<String> TOPICS=Set.of("PAYMENT","DELIVERY","ACCEPTANCE","BREACH","TERM","TERMINATION","DISPUTE","WARRANTY","CONFIDENTIALITY","SUBJECT","STRUCTURE","QUALIFICATION","ATTACHMENT","EFFECTIVENESS","OTHER");
    private static final Set<String> CODES=Set.of("PAYMENT_CONFLICT","PAYMENT_TRIGGER","ACCEPTANCE_AMBIGUITY","OBLIGATION_IMBALANCE","BREACH_AMBIGUITY","TERM_CONFLICT","TERMINATION_AMBIGUITY","TAX_AMBIGUITY","CROSS_FILE_CONFLICT","OTHER_CONCRETE_RISK","MATERIAL_SCOPE_NOTE","OPTIONAL_CLAUSE_ADVICE");
}
