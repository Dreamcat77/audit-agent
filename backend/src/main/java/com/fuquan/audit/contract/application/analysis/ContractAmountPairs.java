package com.fuquan.audit.contract.application.analysis;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Compare locally paired price operands, never a whole-file cartesian product. */
final class ContractAmountPairs {
    private ContractAmountPairs() {}
    record Operand(Fact fact,Evidence evidence,int at,int end,BigDecimal amount) {}
    record Pair(Operand number,Operand words) {}
    private static final Pattern ROLE=Pattern.compile("合同总价|合同金额|合同价款|价款总额|总价款|价税合计|履约保证金|保证金|违约金|预算金额|最高限价|投标报价|投标总价|中标金额|分项金额|单价|预付款|首付款|尾款");
    static List<Pair> pairs(List<Fact> facts,String text) {
        var numbers=operands(facts,"TOTAL_AMOUNT",text);var words=operands(facts,"AMOUNT_WORDS",text);
        var result=new LinkedHashMap<String,Pair>();
        for(var number:numbers){
            var candidates=words.stream().filter(w->related(number,w,text)).toList();
            if(candidates.isEmpty())continue;
            int best=candidates.stream().mapToInt(w->Math.abs(w.at()-number.at())).min().orElseThrow();
            var closest=candidates.stream().filter(w->Math.abs(w.at()-number.at())==best).toList();
            if(closest.size()!=1)continue;
            var word=closest.get(0);
            int reverse=numbers.stream().filter(n->related(n,word,text)).mapToInt(n->Math.abs(n.at()-word.at())).min().orElseThrow();
            if(reverse!=best||numbers.stream().filter(n->related(n,word,text)&&Math.abs(n.at()-word.at())==reverse).count()!=1)continue;
            result.put(number.at()+"/"+word.at(),new Pair(number,word));
        }
        return List.copyOf(result.values());
    }
    private static boolean related(Operand n,Operand w,String text){
        if(!n.evidence().fileId().equals(w.evidence().fileId())
                ||!n.evidence().parseResultId().equals(w.evidence().parseResultId())
                ||n.evidence().parseVersion()!=w.evidence().parseVersion()
                ||!n.evidence().textSha256().equals(w.evidence().textSha256())
                ||!n.evidence().fileSha256().equals(w.evidence().fileSha256()))return false;
        int from=Math.min(n.at(),w.at()),to=Math.max(n.end(),w.end());
        if(to-from>320)return false;
        String between=text.substring(from,to);
        if(between.chars().filter(c->c=='\n').count()>3||between.contains("\n\n"))return false;
        // Do not pair operands across another monetary role, heading, or event.
        return !between.matches("(?s).*(?:保证金|违约金|预算|投标|中标|最高限价|分项|单价|预付款|首付款|尾款|第[一二三四五六七八九十0-9]+条).*" );
    }
    private static List<Operand> operands(List<Fact> facts,String type,String text){
        var result=new LinkedHashMap<String,Operand>();
        for(var f:facts)if(f.type().equals(type)){
            var amount=ContractAnalysisRules.money(f.value());if(amount.isEmpty())continue;
            for(var e:f.evidence()){
                if(e.start()<0||e.end()>text.length()||!text.substring(e.start(),e.end()).equals(e.excerpt()))continue;
                String needle=f.value();var literal=Pattern.compile("(?<![0-9][,，])(?<![0-9.零壹贰叁肆伍陆柒捌玖拾佰仟万亿\\-−])"+Pattern.quote(needle)+"(?![0-9.零壹贰叁肆伍陆柒捌玖拾佰仟万亿])").matcher(e.excerpt());
                if(literal.find()){
                    do {int at=e.start()+literal.start(),end=e.start()+literal.end();
                        if(contractRole(text,at)&&renminbi(text,at,end))result.put(at+"/"+amount.get(),new Operand(f,e,at,end,amount.get()));
                    }while(literal.find());
                }else if(type.equals("TOTAL_AMOUNT"))for(int offset:ContractAnalysisEvidence.numericValueOffsets(type,f.value(),e.excerpt())){
                    int at=e.start()+offset;if(contractRole(text,at)&&renminbi(text,at,at+1))result.put(at+"/"+amount.get(),new Operand(f,e,at,at+1,amount.get()));
                }
            }
        }
        return List.copyOf(result.values());
    }
    private static boolean renminbi(String text,int at,int end){
        int lineStart=text.lastIndexOf('\n',Math.max(0,at-1))+1;
        int lineEnd=text.indexOf('\n',end);if(lineEnd<0)lineEnd=text.length();
        String local=text.substring(Math.max(lineStart,at-50),Math.min(lineEnd,end+30));
        return !Pattern.compile("美元|美金|港币|港元|欧元|日元|英镑|(?i:USD|HKD|EUR|JPY|GBP)|US\\$|(?<![￥¥])\\$").matcher(local).find();
    }
    static boolean contractRole(String text,int at){
        String prefix=text.substring(Math.max(0,at-240),at);var m=ROLE.matcher(prefix);String role=null;
        while(m.find())role=m.group();
        return role==null||Set.of("合同总价","合同金额","合同价款","价款总额","总价款","价税合计").contains(role);
    }
    static boolean evaluate(List<Fact> facts,String text,List<Check> checks,List<Finding> findings){
        var pairs=pairs(facts,text);var emitted=new HashSet<String>();
        for(var pair:pairs){
            String key=pair.number().amount().stripTrailingZeros()+"/"+pair.words().amount().stripTrailingZeros();
            if(!emitted.add(key))continue;
            var evidence=exactPairEvidence(pair,text);
            ContractAnalysisRules.compare(checks,findings,com.fuquan.audit.contract.domain.F02RuleCodes.AMOUNT_WORDS_MATCH,
                    "合同金额大小写核对",pair.number().amount(),pair.words().amount(),evidence);
        }
        return !pairs.isEmpty();
    }
    private static List<Evidence> exactPairEvidence(Pair pair,String text){
        int at=Math.min(pair.number().at(),pair.words().at()),end=Math.max(pair.number().end(),pair.words().end());
        int from=text.lastIndexOf('\n',Math.max(0,at-1))+1;
        if(at-from>160)from=Math.max(from,at-80);
        var caption=ROLE.matcher(text.substring(from,at));int captionStart=-1;
        while(caption.find())captionStart=caption.start();
        if(captionStart>=0)from+=captionStart;
        int lineEnd=text.indexOf('\n',end);int to=lineEnd<0?text.length():lineEnd;
        to=Math.min(to,end+120);
        var nextRole=ROLE.matcher(text.substring(end,to));if(nextRole.find())to=end+nextRole.start();
        var e=pair.number().evidence();
        return List.of(new Evidence(e.fileId(),e.fileSha256(),e.parseResultId(),e.parseVersion(),e.textSha256(),from,to,text.substring(from,to)));
    }
}
