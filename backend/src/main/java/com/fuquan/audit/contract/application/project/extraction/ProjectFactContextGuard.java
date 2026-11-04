package com.fuquan.audit.contract.application.project.extraction;

import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class ProjectFactContextGuard {
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "^第[一二三四五六七八九十百0-9]+章\\s*\\S.*$");
    private static final Pattern EXPLICIT_SECTION_HEADING = Pattern.compile(
            "^(?:参考合同|合同参考文本|合同模板|合同范本|示范文本|正式合同|评标办法|采购需求|项目概况|投标邀请)$");
    private static final Pattern TOC_PAGE_SUFFIX = Pattern.compile(".*\\s+[0-9]+\\s*$");

    private final ProjectFactExtractionMode mode;
    private Section section = Section.CURRENT;
    private boolean excludedSectionSeen;

    ProjectFactContextGuard(ProjectFactExtractionMode mode) {
        this.mode = mode;
    }

    /** Same document-wide exclusion policy for native source-table and legacy extraction paths. */
    public static boolean potentialContractTablesAllowed(ParsedProjectDocument document) {
        var context = new ProjectFactContextGuard(ProjectFactExtractionMode.POTENTIAL_CONTRACT);
        Map<String, ParsedProjectDocument.Row> nativeRows = new HashMap<>();
        document.tables().forEach(table -> table.rows().forEach(row -> nativeRows.put(row.sourceLocator(), row)));
        for (var segment : document.segments()) {
            var row = nativeRows.get(segment.sourceLocator());
            if (row != null) {
                var populated = row.cells().stream().filter(cell -> !cell.text().isBlank()).toList();
                // Native structure distinguishes a real banner (including empty padding cells)
                // from a normal multi-column item whose name happens to contain "模板".
                if (populated.size() == 1) context.observeHeading(populated.get(0).text().strip());
            }
            context.accessFor(segment);
        }
        return context.potentialContractTableAllowed();
    }

    SegmentAccess accessFor(ParsedProjectDocument.Segment segment) {
        if (isTableCell(segment)) {
            return new SegmentAccess(false, true, false);
        }
        String text = segment.text().strip();
        if (observeHeading(text)) return new SegmentAccess(false, false, false);
        boolean currentSection = section == Section.CURRENT;
        boolean potentialContract = mode == ProjectFactExtractionMode.POTENTIAL_CONTRACT && currentSection;
        return new SegmentAccess(potentialContract, currentSection, potentialContract);
    }

    /** One shared heading policy, whether its native source is a paragraph or a table banner. */
    private boolean observeHeading(String text) {
        if (isStandaloneHeading(text)) {
            if (!TOC_PAGE_SUFFIX.matcher(text).matches()) {
                section = sectionFor(text);
                excludedSectionSeen |= section != Section.CURRENT;
            }
            return true;
        }
        return false;
    }

    boolean potentialContractTableAllowed() {
        return mode == ProjectFactExtractionMode.POTENTIAL_CONTRACT
                && section == Section.CURRENT && !excludedSectionSeen;
    }

    private static boolean isTableCell(ParsedProjectDocument.Segment segment) {
        return segment.sourceLocator().startsWith("docx/table:");
    }

    private static boolean isStandaloneHeading(String text) {
        return !text.contains("\n") && !text.contains("\r")
                && (CHAPTER_HEADING.matcher(text).matches()
                || EXPLICIT_SECTION_HEADING.matcher(text).matches()
                || markedTemplateHeading(text));
    }

    private static boolean markedTemplateHeading(String text) {
        return text.length() <= 100 && !text.contains(":") && !text.contains("：")
                && (text.contains("仅供参考") || text.contains("参考合同")
                || text.contains("模板") || text.contains("范本") || text.contains("示范文本"));
    }

    private static Section sectionFor(String heading) {
        if (heading.contains("仅供参考") || heading.contains("参考合同") || heading.contains("合同参考文本")) {
            return Section.REFERENCE_TEMPLATE;
        }
        if (heading.contains("模板") || heading.contains("范本") || heading.contains("示范文本")) {
            return Section.FORM_TEMPLATE;
        }
        return Section.CURRENT;
    }

    private enum Section {
        CURRENT,
        REFERENCE_TEMPLATE,
        FORM_TEMPLATE
    }

    record SegmentAccess(boolean formalScalarAllowed, boolean projectMaterialCandidateAllowed,
                         boolean potentialContractAllowed) {
    }
}
