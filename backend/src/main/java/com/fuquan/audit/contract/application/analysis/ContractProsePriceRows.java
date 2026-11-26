package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import java.util.regex.Pattern;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Explicit quantity/price/amount sentences; no inferred rows or completeness. */
final class ContractProsePriceRows {
    private static final String NUMBER="[0-9]+(?:[,，][0-9]{3})*(?:\\.[0-9]{1,4})?";
    private static final Pattern CAPTION=Pattern.compile("(?:费用明细|合同价款明细)[：:]\\h*");
    private static final Pattern ROW=Pattern.compile("[^；;。]{1,160}?数量\\h*("+NUMBER+")\\h*(?:次|项|月|件|套|人|台|年|日|天|批|个)\\h*[，,]\\h*单价\\h*("+NUMBER+"\\h*(?:万元|元))\\h*[，,]\\h*金\\s*额\\h*("+NUMBER+"\\h*(?:万元|元))\\h*");
    private static final Pattern COMPLETE=Pattern.compile("\\s*(?:以上|上述)(?:为|是)(?:本)?合同全部价款明细[，,]\\h*明细合计(?:人民币)?\\h*"+NUMBER+"\\h*(?:万元|元)[。.]?");
    private ContractProsePriceRows() {}
    static ContractAnalysisTable.Table parse(Source source,String text){
        var captions=CAPTION.matcher(text);var rows=new ArrayList<ContractAnalysisTable.Row>();
        int blocks=0;boolean complete=false;
        while(captions.find()){
            if(ContractAnalysisSemantics.reference(text,captions.start()))continue;
            blocks++;int cursor=captions.end();boolean ended=false;
            while(cursor<text.length()){
                var row=ROW.matcher(text);row.region(cursor,text.length());if(!row.lookingAt())break;
                if(row.end()<text.length()&&"；;。".indexOf(text.charAt(row.end()))<0)break;
                var q=ContractAnalysisRules.number(row.group(1));var p=ContractAnalysisRules.money(row.group(2));var a=ContractAnalysisRules.money(row.group(3));
                if(q.isEmpty()||p.isEmpty()||a.isEmpty())break;
                rows.add(new ContractAnalysisTable.Row(q.get(),p.get(),a.get(),new Evidence(source.fileId(),source.sha256(),source.parseResultId(),source.parseVersion(),source.textSha256(),cursor,row.end(),text.substring(cursor,row.end()))));
                cursor=row.end();if(cursor==text.length())break;
                char separator=text.charAt(cursor);if(separator==';'||separator=='；'){cursor++;continue;}
                if(separator=='。'){cursor++;ended=true;}break;
            }
            var tail=COMPLETE.matcher(text);tail.region(cursor,text.length());
            complete=ended&&!rows.isEmpty()&&tail.lookingAt();
        }
        return new ContractAnalysisTable.Table(List.copyOf(rows),blocks==1&&complete);
    }
}
