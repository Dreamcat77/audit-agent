package com.fuquan.audit.contract.application.project.parse;

import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ProjectDocumentParserTest {

    @Test void realDocxPhysicalRowKeepsOneFactGroupAcrossCellAndBatchBoundaries() throws Exception {
        byte[] bytes;
        try(var document=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            for(int n=0;n<31;n++)document.createParagraph().createRun().setText("Preface "+n);
            var table=document.createTable(2,4);
            table.getRow(0).getCell(0).setText("ROW-A "+"x".repeat(500));
            var continuation=table.getRow(0).getCell(0).getParagraphs().get(0).createRun();
            continuation.addBreak();continuation.setText("CONTINUED CELL");
            table.getRow(0).getCell(1).setText("2");table.getRow(0).getCell(2).setText("20.00");table.getRow(0).getCell(3).setText("40.00");
            table.getRow(1).getCell(0).setText("ROW-B");table.getRow(1).getCell(1).setText("3");
            table.getRow(1).getCell(2).setText("10.00");table.getRow(1).getCell(3).setText("30.00");
            document.write(output);bytes=output.toByteArray();
        }
        var defaults=ProjectIntakeProperties.defaults();
        var parser=new ProjectDocxTextParser(new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(defaults),defaults));
        var parsed=parser.parse(file("rows.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document"),new ByteArrayInputStream(bytes));
        String canonical=parsed.fullText();int start=canonical.indexOf("ROW-A"),end=canonical.indexOf("ROW-B");
        var segments=new com.fuquan.audit.contract.application.project.factai.ContractFactSegmenter().segment(UUID.randomUUID(),canonical);
        var rowA=segments.stream().filter(segment->segment.startOffset()>=start&&segment.endOffset()<=end).toList();
        assertThat(rowA).hasSizeGreaterThan(2);
        assertThat(rowA.stream().map(segment->segment.rowIdentity()).distinct()).hasSize(1);
        assertThat(segments.indexOf(rowA.get(0))).isEqualTo(31);
        assertThat(segments.indexOf(rowA.get(rowA.size()-1))).isGreaterThanOrEqualTo(32);
        assertThat(rowA.get(0).rowIdentity()).isNotEqualTo(segments.get(segments.size()-1).rowIdentity());
        assertThat(canonical).contains("CONTINUED CELL").contains("\t2\t20.00\t40.00");
        assertThat(parsed.tables().get(0).rows().get(0).cells()).hasSize(4);
        assertThat(parsed.tables().get(0).rows().get(0).cells().get(0).text()).contains("\nCONTINUED CELL");
    }

    @Test void docxParagraphsAndTablesRemainInOriginalBodyOrder() throws Exception {
        byte[] bytes;
        try(var document=new XWPFDocument()) {
            document.createParagraph().createRun().setText("BEFORE TABLE");
            document.createTable(1,1).getRow(0).getCell(0).setText("TABLE ROW");
            document.createParagraph().createRun().setText("AFTER TABLE");
            var out=new ByteArrayOutputStream();document.write(out);bytes=out.toByteArray();
        }
        var defaults=ProjectIntakeProperties.defaults();
        var parser=new ProjectDocxTextParser(new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(defaults),defaults));
        var parsed=parser.parse(file("ordered.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                new ByteArrayInputStream(bytes));
        assertThat(parsed.segments()).extracting(ParsedProjectDocument.Segment::text)
                .containsExactly("BEFORE TABLE","TABLE ROW","AFTER TABLE");
        assertThat(parsed.tables()).singleElement().satisfies(table->assertThat(table.rows()).hasSize(1));
    }

    @Test
    void pdfAndDocxProduceDeterministicSegments() throws Exception {
        ProjectPdfTextParser pdf = new ProjectPdfTextParser();
        ParsedProjectDocument pdfResult = pdf.parse(file("contract.pdf", "application/pdf"),
                new ByteArrayInputStream(pdf("Contract No C-2026-1")));
        assertThat(pdfResult.segments()).extracting(ParsedProjectDocument.Segment::sourceLocator)
                .containsExactly("pdf/page:1");

        ProjectIntakeProperties defaults = ProjectIntakeProperties.defaults();
        ProjectDocxTextParser docx = new ProjectDocxTextParser(
                new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(defaults), defaults));
        ParsedProjectDocument docxResult = docx.parse(file("contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                new ByteArrayInputStream(docx("Contract No C-2026-1")));
        assertThat(docxResult.segments()).extracting(ParsedProjectDocument.Segment::sourceLocator)
                .containsExactly("docx/body/paragraph:1");
    }

    @Test
    void docxTablePreservesTypedRowsAndCellLocatorsWithoutDuplicatingFlatText() throws Exception {
        ProjectIntakeProperties defaults = ProjectIntakeProperties.defaults();
        ProjectDocxTextParser parser = new ProjectDocxTextParser(
                new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(defaults), defaults));

        ParsedProjectDocument parsed = parser.parse(file("contract.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                new ByteArrayInputStream(lineItemDocx()));

        assertThat(parsed.tables()).singleElement().satisfies(table -> {
            assertThat(table.sourceLocator()).isEqualTo("docx/table:1");
            assertThat(table.rows()).hasSize(4);
            assertThat(table.rows().get(0).sourceLocator()).isEqualTo("docx/table:1/row:1");
            assertThat(table.rows().get(0).cells())
                    .extracting(ParsedProjectDocument.Cell::text)
                    .containsExactly("标识", "货物/服务名称", "数量", "单价（元）", "申报金额（元）");
            assertThat(table.rows().get(1).cells())
                    .extracting(ParsedProjectDocument.Cell::sourceLocator)
                    .containsExactly(
                            "docx/table:1/row:2/cell:1",
                            "docx/table:1/row:2/cell:2",
                            "docx/table:1/row:2/cell:3",
                            "docx/table:1/row:2/cell:4",
                            "docx/table:1/row:2/cell:5");
        });
        assertThat(parsed.segments()).hasSize(4);
        assertThat(occurrences(parsed.fullText(), "SOFT-01")).isEqualTo(1);
        assertThat(occurrences(parsed.fullText(), "智慧维修系统软件许可")).isEqualTo(1);
    }

    @Test
    void directDocxUsesOuterUploadLimitWhileInternalEntriesKeepArchiveLimits() throws Exception {
        byte[] direct = docx("valid direct document");
        ProjectIntakeProperties directLimits = new ProjectIntakeProperties(DataSize.ofBytes(direct.length),
                DataSize.ofBytes(direct.length - 1L), DataSize.ofMegabytes(2), 20, 5, 100, 200);
        ProjectDocxContainerGuard directGuard = new ProjectDocxContainerGuard(
                new SelectedProjectArchiveReader(directLimits), directLimits);

        assertThat(directGuard.validate(new ByteArrayInputStream(direct))).isEqualTo(direct);

        ProjectIntakeProperties outerTooSmall = new ProjectIntakeProperties(
                DataSize.ofBytes(direct.length - 1L), DataSize.ofBytes(direct.length * 2L),
                DataSize.ofMegabytes(2), 20, 5, 100, 200);
        ProjectDocxContainerGuard outerGuard = new ProjectDocxContainerGuard(
                new SelectedProjectArchiveReader(outerTooSmall), outerTooSmall);
        assertThatThrownBy(() -> outerGuard.validate(new ByteArrayInputStream(direct)))
                .isInstanceOf(ProjectParseException.class)
                .hasMessageContaining("DOCX_SIZE_LIMIT");

        byte[] oversizedInternalEntry = docxContainer("x".repeat(64));
        ProjectIntakeProperties internalLimits = new ProjectIntakeProperties(DataSize.ofMegabytes(1),
                DataSize.ofBytes(32), DataSize.ofKilobytes(2), 20, 5, 100, 200);
        ProjectDocxContainerGuard internalGuard = new ProjectDocxContainerGuard(
                new SelectedProjectArchiveReader(internalLimits), internalLimits);
        assertThatThrownBy(() -> internalGuard.validate(new ByteArrayInputStream(oversizedInternalEntry)))
                .isInstanceOf(ProjectParseException.class)
                .hasMessageContaining("DOCX_ENTRY_SIZE_LIMIT");
    }

    public static ProjectFile file(String name, String mediaType) {
        String hash = "a".repeat(64);
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        return new ProjectFile(UUID.randomUUID(), UUID.randomUUID(), null, null, name, mediaType,
                ProjectFileCategory.CONTRACT_BODY, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PENDING, hash, hash, 1024, null, null, now, now);
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(40, 700); content.showText(text); content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(); document.save(out); return out.toByteArray();
        }
    }

    private static byte[] docx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(text);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); document.write(out); return out.toByteArray();
        }
    }

    private static byte[] lineItemDocx() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFTable table = document.createTable(4, 5);
            String[][] values = {
                    {"标识", "货物/服务名称", "数量", "单价（元）", "申报金额（元）"},
                    {"SOFT-01", "智慧维修系统软件许可", "1", "40000.00", "40000.00"},
                    {"SERV-01", "部署实施与数据初始化", "1", "17000.00", "17000.00"},
                    {"TRAIN-01", "培训与接口对接服务", "1", "10000.00", "10000.00"}
            };
            for (int row = 0; row < values.length; row++) {
                for (int cell = 0; cell < values[row].length; cell++) {
                    table.getRow(row).getCell(cell).setText(values[row][cell]);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    private static int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static byte[] docxContainer(String documentXml) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
