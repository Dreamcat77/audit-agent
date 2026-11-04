package com.fuquan.audit.contract.application.project.extraction;

import com.fuquan.audit.contract.application.project.ExtractionKeyFactory;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

import static com.fuquan.audit.contract.application.project.parse.ProjectDocumentParserTest.file;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectFactContextGuardTest {
    @Test
    void standaloneTemplateHeadingsAreBoundedAndTocLikeLinesDoNotChangeBodyState() {
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                segment(1, "第六章 政府采购合同（仅供参考）"),
                segment(2, "甲方：参考甲方"),
                segment(3, "第七章 投标文件格式范本"),
                segment(4, "乙方：表单乙方"),
                segment(5, "第八章 正式合同"),
                segment(6, "乙方：正式乙方"),
                segment(7, "第六章 政府采购合同（仅供参考） 118"),
                segment(8, "甲方：目录后甲方")));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(file("contract.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_A"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("目录后甲方");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_B"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("正式乙方");
    }

    @Test
    void alternateStandaloneReferenceAndFormHeadingsChangeStateUntilExplicitResumeHeading() {
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                segment(1, "参考合同"),
                segment(2, "乙方：参考乙方"),
                segment(3, "正式合同"),
                segment(4, "乙方：正式乙方一"),
                segment(5, "合同模板"),
                segment(6, "甲方：模板甲方"),
                segment(7, "正式合同"),
                segment(8, "甲方：正式甲方一"),
                segment(9, "格式范本"),
                segment(10, "乙方：格式乙方"),
                segment(11, "正式合同"),
                segment(12, "甲方：正式甲方二"),
                segment(13, "投标文件格式范本"),
                segment(14, "甲方：投标格式甲方"),
                segment(15, "正式合同"),
                segment(16, "乙方：正式乙方二"),
                segment(17, "政府采购合同模板"),
                segment(18, "乙方：政府模板乙方"),
                segment(19, "第八章 正式合同"),
                segment(20, "甲方：正式甲方三"),
                segment(21, "第六章 示范文本"),
                segment(22, "甲方：示范甲方"),
                segment(23, "第七章 正式合同"),
                segment(24, "乙方：正式乙方三")));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(file("contract.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_A"))
                .extracting(ExtractionResult::normalizedValue)
                .containsExactly("正式甲方一", "正式甲方二", "正式甲方三");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_B"))
                .extracting(ExtractionResult::normalizedValue)
                .containsExactly("正式乙方一", "正式乙方二", "正式乙方三");
    }

    private static ParsedProjectDocument.Segment segment(int index, String text) {
        return new ParsedProjectDocument.Segment("docx/body/paragraph:" + index, text, null, index);
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
    }
}
