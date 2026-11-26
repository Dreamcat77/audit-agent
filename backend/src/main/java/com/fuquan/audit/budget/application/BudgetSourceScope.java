package com.fuquan.audit.budget.application;

import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.util.*;
import java.util.regex.Pattern;

/** Source labels and a unit-wide annual notice cannot be reinterpreted as project expense rows. */
final class BudgetSourceScope {
    private BudgetSourceScope() {}
    static boolean wrongSubjectRole(Fact fact) {
        return wrongSubjectRole(fact,Objects.toString(fact.excerpt(),""));
    }
    static boolean wrongSubjectRole(Fact fact,String source) {
        if(!fact.field().equals("subjectName"))return false;
        String quote=Objects.toString(fact.excerpt(),""),value=Pattern.quote(fact.value());
        // A cropped model quotation can hide that the name refers to another
        // allocation list. Inspect only its uniquely identified physical line.
        int at=source.indexOf(quote);
        if(!quote.isEmpty()&&at>=0&&at==source.lastIndexOf(quote)){
            int offset=quote.indexOf(fact.value());
            if(offset>=0){int position=at+offset,start=source.lastIndexOf('\n',Math.max(0,position-1))+1,end=source.indexOf('\n',position);
                quote=source.substring(start,end<0?source.length():end);}
        }
        if(Pattern.compile("(?:科目名称|预算科目|支出项目|支出科目)\\s*[:：|]\\s*"+value).matcher(quote).find())return false;
        if(Pattern.compile(value+"\\h*(?:的)?\\h*(?:资金|预算|经费)(?:安排|分配)[^\\r\\n。；;]{0,10}(?:按|以|依据|参照)[^\\r\\n。；;]{0,40}(?:清单|明细|表)[^\\r\\n。；;]{0,20}(?:执行|为准)").matcher(quote).find())return true;
        return Pattern.compile("(?:^|[\\r\\n；;|])\\h*(?:单位|申报单位|申请单位|采购人|供应商名称|甲方|乙方)\\h*[:：]\\h*"+value+"(?=$|[\\s；;。|])")
                .matcher(quote).find();
    }
    static boolean annualUnitBackground(List<Segment> segments,List<Row> rows) {
        String source=String.join("\n",segments.stream().map(Segment::text).toList());
        String title=source.stripLeading().split("\\R",2)[0].strip();
        boolean annualTitle=title.matches("(?:[0-9]{4}年?)?年度财政支出预算(?:[（(]预[）)])?通知书")
                ||title.matches("关于(?:下达|印发|批复)(?:[0-9]{4}年?)?年度财政支出预算(?:[（(]预[）)])?的(?:通知|复函)");
        boolean unitScope=Pattern.compile("(?:本|你|贵|该)单位(?:[0-9]{4}年?)?年度财政支出预算|单位年度总预算|主管单位年度财政总盘子").matcher(source).find();
        if(!annualTitle||!unitScope)return false;
        // A unit-wide notice may itself include a genuine project allocation table.
        // Never discard those allocations merely because its cover is annual.
        if(Pattern.compile("(?:项目名称|项目编号|科目名称|预算科目|一级名称|二级名称|三级名称)\\h*(?:[:：|]|$)",Pattern.MULTILINE).matcher(source).find())return false;
        // A model may verbatim relabel the annual document title as a project.
        // An explicit statement excluding project allocations is stronger than
        // that optional candidate; actual project headings above still win.
        if(source.matches("(?s).*(?:不列具体项目预算|不代表本批项目的批复金额|不替代单个项目下达额度).*"))return true;
        return rows.stream().flatMap(r->r.facts().stream()).noneMatch(f->Set.of("projectName","projectCode").contains(f.field())
                &&!Set.of("REFERENCE","GENERAL").contains(Objects.toString(f.subjectScope(),"")));
    }
    static List<Row> asBackground(List<Row> rows) {
        return rows.stream().map(r->new Row(r.rowKey(),r.facts().stream()
                .map(f->new Fact(f.field(),f.value(),f.locator(),f.excerpt(),"GENERAL",f.amountScope())).toList())).toList();
    }
}
