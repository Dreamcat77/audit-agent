package com.fuquan.audit.common.privacy;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Source-only party definitions. Keep conformance with audit_ai.client_party. */
final class ClientPartyMatcher {
    private static final List<String> LABELS=List.of("甲方名称","乙方名称","采购人名称","采购单位名称","委托单位名称",
            "甲方","乙方","采购人","采购单位","采购方","买方","需方","委托人","委托方","委托单位",
            "发包人","发包方","出租人","出租方","承租人","承租方","供应商","供方","卖方","受托人","受托方","承包人","承包方","服务方");
    private static final String LABEL=String.join("|",LABELS.stream().map(s->String.join("[^\\S\\r\\n]*",s.split(""))).toList());
    private static final Pattern FIELD=Pattern.compile("(?m)(?:^|[|｜;；])[^\\S\\r\\n]*(("+LABEL+
            ")(?:[^\\S\\r\\n]*[（(][^）)\\r\\n]{0,40}[）)])?)[^\\S\\r\\n]*(?:[:：]|\\r?\\n|[^\\S\\r\\n]{2,})[^\\S\\r\\n]*([^\\r\\n|｜;；]{0,220})",Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ROLE=Pattern.compile("甲方|乙方");
    private static final Pattern ANNOTATION=Pattern.compile("\\((?:(?:以下)?(?:简称|称|统称|称为|作为))?[“”\"'‘’]*(甲方|乙方|采购人|采购方|委托人|委托方|买方|需方|盖章|签章|公章)[“”\"'‘’]*\\)");
    private static final Pattern HEADING=Pattern.compile("^(?:(?:附件|附录).{0,20})?(?:历史业绩|类似业绩|过往业绩|参考合同|历史合同)");
    private static final Pattern ORG_SUFFIX=Pattern.compile("(?:公司|企业|分局|支队|大队|派出所|委员会|事务所|合作社|医院|学校|学院)$");
    private static final Map<String,String> FUNCTIONAL=Map.ofEntries(
        Map.entry("采购人","PURCHASER"),Map.entry("采购单位","PURCHASER"),Map.entry("采购方","PURCHASER"),
        Map.entry("委托人","PRINCIPAL"),Map.entry("委托方","PRINCIPAL"),Map.entry("委托单位","PRINCIPAL"),
        Map.entry("买方","BUYER"),Map.entry("需方","BUYER"),Map.entry("卖方","SELLER"),
        Map.entry("供应商","SUPPLIER"),Map.entry("供方","SUPPLIER"),Map.entry("受托人","SERVICE_PROVIDER"),
        Map.entry("受托方","SERVICE_PROVIDER"),Map.entry("服务方","SERVICE_PROVIDER"),
        Map.entry("发包人","EMPLOYER"),Map.entry("发包方","EMPLOYER"),Map.entry("承包人","CONTRACTOR"),Map.entry("承包方","CONTRACTOR"),
        Map.entry("出租人","LESSOR"),Map.entry("出租方","LESSOR"),Map.entry("承租人","LESSEE"),Map.entry("承租方","LESSEE"));
    private record Field(String functional,String value,Set<String> roles,int end){}
    private static final Pattern ALIAS=Pattern.compile("^(.*?)\\((?:以下简称|以下称|简称|下称)[:：]?[“”\"'‘’]*([\\w\\u4e00-\\u9fff]{2,20})[“”\"'‘’]*\\)[，,。.]*$",Pattern.UNICODE_CHARACTER_CLASS);

    static String normalize(String value){
        return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("(?U)[\\s\\u200b\\ufeff]+","").replace('巿','市');
    }
    static Set<String> variants(List<String> names){
        var result=new LinkedHashSet<String>();names.forEach(n->result.add(normalize(n)));
        for(var n:List.copyOf(result)){
            if(n.matches("警保(?:处|科)"))result.add(n.replace("警保","警务保障"));
            if(n.matches(".{2,60}市公安局"))result.add(n.substring(0,n.length()-4)+"公安局");
        }
        var parents=result.stream().filter(n->n.matches(".{1,100}(?:局|厅|委员会)")).toList();
        var departments=result.stream().filter(n->n.matches(".{1,32}(?:处|科|办公室|中心)")&&!parents.contains(n)).toList();
        for(var parent:parents)for(var department:departments){result.add(parent+department);result.add(parent+"("+department+")");}
        return result;
    }
    private static boolean entity(String value,Set<String> wanted){
        var s=normalize(value).replaceAll("^[，,。.“”\"'‘’]+|[，,。.“”\"'‘’]+$","");
        s=ANNOTATION.matcher(s).replaceAll("").replaceAll("^[，,。.“”\"'‘’]+|[，,。.“”\"'‘’]+$","");
        s=s.replaceFirst("\\((?:主管|主管单位)\\)$", "");
        s=s.replaceAll("\\((?:以下简称|以下称|简称|下称)[:：]?[^()]{1,30}\\)$","").replaceAll("^[，,。.“”\"'‘’]+|[，,。.“”\"'‘’]+$","");
        s=s.split("[,，](?:地址|住所|法定代表人|负责人|联系人|联系电话)[:：]",2)[0];
        if(s.endsWith("本级")&&wanted.contains(s.substring(0,s.length()-2)))s=s.substring(0,s.length()-2);
        return wanted.contains(s);
    }
    private static Set<String> roles(String label,String value){
        var result=new HashSet<String>();var direct=ROLE.matcher(normalize(label));while(direct.find())result.add(direct.group());
        var annotations=Pattern.compile("[（(]([^）)]{0,40})[）)]").matcher(value);
        while(annotations.find()){
            var a=normalize(annotations.group(1));
            if(a.matches("(?:以下)?(?:简称|称|统称|称为)?[“”\"'‘’]*(?:甲方|乙方)[“”\"'‘’]*")){
                var m=ROLE.matcher(a);while(m.find())result.add(m.group());
            }
        }
        return result;
    }
    private static int cutoff(String text){
        var lines=Pattern.compile("(?m)^.*$").matcher(text);
        while(lines.find())if(HEADING.matcher(normalize(lines.group())).find())return lines.start();
        return text.length();
    }
    private static Set<String> declaredAliases(String text,Set<String> wanted){
        var definitions=new HashMap<String,Set<Boolean>>();var matcher=FIELD.matcher(text);int cutoff=cutoff(text);
        while(matcher.find()){
            if(matcher.start()>=cutoff)break;
            var alias=ALIAS.matcher(normalize(matcher.group(3)));
            if(alias.matches()&&!LABELS.contains(alias.group(2))&&!Set.of("双方","我方","对方","客户").contains(alias.group(2)))
                definitions.computeIfAbsent(alias.group(2),k->new HashSet<>()).add(entity(alias.group(1),wanted));
        }
        var result=new HashSet<String>();definitions.forEach((alias,known)->{if(known.equals(Set.of(true)))result.add(alias);});return result;
    }
    static String party(String text,List<String> names){
        var wanted=variants(names);var found=new HashSet<String>();var functionalFound=new HashSet<String>();var bound=new HashMap<String,Set<String>>();var fields=new ArrayList<Field>();
        wanted.addAll(declaredAliases(text,wanted));int cutoff=cutoff(text);
        var matcher=FIELD.matcher(text);
        while(matcher.find()){
            if(matcher.start()>=cutoff)break;
            String functional=normalize(matcher.group(2)).replaceFirst("名称$",""),value=matcher.group(3);var roles=roles(matcher.group(1),value);
            if(!roles.isEmpty())bound.computeIfAbsent(functional,k->new HashSet<>()).addAll(roles);
            fields.add(new Field(functional,value,roles,matcher.end()));
        }
        for(var field:fields){
            var roles=field.roles.isEmpty()?bound.getOrDefault(field.functional,Set.of()):field.roles;
            var tail=text.substring(field.end,Math.min(field.end+240,text.length())).split("\\R",-1);var continuations=new ArrayList<String>();int start=tail.length>0&&tail[0].isEmpty()?1:0;
            for(int i=start;i<Math.min(start+2,tail.length);i++){
                var line=tail[i].strip();if(line.isEmpty()||Pattern.compile("[:：|；;。]").matcher(line).find()||normalize(line).length()>50)break;
                continuations.add(line);
            }
            String candidate=field.value;boolean matched=entity(candidate,wanted);
            if(matched&&!continuations.isEmpty()&&ORG_SUFFIX.matcher(normalize(continuations.get(0))).find())matched=false;
            if(!matched)for(var line:continuations){candidate+=line;if(entity(candidate,wanted)){matched=true;break;}}
            if(matched){
                for(var role:roles)found.add(role.equals("甲方")?"PARTY_A":"PARTY_B");
                if(FUNCTIONAL.containsKey(field.functional))functionalFound.add(FUNCTIONAL.get(field.functional));
            }
        }
        var result=found.isEmpty()?functionalFound:found;
        return result.size()>1?"AMBIGUOUS":result.stream().findFirst().orElse("UNMATCHED");
    }
}
