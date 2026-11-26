package com.fuquan.audit.budget.application;

import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import com.fuquan.audit.common.material.visual.VisualIndex;
import java.util.*;

/** Reconstruct explicit budget columns from immutable page coordinates, never text adjacency. */
final class BudgetPositionedTableReader {
    record Span(int start,int end) {}
    record Read(List<Row> rows,Map<String,List<Span>> consumed) {}
    private record Token(Segment segment,int start,int end,String value,double left,double right,double top,double bottom,int direction) {
        double x(){return (left+right)/2;} double y(){return (top+bottom)/2;}
    }
    private record Column(Token token,String field,String unit) {}
    static Read read(List<Segment> segments,VisualIndex index) {
        var rows=new ArrayList<Row>();var consumed=new HashMap<String,List<Span>>();
        if(index==null)return new Read(rows,consumed);
        var pages=new LinkedHashMap<String,List<Token>>();int offset=0;
        for(var segment:segments) {
            int base=offset;offset+=segment.text().length()+1;
            if(!segment.locator().startsWith("pdf/page:"))continue;
            for(var a:index.anchors()) {
                if(a.start()<base||a.end()>base+segment.text().length()||a.confidence()<0.85||a.verification().equals("UNCERTAIN"))continue;
                var p=a.polygon();double dx=p.get(1).x()-p.get(0).x(),dy=p.get(1).y()-p.get(0).y();
                int direction=Math.abs(dx)>=Math.abs(dy)?(dx>=0?0:2):(dy>=0?1:3);
                double left=1,right=0,top=1,bottom=0;
                for(var point:p) {
                    double x=point.x(),y=point.y();double u=switch(direction){case 1->y;case 2->1-x;case 3->1-y;default->x;};
                    double v=switch(direction){case 1->1-x;case 2->1-y;case 3->x;default->y;};
                    left=Math.min(left,u);right=Math.max(right,u);top=Math.min(top,v);bottom=Math.max(bottom,v);
                }
                String value=segment.text().substring(a.start()-base,a.end()-base).strip();
                if(value.isEmpty())continue;
                var token=new Token(segment,a.start()-base,a.end()-base,value,left,right,top,bottom,direction);
                pages.computeIfAbsent(a.pageNumber()+"/"+a.method()+"/"+direction,k->new ArrayList<>()).add(token);
            }
        }
        for(var tokens:pages.values()) {
            tokens.sort(Comparator.comparingDouble(Token::y).thenComparingDouble(Token::x));
            // Each table requires its own same-line identity and amount headers. No header inheritance across pages.
            for(int h=0;h<tokens.size();h++) {
                var seed=tokens.get(h);if(!"subjectName".equals(field(seed.value())))continue;
                var columns=new ArrayList<Column>();
                for(var t:tokens)if(Math.abs(t.y()-seed.y())<=Math.max(0.006,(seed.bottom()-seed.top())*0.6)) {
                    String field=field(t.value());columns.add(new Column(t,field==null?"":field,unit(t.value())));
                }
                if(columns.stream().noneMatch(c->c.field().endsWith("Amount"))||columns.stream().filter(c->!c.field().isEmpty()).map(Column::field).distinct().count()!=columns.stream().filter(c->!c.field().isEmpty()).count())continue;
                columns.sort(Comparator.comparingDouble(c->c.token().x()));
                double stop=tokens.stream().filter(t->t.y()>seed.y()+0.012&&field(t.value())!=null)
                        .mapToDouble(Token::top).min().orElse(1);
                var identities=tokens.stream().filter(t->t.y()>seed.y()+0.008&&t.y()<stop&&column(t,columns).equals("subjectName")
                        &&field(t.value())==null&&!t.value().matches("[0-9]+|合计|总计")&&t.value().length()>1).toList();
                boolean any=false;
                for(int n=0;n<identities.size();n++) {
                    var identity=identities.get(n);
                    double tolerance=Math.max(0.007,(identity.bottom()-identity.top())*0.8);
                    var cells=new LinkedHashMap<String,List<Token>>();
                    for(var t:tokens)if(t.y()>seed.y()+0.008&&t.y()<stop&&Math.abs(t.y()-identity.y())<=tolerance) {
                        String f=column(t,columns);if(!f.isEmpty()&&!(f.equals("subjectName")&&t.value().matches("[0-9]+")))cells.computeIfAbsent(f,k->new ArrayList<>()).add(t);
                    }
                    // Ambiguous cells (wrapped rows, overlapping tables) fall back to the model intact.
                    if(cells.values().stream().anyMatch(v->v.size()!=1)||cells.getOrDefault("subjectName",List.of()).size()!=1)continue;
                    var facts=new ArrayList<Fact>();var used=new ArrayList<Token>();
                    for(var c:columns) {
                        if(c.field().isEmpty())continue;
                        var matches=cells.getOrDefault(c.field(),List.of());if(matches.isEmpty())continue;
                        var t=matches.get(0);String value=t.value();
                        if(c.field().endsWith("Amount")) {
                            if(!value.matches("[¥￥]?[0-9０-９][0-9０-９,，.． ]*(?:元|千元|万元)?"))continue;
                            if(!value.matches(".*(?:万元|千元|元)$")&&c.unit()!=null)value+=c.unit();
                            if(BudgetUploadedMaterialAnalyzer.money(value)==null)continue;
                        }
                        if(c.field().equals("subjectCode")&&!value.matches("[A-Za-z0-9][A-Za-z0-9._/\\-]*"))continue;
                        facts.add(new Fact(c.field(),value,t.segment().locator()+"/record:"+t.start()+":"+t.end(),t.value()));used.add(t);
                    }
                    if(facts.stream().noneMatch(f->f.field().endsWith("Amount")))continue;
                    rows.add(new Row(identity.segment().locator()+"/positioned:"+identity.start(),facts));any=true;
                    used.forEach(t->consumed.computeIfAbsent(t.segment().locator(),k->new ArrayList<>()).add(new Span(t.start(),t.end())));
                }
                if(any)columns.stream().filter(c->!c.field().isEmpty()).forEach(c->{var t=c.token();consumed.computeIfAbsent(t.segment().locator(),k->new ArrayList<>()).add(new Span(t.start(),t.end()));});
            }
        }
        return new Read(List.copyOf(rows),consumed);
    }
    private static String column(Token token,List<Column> columns) {
        for(int i=0;i<columns.size();i++) {
            double left=i==0?columns.get(i).token().left()-0.025:(columns.get(i-1).token().x()+columns.get(i).token().x())/2;
            double right=i==columns.size()-1?columns.get(i).token().right()+0.025:(columns.get(i).token().x()+columns.get(i+1).token().x())/2;
            if(token.x()>=left&&token.x()<right)return columns.get(i).field();
        }
        return "";
    }
    private static String field(String label) {
        String s=label.replaceAll("\\s+","");
        if(s.matches("(?:序号)?(?:预算科目|科目名称|支出项目|预算项目)(?:名称)?"))return "subjectName";
        if(s.matches("科目(?:编码|代码)"))return "subjectCode";
        if(s.matches("(?:本次)?(?:申报|申请)金额(?:[（(](?:元|千元|万元)[）)])?"))return "declaredAmount";
        if(s.matches("(?:批复|批准|批复参考)金额(?:[（(](?:元|千元|万元)[）)])?"))return "approvalAmount";
        return null;
    }
    private static String unit(String s){return s.contains("万元")?"万元":s.contains("千元")?"千元":s.contains("元")?"元":null;}
}
