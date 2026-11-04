package com.fuquan.audit.budget.infra.document;

import com.fuquan.audit.budget.application.BudgetMaterialLine;
import com.fuquan.audit.budget.application.BudgetTextNormalizer;
import com.fuquan.audit.budget.application.port.BudgetMaterialParser;
import com.fuquan.audit.common.material.application.OfficeMaterialTypes;
import com.fuquan.audit.common.material.application.port.DocumentTextPort;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

/** AI only proposes source-grounded budget fields. Money conversion and review remain deterministic. */
@Component
@Order(100)
public class BudgetOfficeMaterialParser implements BudgetMaterialParser {
    private final DocumentTextPort reader;
    private final MaterialFieldAiPort ai;
    public BudgetOfficeMaterialParser(DocumentTextPort reader,MaterialFieldAiPort ai){this.reader=reader;this.ai=ai;}
    @Override public boolean supports(String name,String contentType){return OfficeMaterialTypes.supports(name);}
    @Override public List<BudgetMaterialLine> parse(InputStream input,String name) throws IOException {
        var source=reader.read(input,name,UUID.randomUUID().toString(),500L*1024*1024,100,192000);
        MaterialFieldAiPort.Result result;
        try {result=ai.extract("BUDGET",UUID.randomUUID().toString(),source.sha256(),source.segments());}
        catch(RuntimeException e){throw new IOException("预算材料 AI 字段提取失败，未保存部分结果",e);}
        if(result==null||result.rows()==null||result.rows().isEmpty()||result.rows().size()>10000)throw new IOException("预算材料未提取到可核对的明细");
        var texts=source.segments().stream().collect(Collectors.toMap(MaterialFieldAiPort.Segment::locator,MaterialFieldAiPort.Segment::text));
        var lines=new ArrayList<BudgetMaterialLine>();
        Set<String> allowed=Set.of("subjectCode","subjectName","declaredAmount","approvalAmount","supplierName","projectCode","projectName","budgetYear","departmentName");
        for(var row:result.rows()) {
            if(row==null||row.facts()==null||row.facts().isEmpty())throw new IOException("预算材料行信息不完整");
            var values=new HashMap<String,String>();
            for(var fact:row.facts()) {
                String text=texts.get(fact.locator());
                if(!allowed.contains(fact.field())||text==null||fact.value()==null||fact.value().isBlank()
                    ||fact.excerpt()==null||!text.contains(fact.excerpt())||!fact.excerpt().contains(fact.value()))
                    throw new IOException("预算提取结果未通过原文依据核对");
                if(fact.field().equals("declaredAmount")&&text.matches("(?s).*(?:申报|申请)金额\\s*[:：]?.*")) {
                    var labelled=java.util.regex.Pattern.compile("(?:申报|申请)金额\\s*[:：]?\\s*"+java.util.regex.Pattern.quote(fact.value())).matcher(text);
                    if(!labelled.find())throw new IOException("预算申报金额与原文标签不一致，不能使用批复参考金额替代");
                }
                String previous=values.putIfAbsent(fact.field(),fact.value());
                if(previous!=null&&!previous.equals(fact.value()))throw new IOException("预算同一明细存在冲突字段，无法完整确认");
            }
            if(!values.containsKey("subjectName")||!values.containsKey("declaredAmount"))throw new IOException("预算明细缺少科目或申报金额，未保存部分结果");
            String amount=values.get("declaredAmount");
            var money=BudgetTextNormalizer.amount(amount);
            if(money==null)throw new IOException("预算申报金额无法准确识别");
            lines.add(new BudgetMaterialLine(lines.size()+1,values.get("subjectCode"),values.get("subjectName"),amount,money,values.get("supplierName"),values.get("projectCode")));
        }
        return List.copyOf(lines);
    }
}
