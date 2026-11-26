package com.fuquan.audit.budget.application;

import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import java.util.*;
import java.util.regex.Pattern;

/** One prohibited expense may be described in the request, spreadsheet and scan. */
final class BudgetPurposeConsolidation {
    private BudgetPurposeConsolidation() {}
    private static final class Group {
        final String expense;final List<Evidence> rules;final List<Assessment> members=new ArrayList<>();
        Group(String expense,List<Evidence> rules,Assessment first){this.expense=expense;this.rules=rules;members.add(first);}
        Assessment result(){
            var chosen=members.stream().filter(a->!prohibitions(a,expense.substring(0,expense.indexOf('\n'))).isEmpty())
                    .max(Comparator.comparingInt(a->a.sources().size())).orElseThrow();
            var evidence=members.stream().flatMap(a->a.sources().stream()).distinct().toList();
            return new Assessment(chosen.code(),chosen.level(),chosen.title(),chosen.explanation(),chosen.basis(),evidence);
        }
    }
    static List<Assessment> merge(List<Assessment> items,List<Line> lines){
        var groups=new ArrayList<Group>();var membership=new IdentityHashMap<Assessment,Group>();
        for(var item:items){
            String expense=expense(item,lines);if(expense==null)continue;
            var rules=prohibitions(item,expense.substring(0,expense.indexOf('\n')));if(rules.isEmpty())continue;
            var group=groups.stream().filter(g->g.expense.equals(expense)&&sameRules(g.rules,rules)).findFirst().orElse(null);
            if(group==null){group=new Group(expense,rules,item);groups.add(group);}else group.members.add(item);
            membership.put(item,group);
        }
        for(var item:items)if(!membership.containsKey(item)){
            String expense=expense(item,lines);if(expense==null||item.sources().stream().anyMatch(e->
                    e.excerpt()!=null&&e.excerpt().matches("(?s).*(?:应|须|不得|禁止|限于|仅限).*")))continue;
            var matches=groups.stream().filter(g->g.expense.equals(expense)).toList();
            if(matches.size()==1){matches.get(0).members.add(item);membership.put(item,matches.get(0));}
        }
        var result=new ArrayList<Assessment>();var emitted=Collections.newSetFromMap(new IdentityHashMap<Group,Boolean>());
        for(var item:items){var group=membership.get(item);if(group==null)result.add(item);else if(emitted.add(group))result.add(group.result());}
        return List.copyOf(result);
    }
    private static String expense(Assessment item,List<Line> lines){
        if(!item.code().equals("SEMANTIC_PURPOSE_SCOPE")||!item.level().equals("WARNING"))return null;
        var matches=new HashSet<String>();
        for(var line:lines)if(line.subject()!=null&&line.subject().length()>=2&&line.declaredAmount()!=null){
            var pattern=Pattern.compile(Pattern.quote(line.subject())+"\\h*(?:[:：|]|(?:申报)?金额(?:为)?)?\\h*([0-9]+(?:[,，][0-9]{3})*(?:\\.[0-9]+)?\\h*(?:万元|元|万)?)");
            for(var source:item.sources())if(source.excerpt()!=null){
                var matcher=pattern.matcher(source.excerpt());while(matcher.find()){
                    var amount=BudgetUploadedMaterialAnalyzer.money(matcher.group(1));
                    if(amount!=null&&amount.compareTo(line.declaredAmount())==0)
                        matches.add(line.subject()+"\n"+amount.stripTrailingZeros().toPlainString());
                }
            }
        }
        return matches.size()==1?matches.iterator().next():null;
    }
    private static List<Evidence> prohibitions(Assessment item,String subject){
        var pattern=Pattern.compile("(?:不得|禁止)(?:列支|支出|用于|支付)[^。；\\n]{0,20}"+Pattern.quote(subject));
        return item.sources().stream().filter(e->e.excerpt()!=null&&pattern.matcher(e.excerpt()).find()).toList();
    }
    private static boolean sameRules(List<Evidence> a,List<Evidence> b){
        return a.stream().allMatch(e->b.stream().anyMatch(v->sameRule(e,v)))&&b.stream().allMatch(e->a.stream().anyMatch(v->sameRule(e,v)));
    }
    private static boolean sameRule(Evidence a,Evidence b){
        return a.taskId().equals(b.taskId())&&Objects.toString(a.locator(),"").replaceFirst("/(?:record:[0-9]+:[0-9]+|line:[0-9]+)$","")
                .equals(Objects.toString(b.locator(),"").replaceFirst("/(?:record:[0-9]+:[0-9]+|line:[0-9]+)$",""))
                &&(a.excerpt().contains(b.excerpt())||b.excerpt().contains(a.excerpt()));
    }
}
