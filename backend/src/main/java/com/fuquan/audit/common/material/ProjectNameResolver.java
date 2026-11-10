package com.fuquan.audit.common.material;

import java.util.List;
import java.util.regex.Pattern;

/** Display naming only: a generated name is never evidence for cross-project matching. */
public final class ProjectNameResolver {
    private ProjectNameResolver() {}
    private static final Pattern NAME = Pattern.compile("(?m)(?:^|[；;|\\t])\\h*(?:采购项目名称|建设项目名称|项目名称|工程名称)\\h*[:：]\\h*(?:\\r?\\n\\h*)?([^\\r\\n；;|]{2,160})");
    private static final Pattern DOCUMENT_TITLE = Pattern.compile("(?i).*(?:合同书|合同|协议书|协议|招标文件|投标文件|通知书|审批表|申请表|需求表|需求标准|评标报告|报价单|预算表|申报表|批复表|\\.(?:pdf|docx?|xlsx?|pptx?|txt|png|jpe?g))$");
    public static String resolve(List<String> extracted, List<String> texts, String fallback) {
        for (String value : extracted) if (isProjectName(value)) return clean(value);
        for (String text : texts) {
            var match = NAME.matcher(text);
            while (match.find()) if (isProjectName(match.group(1))) return clean(match.group(1));
        }
        for (String text : texts) for (String line : text.lines().limit(12).toList()) {
            String title = line.strip();
            if (title.length() >= 6 && title.length() <= 80
                    && title.matches("[^：:。；;，,]+(?:项目|工程)") && isProjectName(title))
            {
                return clean(title);
            }
        }
        return fallback;
    }
    public static boolean isProjectName(String value) {
        if (value == null) return false;
        String name = clean(value);
        String title = name.replaceAll("\\s+", "");
        for(int i=0;i<3;i++)title=title
                .replaceFirst("(?i)\\.(?:pdf|docx?|xlsx?|pptx?|txt|png|jpe?g)$", "")
                .replaceAll("^[《〈“\"']+|[》〉”\"']+$", "")
                .replaceFirst("[（(][^()（）]{0,40}[）)]$", "")
                .replaceFirst("(?i)[._—-]*(?:V[0-9]+(?:\\.[0-9]+)*|(?:最终|终稿|盖章|签章|签字|签署|修订|定稿|扫描|电子)(?:版|件|稿|本)?)[._—-]*$", "");
        return name.length() >= 2 && !DOCUMENT_TITLE.matcher(title).matches()
                && !name.matches("(?:项目编号|采购编号|合同编号|申请部门|采购人|预算金额|合同金额)\\s*[:：].*")
                && !name.matches("项目负责人|项目经理|人员岗位")
                && !name.matches("(?i)未识别|未提供|不详|未知|暂无|无|null|unknown|项目名称|合同名称|政府采购|采购项目|工程项目|项目|本项目|该项目|上述项目|采购需求");
    }
    private static String clean(String value) {
        String result = value.replaceAll("[\\u200b\\ufeff]", "").strip()
                .replaceFirst("^(?:采购项目名称|建设项目名称|项目名称|工程名称)\\s*[:：]\\s*", "")
                .split("[\\t|]|\\s+(?=(?:项目编号|采购编号|预算金额|预算年度|采购人|申请部门)\\s*[:：])", 2)[0].strip();
        return result.substring(0, Math.min(result.length(), 160));
    }
}
