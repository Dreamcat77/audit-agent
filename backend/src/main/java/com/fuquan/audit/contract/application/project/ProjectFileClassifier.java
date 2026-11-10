package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.util.EnumSet;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class ProjectFileClassifier {
    public ProjectFileCategory classify(String normalizedPath) {
        String[] segments = normalizedPath.replace('\\', '/').toLowerCase(Locale.ROOT).split("/");
        ProjectFileCategory filename = classifyFilename(segments[segments.length - 1]);
        boolean genericAttachment = filename == ProjectFileCategory.SUPPLEMENT
                && !contains(segments[segments.length - 1], "补充", "澄清", "变更协议", "supplement");
        if (filename != ProjectFileCategory.UNKNOWN && !genericAttachment) return filename;
        for (int index = segments.length - 2; index >= 0; index--) {
            EnumSet<ProjectFileCategory> matches = parentMatches(segments[index]);
            if (matches.size() == 1) return matches.iterator().next();
            if (matches.size() > 1) return ProjectFileCategory.UNKNOWN;
        }
        return filename;
    }

    private static EnumSet<ProjectFileCategory> parentMatches(String segment) {
        if (contains(segment, "补充", "澄清", "变更协议", "supplement", "attachment")) {
            return EnumSet.of(ProjectFileCategory.SUPPLEMENT);
        }
        EnumSet<ProjectFileCategory> matches = EnumSet.noneOf(ProjectFileCategory.class);
        addIf(matches, segment, ProjectFileCategory.SUPPLEMENT,
                "补充材料", "补充协议", "补充", "澄清", "变更协议", "附件", "supplement", "attachment", "other");
        addIf(matches, segment, ProjectFileCategory.CONTRACT_BODY,
                "合同正文", "合同", "协议", "contract", "agreement");
        addIf(matches, segment, ProjectFileCategory.AWARD_NOTICE,
                "中标通知书", "中标", "award", "winning");
        addIf(matches, segment, ProjectFileCategory.BID_RESPONSE,
                "投标文件", "响应文件", "投标", "bid", "response");
        addIf(matches, segment, ProjectFileCategory.TENDER_DOCUMENT,
                "招标文件", "采购文件", "招标", "tender", "procurement");
        return matches;
    }

    private static ProjectFileCategory classifyFilename(String filename) {
        if(isAuxiliary(filename))return ProjectFileCategory.SUPPLEMENT;
        if (contains(filename, "中标通知书", "成交通知书", "中标", "成交", "award", "winning")) {
            return ProjectFileCategory.AWARD_NOTICE;
        }
        if (contains(filename, "投标文件", "响应文件", "投标", "响应", "bid", "response")) {
            return ProjectFileCategory.BID_RESPONSE;
        }
        if (contains(filename, "招标文件", "采购文件", "采购公告", "招标公告", "招标", "tender", "procurement")) {
            return ProjectFileCategory.TENDER_DOCUMENT;
        }
        if (contains(filename, "补充材料", "补充协议", "补充", "澄清", "变更协议", "附件", "supplement", "attachment", "other")) {
            return ProjectFileCategory.SUPPLEMENT;
        }
        if (contains(filename, "采购合同", "合同", "协议", "contract", "agreement")) {
            return ProjectFileCategory.CONTRACT_BODY;
        }
        return ProjectFileCategory.UNKNOWN;
    }
    public static boolean isAuxiliary(String name){return contains(name,"廉洁协议","廉政协议","保密协议","廉洁、保密","定密公开审批","督申总队回复单","领导签字工作单");}

    private static void addIf(EnumSet<ProjectFileCategory> matches, String value,
            ProjectFileCategory category, String... tokens) {
        if (contains(value, tokens)) matches.add(category);
    }

    private static boolean contains(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }
}
