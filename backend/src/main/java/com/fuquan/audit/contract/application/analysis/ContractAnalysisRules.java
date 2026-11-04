package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.domain.*;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Existing extractor is recall assistance; exact rules never fabricate missing formal fields. */
@Component
public class ContractAnalysisRules {
    private final ProjectFactExtractor extractor;private final ContractAnalysisEvidence ground;
    public ContractAnalysisRules(ProjectFactExtractor extractor,ContractAnalysisEvidence ground){this.extractor=extractor;this.ground=ground;}
    public Output recall(ProjectFile file,Source source,String text){
        var scope=new ContractAnalysisEvidence.Scope(source,text,0,text.length());
        var segments=new ArrayList<ParsedProjectDocument.Segment>();int offset=0;
        for(String line:text.split("\n",-1)){segments.add(new ParsedProjectDocument.Segment("analysis/offset:"+offset,line));offset+=line.length()+1;}
        List<Fact> facts=new ArrayList<>();
        var references=new ArrayList<Evidence>();
        var reference=Pattern.compile("(?im)^\\h*(?:关联合同(?:编号|号)|原合同(?:编号|号)|主合同(?:编号|号))\\h*[:：]\\h*([^\\s：:，,；;。]{1,120})").matcher(text);
        while(reference.find()){
            var located=ground.locate(scope,reference.group().strip());
            if(located.isPresent()){references.add(located.get());facts.add(new Fact("CONTRACT_REFERENCE",reference.group(1),"RULE_RECALL_SOURCE_VERIFIED",List.of(located.get())));}
        }
        var named=Pattern.compile("(?im)^\\s*(?:合同名称|Contract\\s*Name)\\s*[:：]\\s*([^\\r\\n]+)").matcher(text);
        var explicitNames=new HashSet<String>();while(named.find())explicitNames.add(ContractAnalysisEvidence.compact(named.group(1)));
        for(var f:extractor.extract(file,new ParsedProjectDocument(segments))){
            String type=switch(f.factType()){case "AMOUNT_NUMERIC"->"TOTAL_AMOUNT";case "AMOUNT_UPPERCASE"->"AMOUNT_WORDS";case "AWARD_SUPPLIER"->"AWARD_SUPPLIER";default->f.factType();};
            if(!Set.of("CONTRACT_NO","CONTRACT_NAME","PROJECT_NO","PARTY_A","PARTY_B","TOTAL_AMOUNT","AMOUNT_WORDS","AWARD_AMOUNT","AWARD_SUPPLIER").contains(type))continue;
            String value=f.valueText();var evidence=ground.locate(scope,f.sourceText());
            if(type.equals("CONTRACT_NO")&&evidence.isPresent()&&references.stream().anyMatch(r->evidence.get().start()>=r.start()&&evidence.get().end()<=r.end()))continue;
            // Explicitly labelled names take precedence over cover headings or prose ending in 'contract'.
            if(type.equals("CONTRACT_NAME")&&!explicitNames.isEmpty()&&!explicitNames.contains(ContractAnalysisEvidence.compact(value)))continue;
            if(value!=null&&evidence.isPresent()&&ContractAnalysisEvidence.compact(evidence.get().excerpt()).contains(ContractAnalysisEvidence.compact(value)))
                facts.add(new Fact(type,value,"RULE_RECALL_SOURCE_VERIFIED",List.of(evidence.get())));
        }
        // A constrained recall supplement for common Chinese financial expressions; AI remains primary for nonstandard prose.
        var words=Pattern.compile("(?:人民币[（(]?大写[）)]?|金额[（(]?大写[）)]?|大写金额|大写)\\s*[:：]?\\s*([零壹贰叁肆伍陆柒捌玖拾佰仟万亿元圆角分整正]+)").matcher(text);
        while(words.find()){var e=ground.locate(scope,words.group());if(e.isPresent()&&new ChineseRmbUppercaseParser().parse(words.group(1)).isPresent())facts.add(new Fact("AMOUNT_WORDS",words.group(1),"RULE_RECALL_SOURCE_VERIFIED",List.of(e.get())));}
        String role=text.matches("(?s).*(合同编号|合同名称|甲方[：:]|乙方[：:]).*")||java.util.regex.Pattern.compile("(?m)^\\h*.{0,60}(?:合同书|采购合同|服务合同|租赁合同|购销合同)\\h*$").matcher(text.substring(0,Math.min(1200,text.length()))).find()?"CONTRACT":"OTHER";
        String profile=text.contains("租赁合同")?"LEASE":text.contains("服务合同")?"SERVICE":text.contains("采购合同")||text.contains("购销合同")?"PROCUREMENT":"GENERAL";
        return new Output(role,profile,true,facts.stream().distinct().toList(),List.of(),List.of(),List.of(),0,null,"contract-rule-recall-v1");
    }
    public Output evaluate(Source source,List<Output> results,String text){
        var facts=results.stream().flatMap(r->r.facts().stream()).filter(f->f.evidence().stream().allMatch(e->e.fileId().equals(source.fileId()))).distinct().toList();
        var checks=new ArrayList<Check>();var findings=new ArrayList<Finding>();
        var amount=unique(facts,"TOTAL_AMOUNT");var words=unique(facts,"AMOUNT_WORDS");
        if(amount.isPresent()&&words.isPresent()&&money(amount.get().value()).isPresent()){
            var parsed=new ChineseRmbUppercaseParser().parse(words.get().value());
            if(parsed.isPresent())compare(checks,findings,F02RuleCodes.AMOUNT_WORDS_MATCH,"合同金额大小写核对",money(amount.get().value()).get(),parsed.get().amount(),combine(amount.get(),words.get()));
            else checks.add(missing(F02RuleCodes.AMOUNT_WORDS_MATCH,"金额大写的写法暂不能可靠换算，其他条款继续审查"));
        }else checks.add(missing(F02RuleCodes.AMOUNT_WORDS_MATCH,"当前材料的合同总价或金额大写依据不足，未作金额一致结论"));
        // Physical row identity is based on source ranges, never name or model row numbers.
        var table=ContractAnalysisTable.parse(source,text);int rows=0;
        for(var row:table.rows()){rows++;compare(checks,findings,F02RuleCodes.LINE_ITEM_ARITHMETIC,"第"+rows+"项数量与单价核对",row.amount(),ContractArithmetic.lineAmount(row.quantity(),row.price()),List.of(row.evidence()));}
        var quantities=facts.stream().filter(f->f.type().equals("ITEM_QUANTITY")).toList();
        if(table.rows().isEmpty())for(var quantity:quantities){
            var price=sameRow(facts,quantity,"ITEM_UNIT_PRICE");var declared=sameRow(facts,quantity,"ITEM_AMOUNT");
            if(price.isEmpty()||declared.isEmpty())continue;
            var q=number(quantity.value());var p=money(price.get().value());var d=money(declared.get().value());
            if(q.isPresent()&&p.isPresent()&&d.isPresent()){rows++;compare(checks,findings,F02RuleCodes.LINE_ITEM_ARITHMETIC,"第"+rows+"项数量与单价核对",d.get(),ContractArithmetic.lineAmount(q.get(),p.get()),combine(quantity,price.get(),declared.get()));}
        }
        if(rows==0)checks.add(missing(F02RuleCodes.LINE_ITEM_ARITHMETIC,"没有同时具备数量、单价及行金额的可靠同一行明细，未执行乘法核对"));
        if(table.complete()&&amount.isPresent()&&money(amount.get().value()).isPresent()){
            var evidence=new ArrayList<Evidence>(amount.get().evidence());table.rows().forEach(r->evidence.add(r.evidence()));
            compare(checks,findings,F02RuleCodes.LINE_SUM_MATCH,"合同明细合计与总价核对",ContractArithmetic.sum(table.rows().stream().map(ContractAnalysisTable.Row::amount).toList()),money(amount.get().value()).get(),evidence);
        }else checks.add(missing(F02RuleCodes.LINE_SUM_MATCH,"尚不能证明全部合同价款明细均已提取，未将局部明细冒充完整合计"));
        return new Output("OTHER","GENERAL",true,List.of(),List.of(),checks,findings,0,null,"f02-independent-v1");
    }
    public static Optional<Fact> unique(List<Fact> facts,String type){
        var candidates=facts.stream().filter(f->f.type().equals(type)).toList();
        if(candidates.isEmpty())return Optional.empty();
        var values=candidates.stream().map(f->ContractAnalysisEvidence.compact(f.value())).distinct().toList();
        if(values.size()==1)return Optional.of(candidates.get(0));
        if((type.endsWith("AMOUNT")||type.equals("AMOUNT_WORDS"))&&candidates.stream().allMatch(f->money(f.value()).isPresent())&&candidates.stream().map(f->money(f.value()).get().stripTrailingZeros()).distinct().count()==1)return Optional.of(candidates.get(0));
        return Optional.empty();
    }
    static Optional<Fact> sameRow(List<Fact> all,Fact q,String type){
        var e=q.evidence().get(0);var values=all.stream().filter(f->f.type().equals(type)&&f.evidence().size()==1&&f.evidence().get(0).equals(e)).toList();
        return values.size()==1?Optional.of(values.get(0)):Optional.empty();
    }
    public static Optional<BigDecimal> money(String value){
        if(value==null)return Optional.empty();var upper=new ChineseRmbUppercaseParser().parse(value);if(upper.isPresent())return Optional.of(upper.get().amount());
        String v=Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("[\\s,，]","").replaceFirst("^(人民币|CNY|RMB|[¥￥])","");
        var m=Pattern.compile("([0-9]+(?:\\.[0-9]{1,4})?)(万元|万|元)?").matcher(v);if(!m.matches())return Optional.empty();
        try{BigDecimal n=new BigDecimal(m.group(1));if(Set.of("万元","万").contains(Objects.toString(m.group(2),"")))n=n.movePointRight(4);
            return n.precision()>18||n.scale()>4?Optional.empty():Optional.of(n);}catch(NumberFormatException e){return Optional.empty();}
    }
    static Optional<BigDecimal> number(String value){return money(value);}
    public static Check missing(String code,String explanation){return new Check(code,"NOT_EVALUATED",explanation,List.of());}
    public static List<Evidence> combine(Fact... facts){return Arrays.stream(facts).flatMap(f->f.evidence().stream()).distinct().toList();}
    public static void compare(List<Check> checks,List<Finding> findings,String code,String name,BigDecimal actual,BigDecimal expected,List<Evidence> evidence){
        boolean matches=ContractArithmetic.sameAmount(actual,expected);String message=name+"："+actual.toPlainString()+"元，与依据金额"+expected.toPlainString()+"元"+(matches?"一致":"不一致，差额"+actual.subtract(expected).abs().toPlainString()+"元");
        checks.add(new Check(code,matches?"PASS":"FAIL",message,evidence));
        if(!matches)findings.add(new Finding(code,"WARNING",name+"不一致",message,"请核对所引原文金额及适用口径。",evidence,"DETERMINISTIC_F02"));
    }
}
