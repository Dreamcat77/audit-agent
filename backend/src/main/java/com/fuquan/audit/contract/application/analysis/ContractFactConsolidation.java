package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Merge equivalent facts, retaining every frozen source; distinct row/event identities stay distinct. */
final class ContractFactConsolidation {
    private ContractFactConsolidation() {}
    static List<Fact> merge(List<Fact> facts,String text) {
        var aliases=new HashMap<String,Set<String>>();
        var definitions=Pattern.compile("([^\\r\\n：:；;，,|]{2,100})[（(](?:以下简称|简称|以下称)[：:]?[“\"‘]?([^）)“”\"‘’]{2,30})[”\"’]?[）)]").matcher(text);
        while(definitions.find()){
            String full=definitions.group(1).strip(),alias=definitions.group(2).strip();
            if(com.fuquan.audit.contract.domain.ContractPartyName.isPresent(full)&&com.fuquan.audit.contract.domain.ContractPartyName.isPresent(alias))
                aliases.computeIfAbsent(ContractAnalysisSemantics.key(alias),k->new HashSet<>()).add(full);
        }
        var map=new LinkedHashMap<String,Fact>();
        for(var fact:facts){
            String value=fact.value();
            if(Set.of("PARTY_A","PARTY_B").contains(fact.type())){
                value=com.fuquan.audit.contract.domain.ContractPartyName.canonical(value);
                var full=aliases.get(ContractAnalysisSemantics.key(value));
                // Alias binding is usable only when the same role also has the full name as grounded evidence.
                if(full!=null&&full.size()==1){String resolved=full.iterator().next();
                    if(facts.stream().anyMatch(f->f.type().equals(fact.type())&&ContractAnalysisSemantics.key(com.fuquan.audit.contract.domain.ContractPartyName.canonical(f.value())).equals(ContractAnalysisSemantics.key(resolved))))value=resolved;
                }
            }
            String key=fact.type()+"/"+ContractAnalysisSemantics.key(value);
            if(fact.type().endsWith("AMOUNT")||fact.type().equals("AMOUNT_WORDS"))
                key=fact.type()+"/"+ContractAnalysisRules.money(value).map(n->n.stripTrailingZeros().toPlainString()).orElse(ContractAnalysisSemantics.key(value));
            if(fact.type().startsWith("ITEM_"))key+="/"+fact.evidence();
            var old=map.get(key);
            map.put(key,old==null?new Fact(fact.type(),value,fact.method(),fact.evidence()):new Fact(old.type(),old.value(),old.method(),Stream.concat(old.evidence().stream(),fact.evidence().stream()).distinct().toList()));
        }
        return List.copyOf(map.values());
    }
}
