package com.fuquan.audit.budget.application;

import java.util.*;
import java.util.regex.Pattern;
import static com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;

/** Exact, explicitly scoped prose amounts; ambiguous language remains with semantic extraction. */
final class BudgetExplicitAmounts {
    private BudgetExplicitAmounts() {}
    private static final String MONEY="(?:人民币|[￥¥])?\\h*(?:[0-9][0-9,，]*(?:\\.[0-9]{1,4})?|[零壹贰叁肆伍陆柒捌玖拾佰仟万亿]+)\\h*(?:亿元|万元|千元|元|圆)(?:整|正)?";
    private static final Pattern AMOUNT=Pattern.compile(MONEY);
    private static final Pattern REQUEST_TOTAL=Pattern.compile("(?:本项目|该项目|本次)(?:的)?(?:申请|申报)(?:预算|经费)(?:合计|总额|总计)\\h*[：:]?\\h*("+MONEY+")");
    private static final Pattern CHANGE=Pattern.compile("(?:本项目|该项目|项目)(?:的)?(?:下达预算|预算下达(?:总额|金额)?|批复(?:金额|总额)|下达(?:金额|总额)|预算总额|预算金额)\\h*(?:从|由)\\h*("+MONEY+")\\h*(?:调整为|变更为|核增至|核减至)\\h*("+MONEY+")");
    static List<Row> read(Segment segment) {
        if(segment.locator().startsWith("excel/"))return List.of();
        String text=segment.text();var rows=new ArrayList<Row>();
        var breaks=Pattern.compile("\\R").matcher(text);int start=0;
        while(start<=text.length()) {
            boolean more=breaks.find();int end=more?breaks.start():text.length();String line=text.substring(start,end);
            var total=REQUEST_TOTAL.matcher(line);
            while(total.find()) {
                String prefix=line.substring(0,total.start());
                if(prefix.matches("(?s).*(?:历史|往年|示例|参考|模板|撤销|撤回|不再|并非|不是|未申请|未申报).*"))continue;
                String locator=segment.locator()+"/record:"+(start+total.start())+":"+(start+total.end());
                if(BudgetUploadedMaterialAnalyzer.money(total.group(1))!=null)
                    rows.add(new Row(locator,List.of(new Fact("declaredTotalAmount",total.group(1),locator,total.group(),"CURRENT","TOTAL"))));
            }
            var revision=CHANGE.matcher(line);
            while(revision.find()) {
                String prefix=line.substring(0,revision.start());
                String around=text.substring(Math.max(0,start-80),Math.min(text.length(),end+180));
                boolean operative=prefix.matches("(?s).*(?:批准|同意|下达|核定|决定).*" )
                        ||around.matches("(?s).*(?:本调整替代前次|本次替代原|以本次(?:批复|下达)为准).*" );
                boolean unapproved=line.matches("(?s).*(?:拟|申请|建议|计划|待批准|尚未|未经|未获|不予|不批准|不同意|草案|征求意见).*" )
                        ||prefix.matches("(?s).*(?:历史|往年|示例|参考|模板|原批复)[：:].*" );
                if(!operative||unapproved)continue;
                String previous=revision.group(1).strip(),current=revision.group(2).strip();
                if(BudgetUploadedMaterialAnalyzer.money(previous)==null||BudgetUploadedMaterialAnalyzer.money(current)==null)continue;
                String locator=segment.locator()+"/record:"+(start+revision.start())+":"+(start+revision.end());
                rows.add(new Row(locator,List.of(new Fact("approvedTotalAmount",current,locator,revision.group(),"CURRENT","TOTAL"),
                        new Fact("previousApprovedTotalAmount",previous,locator,revision.group(),"REFERENCE","TOTAL"))));
            }
            var purpose=Pattern.compile("用于|包括|其中").matcher(line);
            if(purpose.find()) {
                String prefix=line.substring(0,purpose.start());
                if(prefix.matches("(?s).*(?:本项目|该项目|本次).*(?:申请|申报).*(?:预算|经费).*" )
                        &&!prefix.matches("(?s).*(?:历史|往年|示例|参考|不得|不用于|未用于|不申请).*" )) {
                    String tail=line.substring(purpose.end()).split("[。；;]",2)[0];var amounts=AMOUNT.matcher(tail);
                    var items=new ArrayList<Row>();int cursor=0;
                    while(amounts.find()) {
                        String raw=tail.substring(cursor,amounts.start());String leading=raw.stripLeading();
                        boolean punctuated=leading.matches("^[、，,；;].*" );
                        String name=raw.replaceFirst("^[\\h、，,；;]+", "");
                        if(cursor>0&&!punctuated)name=name.replaceFirst("^(?:以及|及|和)\\h*", "");
                        name=name.strip();String value=amounts.group().strip();
                        if(name.length()<2||name.length()>40||name.matches("(?s).*[0-9：:|].*" )
                                ||name.matches("(?:向|支付|供应商|采购人|合同|价款|预算|经费|合计|总计|不得|不含).*" )
                                ||BudgetUploadedMaterialAnalyzer.money(value)==null){items.clear();break;}
                        int itemStart=start+purpose.end()+cursor+raw.indexOf(name),itemEnd=start+purpose.end()+amounts.end();
                        String locator=segment.locator()+"/record:"+itemStart+":"+itemEnd;
                        String quote=text.substring(itemStart,itemEnd);
                        items.add(new Row(locator,List.of(new Fact("subjectName",name,locator,quote,"CURRENT",null),
                                new Fact("declaredAmount",value,locator,quote,"CURRENT",null))));
                        cursor=amounts.end();
                    }
                    // One amount can just be a rephrased total. Enumerated items are independent physical ranges.
                    if(items.size()>=2)rows.addAll(items);
                }
            }
            if(!more)break;start=breaks.end();
        }
        return List.copyOf(rows);
    }
}
