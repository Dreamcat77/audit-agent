package com.fuquan.audit.procurement.application;

import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** Conservative F10/F11 routing. Identities must be explicit source fields, never inferred from company mentions. */
public final class ProcurementMaterialClassifier {
    private ProcurementMaterialClassifier() {}
    public record Classification(String kind, String bidderName, String ipAddress, String handlerName) {}

    public static Classification classify(String fileName, String text) {
        String source = text == null ? "" : text;
        String title = source.lines().filter(s -> !s.isBlank()).limit(8).collect(java.util.stream.Collectors.joining("\n"));
        boolean tender = Pattern.compile("招标文件|采购邀请|采购公告|招标公告").matcher(fileName + "\n" + title).find();
        boolean quotation = Pattern.compile("供应商报价记录|报价单|报价函|报价表|报价文件")
                .matcher(fileName).find()
                || Pattern.compile("(?m)^.{0,80}(?:供应商报价记录|报价单|报价函|报价表|报价文件)\\h*$")
                        .matcher(title).find();
        boolean bid = !tender && (quotation || Pattern.compile("投标文件|响应文件|投标函|响应函")
                .matcher(fileName + "\n" + title).find());
        if (quotation && !tender) return new Classification("QUOTATION", null, null, null);
        if (!bid && tender) return new Classification("TENDER", null, null, null);
        if (!bid) {
            boolean knownOther = Pattern.compile("采购需求|需求申请|技术需求|实施计划|预算批复|预算回复|采购预算|"
                    + "评审办法|评审标准|澄清答复|澄清回复|澄清文件|中标通知|履约验收|验收方案|采购合同")
                    .matcher(fileName + "\n" + title).find();
            return new Classification(knownOther ? "GENERAL" : "OTHER", null, null, null);
        }
        String bidder = field(source, "投标人(?:名称)?|投标单位(?:名称)?|响应供应商(?:名称)?|供应商名称"
                + (quotation ? "|供应商|报价单位(?:名称)?|报价人(?:名称)?" : ""), 500);
        String handler = field(source, "经办人(?:姓名)?|授权代表(?:姓名)?", 200);
        String ip = field(source, "投标IP(?:地址)?|投标 IP(?: 地址)?|提交IP(?:地址)?", 64);
        if (ip != null && !ip.matches("(?:\\d{1,3}\\.){3}\\d{1,3}|[0-9a-fA-F:]{3,64}")) ip = null;
        return new Classification(bidder == null ? "BIDDER_UNCONFIRMED" : "BIDDER", bidder, ip, handler);
    }

    static String field(String text, String labels, int max) {
        // Newline/tab delimiters cover both paragraphs and POI table cells; blank template fields stay blank.
        // PDF/DOCX text extraction often flattens a labelled paragraph into one
        // line (for example "报价单位：甲公司。报价编号：..."). Stop at the
        // punctuation that starts the next labelled field so the model and the
        // deterministic fallback receive the organization name only.
        var matcher = Pattern.compile("(?m)(?:^|[\\t|；;。])\\h*(?:" + labels
                + ")\\h*[：:]\\h*([^\\r\\n\\t|；;。，,、]{1," + max + "})").matcher(text);
        var values = new LinkedHashSet<String>();
        while (matcher.find()) {
            String value = matcher.group(1).replaceAll("[（(](?:盖章|公章)[）)].*$", "").trim();
            if (!value.isBlank() && !value.matches("[_＿—\\-\\s]+") && !value.matches(".*(填写|待定|待填|公司名称|供应商名称|投标人名称).*")) values.add(value);
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }
}
