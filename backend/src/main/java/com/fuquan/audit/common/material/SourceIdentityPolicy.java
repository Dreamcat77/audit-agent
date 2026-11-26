package com.fuquan.audit.common.material;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Identity roles come from the label attached to this value, never another value in the excerpt. */
public final class SourceIdentityPolicy {
    private SourceIdentityPolicy() {}
    public static String key(String value) {
        return Normalizer.normalize(Objects.toString(value,""),Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}\\u200b\\ufeff]+","").replaceAll("[，,。；;：:]+$","");
    }
    private static final Pattern LABEL=Pattern.compile(
        "合同(?:统一|备案|登记)?编号|合同号|采购项目编号|项目(?:统一)?编号|招标(?:项目)?编号|采购编号|申报编号|申请编号|统一社会信用代码|营业执照编号|"
        +"采购代理(?:机构)?(?:名称)?|招标代理(?:机构)?(?:名称)?|代理机构(?:名称)?|(?:中标|成交)(?:供应商|单位|人)(?:名称)?|"
        +"投标(?:人|单位)(?:名称)?|报价(?:人|单位)(?:名称)?|响应供应商(?:名称)?|供应商(?:名称)?|"
        +"采购(?:人|单位)(?:名称)?|招标人(?:名称)?|委托(?:方|单位)(?:名称)?|甲方(?:名称)?|乙方(?:名称)?");
    /** Returns null when the source has no explicit label for this exact value. */
    public static String role(String value,String excerpt) {
        String text=key(excerpt),needle=key(value);if(needle.isBlank())return null;
        var roles=new HashSet<String>();int from=0,at;
        while((at=text.indexOf(needle,from))>=0){
            from=at+Math.max(1,needle.length());
            var labels=LABEL.matcher(text.substring(Math.max(0,at-100),at));String last=null;int end=-1;
            while(labels.find()){last=labels.group();end=labels.end();}
            if(last==null)continue;
            String gap=text.substring(Math.max(0,at-100)+end,at);
            // A second name or number between a label and value is not a binding.
            if(!gap.matches("(?:[：:=为是，,]|[（(][^()]{0,24}[）)]|[“”\"‘’])*"))continue;
            String role=last.startsWith("合同")?"contractNo":last.contains("项目")&&!last.startsWith("招标")?"projectNo":
                last.startsWith("招标")&&last.endsWith("编号")?"tenderNo":last.equals("采购编号")?"procurementNo":
                last.matches("(?:申报|申请)编号")?"applicationNo":last.matches(".*(?:信用代码|执照编号)")?"creditCode":
                last.contains("代理")?"agencyName":last.startsWith("中标")||last.startsWith("成交")?"awardSupplier":
                last.startsWith("投标")||last.startsWith("报价")||last.startsWith("响应")||last.startsWith("供应商")||last.startsWith("乙方")?"supplierName":"purchaserName";
            roles.add(role);
        }
        return roles.size()==1?roles.iterator().next():null;
    }
    public static String identityKey(String value) {
        return key(value).replaceAll("[（(](?:主管|主管单位|采购人|采购单位|甲方|乙方|盖章|签章)[）)]","");
    }
    public static boolean placeholder(String value){
        String name=key(value);
        if(name.isBlank())return true;
        if(name.matches("(?is)(?:\\$\\{.*}|\\{\\{.*}}|«.*»|<%.*%>)"))return true;
        String label="(?:项目名称|项目编号|合同名称|合同编号|供应商名称|采购人名称|采购单位名称|单位名称|公司名称|甲方(?:单位)?名称|乙方(?:单位)?名称|招标单位名称|预算金额|合同金额|subjectName|supplierName|purchaserName|projectName|projectNo|contractNo|partyA|partyB)";
        // Merge fields often contain their module prefix. Brackets alone do not
        // make a genuine organization or project name a placeholder.
        if(name.matches("(?i)[\\[【].{0,60}?"+label+"[\\]】]"))return true;
        return name.matches("(?i)"+label+"[:：]?|(?:甲方|乙方|丙方|采购人|供应商|投标人|待定|待填(?:写)?|未填(?:写)?|无|暂无|未知|不详|null|unknown|N/?A)(?:[（(].*?[）)])?[:：]?|[_＿—-]+");
    }
    /** Field-specific checks supplement source grounding; a verbatim merge field is still not a fact. */
    public static boolean plausible(String field,String value){
        if(placeholder(value))return false;
        String name=key(value);
        if(Set.of("supplierName","purchaserName","agencyName","partyA","partyB","awardSupplier","departmentName").contains(field))
            return name.codePoints().anyMatch(Character::isLetter)&&!name.matches("(?:甲方|乙方|丙方)[:：](?:甲方|乙方|丙方)");
        return true;
    }
    /** Explicit source aliases only. Equal organization suffixes or fuzzy spelling do not prove equal parties. */
    public static Map<String,String> aliases(Collection<String> texts) {
        var candidates=new HashMap<String,Set<String>>();
        var pattern=Pattern.compile("([^\\r\\n：:；;，,|]{2,100})[（(](?:以下简称|简称|以下称)[：:]?[“\"‘]?([^）)“”\"‘’]{2,30})[”\"’]?[）)]");
        for(String text:texts){var m=pattern.matcher(text);while(m.find())if(!placeholder(m.group(2)))
            candidates.computeIfAbsent(identityKey(m.group(2)),k->new HashSet<>()).add(identityKey(m.group(1)));}
        var result=new HashMap<String,String>();candidates.forEach((k,v)->{if(v.size()==1)result.put(k,v.iterator().next());});return result;
    }
}
