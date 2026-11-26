package com.fuquan.audit.contract.application.analysis;

import java.math.BigDecimal;
import java.util.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** A complete scoped amount table needs no invented quantity or unit price. */
final class ContractAmountOnlyTable {
    private ContractAmountOnlyTable() {}
    private record Candidate(List<ContractAnalysisTable.Row> rows,boolean complete,boolean scoped) {}
    static ContractAnalysisTable.Table parse(Source source,String text) {
        var candidates=new ArrayList<Candidate>();var rows=new ArrayList<ContractAnalysisTable.Row>();
        int amountColumn=-1,nameColumn=-1,offset=0;boolean active=false,invalid=false,scoped=false,whole=false;
        String previous="",beforePrevious="";BigDecimal unit=BigDecimal.ONE;
        for(String line:text.split("\n",-1)) {
            String[] cells=line.contains("\t")?line.split("\t",-1):line.split("\\|",-1);
            int amount=-1,name=-1;boolean multiplicands=false;
            for(int i=0;i<cells.length;i++) {
                String c=ContractAnalysisEvidence.compact(cells[i]);
                if(c.matches("(?:分项金额|行金额|金额|小计|价税合计)(?:[（(].*[)）])?"))amount=i;
                if(c.matches("(?:分项名称|项目名称|品目名称|费用名称|服务事项|服务内容|名称)"))name=i;
                if(c.matches("(?:数量|工作量|(?:含税)?单价)(?:[（(].*[)）])?"))multiplicands=true;
            }
            if(amount>=0&&name>=0&&amount!=name&&!multiplicands) {
                if(active)candidates.add(new Candidate(List.copyOf(rows),false,scoped));
                String context=beforePrevious+"\n"+previous+"\n"+line;
                scoped=context.matches("(?s).*合同(?:价款|价格|金额|标的|分项).{0,12}(?:明细|清单|表).*" );
                scoped&=!ContractAnalysisSemantics.reference(text,offset)
                        &&!context.matches("(?s).*(?:历史|业绩|模板|示例|仅供参考|成本测算).*" );
                whole=whole(context);active=true;invalid=false;amountColumn=amount;nameColumn=name;rows=new ArrayList<>();
                unit=cells[amount].contains("万元")?new BigDecimal("10000"):BigDecimal.ONE;
            } else if(active&&!line.isBlank()) {
                if(cells.length>Math.max(amountColumn,nameColumn)) {
                    String label=ContractAnalysisEvidence.compact(cells[nameColumn]);
                    boolean total=label.matches("(?:总计|合计|含税合计|价税合计)[：:]?")
                            ||label.isBlank()&&Arrays.stream(cells).limit(amountColumn).map(ContractAnalysisEvidence::compact)
                                .filter(v->!v.isBlank()).findFirst().orElse("").matches("(?:总计|合计|含税合计|价税合计)[：:]?");
                    if(total) {
                        boolean validTotal=ContractAnalysisRules.money(cells[amountColumn].strip()).isPresent();
                        int end=offset+line.length();
                        // Some spreadsheets merge the total caption into the
                        // index column and put the completeness declaration in
                        // the immediately following footer. It must explicitly
                        // refer to this contract, not another table or project.
                        String after=text.substring(Math.min(text.length(),end));
                        var footer=java.util.regex.Pattern.compile("^\\s*(以上(?:为|是)(?:本|该)合同(?:的)?(?:全部|完整)(?:价款|价格|金额|分项)(?:明细|清单|表)[^\\r\\n]{0,140})").matcher(after);
                        boolean footerComplete=footer.find()&&whole(footer.group(1));
                        boolean complete=!invalid&&validTotal&&!rows.isEmpty()&&(whole||whole(line)||footerComplete);
                        if(complete&&footerComplete){
                            var last=rows.get(rows.size()-1);var e=last.evidence();int to=end+footer.end(1);
                            rows.set(rows.size()-1,new ContractAnalysisTable.Row(last.quantity(),last.price(),last.amount(),
                                new Evidence(e.fileId(),e.fileSha256(),e.parseResultId(),e.parseVersion(),e.textSha256(),e.start(),to,text.substring(e.start(),to))));
                        }
                        candidates.add(new Candidate(List.copyOf(rows),complete,scoped));
                        active=false;
                    } else {
                        var value=ContractAnalysisRules.money(cells[amountColumn].strip());
                        if(!label.isBlank()&&value.isPresent()&&value.get().signum()>=0) {
                            var amountValue=cells[amountColumn].matches(".*[元圆万亿].*")?value.get():value.get().multiply(unit);
                            rows.add(new ContractAnalysisTable.Row(null,null,amountValue,new Evidence(source.fileId(),source.sha256(),source.parseResultId(),source.parseVersion(),source.textSha256(),offset,offset+line.length(),line)));
                        } else invalid=true;
                    }
                } else {invalid=true;candidates.add(new Candidate(List.copyOf(rows),false,scoped));active=false;}
            }
            beforePrevious=previous;previous=line;offset+=line.length()+1;
        }
        if(active)candidates.add(new Candidate(List.copyOf(rows),false,scoped));
        var priceTables=candidates.stream().filter(Candidate::scoped).toList();
        if(priceTables.isEmpty()) {
            // A filename may identify a supporting detail file, but cannot prove full price scope.
            boolean named=com.fuquan.audit.contract.application.project.ProjectFileClassifier.isAuxiliary(source.name())
                    &&source.name().matches(".*合同(?:价款|价格|金额|标的).*" );
            return named&&!candidates.isEmpty()?new ContractAnalysisTable.Table(candidates.get(0).rows(),false)
                    :new ContractAnalysisTable.Table(List.of(),false);
        }
        var chosen=priceTables.get(0);
        return new ContractAnalysisTable.Table(chosen.rows(),priceTables.size()==1&&chosen.complete());
    }
    private static boolean whole(String text) {
        return text.matches("(?s).*(?:全部|完整|无其他分项|无其他费用).*" )
                &&!text.matches("(?s).*(?:并非全部|不是全部|不完整|局部|部分价款|不代表全部).*" );
    }
}
