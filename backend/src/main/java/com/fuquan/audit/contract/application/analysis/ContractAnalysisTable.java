package com.fuquan.audit.contract.application.analysis;

import java.math.BigDecimal;
import java.util.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Physical rows remain distinct. Only a single explicitly scoped price table can prove a full sum. */
final class ContractAnalysisTable {
    record Row(BigDecimal quantity,BigDecimal price,BigDecimal amount,Evidence evidence) {}
    record Table(List<Row> rows,boolean complete) {}
    private record Candidate(List<Row> rows,boolean complete,boolean priceScope) {}
    static Table parse(Source source,String text){
        var tables=new ArrayList<Candidate>();var rows=new ArrayList<Row>();
        int qi=-1,pi=-1,ai=-1,offset=0;boolean active=false,scope=false,invalid=false;
        BigDecimal unit=BigDecimal.ONE;String previous="",beforePrevious="";
        for(String line:text.split("\n",-1)){
            String[] cells=line.split("\t",-1);int q=-1,p=-1,a=-1;
            for(int i=0;i<cells.length;i++){
                String cell=ContractAnalysisEvidence.compact(cells[i]);
                if(cell.matches("(?:数量|工作量)(?:[（(].*[)）])?"))q=i;
                if(cell.matches("(?:含税)?单价(?:[（(].*[)）])?"))p=i;
                if(cell.matches("(?:行金额|金额|价税合计|小计|含税经费)(?:[（(].*[)）])?"))a=i;
            }
            if(q>=0&&p>=0&&a>=0&&q!=p&&p!=a&&q!=a){
                if(active)tables.add(new Candidate(List.copyOf(rows),false,scope));
                rows=new ArrayList<>();qi=q;pi=p;ai=a;active=true;invalid=false;
                scope=(beforePrevious+"\n"+previous+"\n"+line).matches("(?s).*(合同(?:价款|价格|金额|分项|货物|标的).{0,12}(明细|清单|表)|合同价款明细|分项报价表).*");
                scope|=(beforePrevious+"\n"+previous).matches("(?s).*(?:乙方|受托方).{0,40}(?:提供|承担).{0,60}服务.{0,60}工作量.{0,20}经费.*")
                        &&Arrays.stream(cells).anyMatch(v->v.matches("服务(?:事项|内容)"));
                unit=line.contains("万元")?new BigDecimal("10000"):BigDecimal.ONE;
            }else if(active&&!line.isBlank()){
                if(Arrays.stream(cells).limit(Math.min(qi,Math.min(pi,ai)))
                        .map(ContractAnalysisEvidence::compact).anyMatch(v->v.matches("(?:总计|合计|含税合计|价税合计)(?:[（(].*[)）]|[：:])?"))){
                    boolean validTotal=cells.length>ai&&ContractAnalysisRules.money(cells[ai]).isPresent();
                    tables.add(new Candidate(List.copyOf(rows),!invalid&&validTotal&&!rows.isEmpty(),scope));active=false;
                }else if(cells.length>Math.max(qi,Math.max(pi,ai))&&line.length()<=4000){
                    var quantity=ContractAnalysisRules.number(cells[qi]);var price=ContractAnalysisRules.money(cells[pi]);var amount=ContractAnalysisRules.money(cells[ai]);
                    if(quantity.isPresent()&&price.isPresent()&&amount.isPresent()&&quantity.get().signum()>=0&&price.get().signum()>=0&&amount.get().signum()>=0){
                        var priceValue=cells[pi].contains("元")?price.get():price.get().multiply(unit);
                        var amountValue=cells[ai].contains("元")?amount.get():amount.get().multiply(unit);
                        rows.add(new Row(quantity.get(),priceValue,amountValue,new Evidence(source.fileId(),source.sha256(),source.parseResultId(),source.parseVersion(),source.textSha256(),offset,offset+line.length(),line)));
                    }else invalid=true;
                }else {invalid=true;tables.add(new Candidate(List.copyOf(rows),false,scope));active=false;}
            }
            beforePrevious=previous;previous=line;offset+=line.length()+1;
        }
        if(active)tables.add(new Candidate(List.copyOf(rows),false,scope));
        var selected=tables.stream().filter(Candidate::priceScope).findFirst().or(()->tables.stream().findFirst());
        return selected.map(t->new Table(t.rows(),tables.size()==1&&t.priceScope()&&t.complete())).orElseGet(()->new Table(List.of(),false));
    }
}
