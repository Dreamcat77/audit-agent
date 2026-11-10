package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Consolidate the same supported issue; distinct actors, events and source versions remain separate. */
final class ContractResultConsolidation {
    private ContractResultConsolidation() {}
    static List<Finding> findings(List<Finding> input){
        var result=new ArrayList<Finding>();
        for(var f:input){int same=-1;
            for(int i=0;i<result.size();i++)if(same(result.get(i),f)){same=i;break;}
            if(same<0)result.add(f);else{
                var old=result.get(same);var preferred=old.origin().startsWith("DETERMINISTIC")?old:f.origin().startsWith("DETERMINISTIC")?f:old;
                result.set(same,new Finding(preferred.code(),preferred.level(),preferred.title(),preferred.explanation(),preferred.suggestion(),
                        Stream.concat(old.evidence().stream(),f.evidence().stream()).distinct().toList(),preferred.origin()));
            }
        }
        return List.copyOf(result);
    }
    private static Set<String> tokens(String text){
        var result=new HashSet<String>();var m=Pattern.compile("甲方|乙方|丙方|逾期交货|逾期付款|保密|验收|解除|付款|税费|含税|不含税|开票|[0-9]+(?:[,.][0-9]+)*[%％]?").matcher(text);
        while(m.find())result.add(m.group());return result;
    }
    private static boolean same(Finding a,Finding b){
        if(!a.code().equals(b.code())||!a.level().equals(b.level())||a.evidence().isEmpty()||b.evidence().isEmpty())return false;
        if(Set.of("OTHER_CONCRETE_RISK","CROSS_FILE_CONFLICT","BREACH_AMBIGUITY").contains(a.code())
                &&!ContractAnalysisSemantics.key(a.title()).equals(ContractAnalysisSemantics.key(b.title())))return false;
        if(!tokens(a.title()+a.explanation()).equals(tokens(b.title()+b.explanation())))return false;
        return covered(a.evidence(),b.evidence())&&covered(b.evidence(),a.evidence());
    }
    private static boolean covered(List<Evidence> a,List<Evidence> b){return a.stream().allMatch(x->b.stream().anyMatch(y->
            x.fileId().equals(y.fileId())&&x.parseResultId().equals(y.parseResultId())&&x.parseVersion()==y.parseVersion()
                    &&x.fileSha256().equals(y.fileSha256())&&x.textSha256().equals(y.textSha256())
                    &&Math.min(x.end(),y.end())-Math.max(x.start(),y.start())>=0.75*Math.max(x.end()-x.start(),y.end()-y.start())));}
}
