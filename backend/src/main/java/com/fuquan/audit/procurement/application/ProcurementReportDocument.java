package com.fuquan.audit.procurement.application;

import java.io.*;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

/** A downloadable document from already saved review results; never invokes AI. */
@Component
public class ProcurementReportDocument {
    public byte[] render(ProcurementReviewReportService.ReportData data)throws IOException {
        try(var doc=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            heading(doc,"采购审查参考报告",18);
            text(doc,"报告编号："+data.metadata().reportNumber());
            text(doc,"生成时间："+DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai")).format(data.metadata().generatedTime()));
            text(doc,"项目："+safe(data.project().projectName())+"（"+safe(data.project().projectCode())+"）");
            text(doc,"项目预算："+money(data.project().budgetAmount()));
            text(doc,"本报告整理已保存的审查结果，供工作参考；未提供材料或数据不足不代表没有风险，请结合原材料核实。");
            if(data.agentReview()==null||data.agentReview().items().stream().noneMatch(item->item.checkType().equals("PROJECT_STATUS")))text(doc,data.overallConclusion());
            heading(doc,"一、智能体预审",14);
            var agent=data.agentReview();
            if(agent==null)text(doc,"未生成预审结果。");
            else {
                boolean legacy=agent.items().stream().anyMatch(item->item.checkType().equals("PROJECT_STATUS"));
                if(!legacy){text(doc,agent.aiSummary());text(doc,agent.suggestion());}
                for(var item:agent.items())if(!item.checkType().equals("PROJECT_STATUS")) {
                    text(doc,item.checkName()+"："+risk(item.riskLevel()));text(doc,item.detail());
                    if(item.expectedValue()!=null)text(doc,"参考值："+item.expectedValue());
                    if(item.actualValue()!=null)text(doc,"材料值："+item.actualValue());
                    text(doc,item.suggestion());text(doc,item.aiExplanation());text(doc,item.aiSuggestion());
                }
            }
            heading(doc,"二、询比价及报价异常",14);
            var quote=data.quoteCheck();
            if(quote==null)text(doc,"未提供可用报价结果，本部分未完成判断。");
            else {
                text(doc,"预算："+money(quote.budgetAmount())+"；平均报价："+money(quote.averageQuote()));
                text(doc,"报价离散系数："+quote.coefficientOfVariation().toPlainString()+"；"+(quote.highlyConcentrated()?"存在报价趋同线索，建议核实。":"请结合各供应商规格和报价范围核对。"));
                for(var q:quote.quotes()) {
                    text(doc,safe(q.supplierName())+"："+money(q.quoteAmount())+(q.overBudget()?"，超预算 "+money(q.overBudgetAmount()):""));
                    text(doc,q.productDescription());text(doc,q.suspiciousReason());
                }
                if(quote.comparison()!=null) {
                    // Keep all comparison evidence in a human-readable paragraph sequence.
                    var comparison=quote.comparison();
                    text(doc,"可比预算上限："+money(comparison.effectiveCeiling()));
                    for(String condition:comparison.conditions())text(doc,"比价条件："+condition);
                    for(var item:comparison.items()) {
                        text(doc,safe(item.itemName())+"："+safe(item.specification())+"；数量 "+item.quantity()+" "+safe(item.unit()));
                        for(var offer:item.offers())text(doc,safe(offer.supplierName())+"：单价 "+money(offer.unitPrice())+"；总价 "+money(offer.totalPrice()));
                    }
                    if(!comparison.recommendedSuppliers().isEmpty())text(doc,"有条件参考供应商："+String.join("、",comparison.recommendedSuppliers())+"；参考金额："+money(comparison.recommendedAmount()));
                    if(!comparison.excludedSuppliers().isEmpty())text(doc,"因命中黑名单而未参与推荐："+String.join("、",comparison.excludedSuppliers()));
                    for(String note:comparison.notes())text(doc,note);
                }
            }
            heading(doc,"三、供应商黑名单比对",14);
            if(data.blacklistAlerts().isEmpty())text(doc,quote==null?"未提供可用供应商比对结果，不能据此判断未命中黑名单。":"已保存的比对结果未记录黑名单命中，请结合黑名单库更新情况核实。");
            for(var hit:data.blacklistAlerts())text(doc,safe(hit.supplierName())+"："+risk(hit.alertLevel())+"；"+safe(hit.blacklistReason()));
            heading(doc,"四、招标文件合规审查",14);
            if(data.tenderFindings().isEmpty())text(doc,"已保存结果没有风险明细；未上传招标材料或分析未完成时，不代表已通过合规审查。");
            for(var f:data.tenderFindings()) {
                text(doc,safe(f.riskCategory())+" · "+risk(f.riskLevel()));text(doc,"涉及内容："+safe(f.matchedText()));
                text(doc,"来源位置："+safe(f.sourceLocator()));text(doc,"核对意见："+safe(f.riskReason()));
                text(doc,"整改建议："+safe(f.remediationSuggestion()));if(f.legalBasis()!=null)text(doc,"依据："+f.legalBasis());
            }
            heading(doc,"五、围串标辅助识别",14);
            var collusion=data.collusionAssessment();
            if(collusion==null)text(doc,"未形成围串标评分，不能据此排除风险。");
            else {
                text(doc,"风险评分："+collusion.riskScore()+" 分；"+risk(collusion.riskLevel()));
                text(doc,"重复 IP："+collusion.duplicateIpGroups()+" 组；重复经办人："+collusion.duplicateHandlerGroups()+" 组。");
                for(var pair:collusion.similarities())text(doc,safe(pair.bidderA())+" 与 "+safe(pair.bidderB())+"：文本相似度 "+pair.similarity().multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString()+"%");
            }
            heading(doc,"六、拆分采购辅助识别",14);
            var split=data.splitPurchaseAssessment();
            if(split==null)text(doc,"未形成拆分采购分析结果。");
            else {
                text(doc,"风险评分："+split.riskScore()+" 分；"+risk(split.riskLevel()));
                if(split.findings().isEmpty())text(doc,"按本次采用规则未记录累计金额风险，项目关联及适用制度仍需人工核实。");
                for(var f:split.findings()) {
                    text(doc,safe(f.supplierName())+"：累计 "+money(f.totalAmount())+"；"+safe(f.thresholdName())+" "+money(f.thresholdAmount())+"；"+risk(f.riskLevel()));
                    for(var r:f.records())text(doc,safe(r.projectName())+"（"+safe(r.projectCode())+"）："+money(r.quoteAmount())+"；记录时间 "+r.quoteTime());
                }
            }
            doc.write(output);return output.toByteArray();
        }
    }
    private static String safe(String s){return s==null||s.isBlank()?"未提供":s;}
    private static String money(BigDecimal n){return n==null?"未提供":n.toPlainString()+" 元";}
    private static String risk(String s){return "CRITICAL".equals(s)?"严重风险":"WARNING".equals(s)?"一般风险":"INFO".equals(s)?"未发现明确异常":"需结合材料核对";}
    private static void heading(XWPFDocument d,String text,int size){var r=d.createParagraph().createRun();r.setBold(true);r.setFontFamily("宋体");r.setFontSize(size);r.setText(text);}
    private static void text(XWPFDocument d,String value){if(value==null||value.isBlank())return;for(String line:value.split("\\R",-1)){var p=d.createParagraph();p.setSpacingAfter(100);var r=p.createRun();r.setFontFamily("宋体");r.setFontSize(11);r.setText(line);}}
}
