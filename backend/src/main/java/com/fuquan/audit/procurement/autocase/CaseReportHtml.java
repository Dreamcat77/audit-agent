package com.fuquan.audit.procurement.autocase;

import java.util.*;

/** Portable report from persisted outcomes; exporting performs no extraction or model calls. */
final class CaseReportHtml {
    private CaseReportHtml() {}
    static String render(AuditCase value) {
        var html=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"UTF-8\"><title>采购审查报告</title><style>body{max-width:1000px;margin:36px auto;padding:0 24px;font-family:Arial,'Microsoft YaHei',sans-serif;color:#243247;line-height:1.8}section{border:1px solid #dce2ea;border-radius:8px;padding:20px;margin:20px 0}h1{font-size:25px}h2{font-size:19px}h3{font-size:16px}.risk{border-left:4px solid #bc3939;background:#fff6f5;padding:12px;margin:12px 0}blockquote{white-space:pre-wrap;font-size:13px;background:#f7f8fa;padding:12px}table{width:100%;border-collapse:collapse}td,th{padding:8px;border:1px solid #ddd;text-align:left}@media print{section{break-inside:avoid}}</style><h1>采购材料审查报告</h1>");
        html.append("<p>项目：").append(esc(CaseFactPolicy.projectName(value))).append("</p><p>处理时间：").append(esc(value.createdAt())).append("</p>");
        var report=value.report();
        if(report==null)return html.append("<p>材料正在处理，尚未生成审查结果。</p></html>").toString();
        html.append("<section><h2>项目金额及报价</h2>");
        if(report.budget()!=null)html.append("<p>预算金额：").append(esc(report.budget())).append(" 元</p>");
        if(report.ceilingAmount()!=null)html.append("<p>最高限价：").append(esc(report.ceilingAmount())).append(" 元</p>");
        if(report.awardAmount()!=null)html.append("<p>中标金额：").append(esc(report.awardAmount())).append(" 元</p>");
        html.append("<table><tr><th>供应商</th><th>报价（元）</th><th>核对结果</th><th>来源材料</th></tr>");
        for(var offer:report.offers()) {
            var file=value.documents().stream().filter(d->d.id().equals(offer.documentId())).findFirst().orElseThrow();
            html.append("<tr><td>").append(esc(offer.supplier())).append("</td><td>").append(esc(offer.amount())).append("</td><td>")
                    .append(report.blockedSuppliers().contains(offer.supplier())?"黑名单命中，暂停推荐；":"")
                    .append(report.budget()==null?"材料不足，未比较预算":offer.amount().compareTo(report.budget())>0?"超过材料预算":"未超过材料预算")
                    .append(report.ceilingAmount()==null?"":offer.amount().compareTo(report.ceilingAmount())>0?"；超过最高限价":"；未超过最高限价")
                    .append("</td><td>").append(esc(file.name())).append("</td></tr>");
        }
        html.append("</table></section>");
        for(var offer:report.offers())if(!offer.lines().isEmpty()) {
            html.append("<section><h2>").append(esc(offer.supplier())).append(" · 报价明细</h2><table><tr><th>品目</th><th>数量</th><th>单价（元）</th><th>金额（元）</th></tr>");
            for(var line:offer.lines())html.append("<tr><td>").append(esc(line.item())).append("</td><td>").append(esc(line.quantity())).append("</td><td>").append(esc(line.unitPrice())).append("</td><td>").append(esc(line.amount())).append("</td></tr>");
            html.append("</table><p>").append(offer.lineValidation().equals("VERIFIED")?"明细计算与总价一致。":offer.lineValidation().equals("CONFLICT")?"明细计算需要核对。":"材料不足，未核对完整明细。").append("</p></section>");
        }
        if(report.calculation()!=null)html.append("<section><h2>报价统计参考</h2><p>平均报价：").append(esc(report.calculation().averageQuote()))
                .append(" 元。</p><p>").append(report.calculation().highlyConcentrated()?"报价较集中，可关注报价独立性；统计线索不构成围串标认定。":"按当前统计参考线未见报价高度集中。").append("</p></section>");
        for(var entry:new TreeMap<>(report.reviews()).entrySet()) {
            html.append("<section><h2>").append(esc(switch(entry.getKey()) {case "BLACKLIST"->"供应商黑名单比对";case "TENDER_COMPLIANCE"->"招标文件合规性审查";default->"跨材料核对";})).append("</h2>");
            for(var finding:entry.getValue().findings()) {
                html.append("<div class=\"risk\"><h3>").append(esc(finding.title())).append("</h3><p>").append(esc(CaseReviewText.explanation(new AuditCase.ReviewCheck(finding.code(),"CONFLICT",finding.explanation(),finding.evidence())))).append("</p><p>建议：").append(esc(finding.suggestion())).append("</p>");
                for(var source:finding.evidence())html.append("<p>涉及材料：").append(esc(source.fileName())).append("</p><blockquote>").append(esc(source.excerpt())).append("</blockquote>");
                html.append("</div>");
            }
            for(var check:entry.getValue().checks())if(!check.code().equals("MATERIAL_COVERAGE"))html.append("<p>").append(esc(CaseReviewText.explanation(check))).append("</p>");
            if(entry.getValue().checks().isEmpty()&&entry.getValue().findings().isEmpty())html.append("<p>材料不足，未生成</p>");
            html.append("</section>");
        }
        html.append("<section><h2>材料清单</h2><ul>");
        value.documents().forEach(d->html.append("<li>").append(esc(d.name())).append("</li>"));
        html.append("</ul></section><section><h2>核对范围说明</h2>");
        report.notices().forEach(n->html.append("<p>").append(esc(n)).append("</p>"));
        return html.append("<p>本报告为上传材料范围内的审查参考，统计线索不构成围串标认定。</p></section></html>").toString();
    }
    private static String esc(Object value) {return Objects.toString(value,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
