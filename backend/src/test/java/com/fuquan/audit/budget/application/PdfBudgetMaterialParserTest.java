package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.budget.infra.pdf.PdfBoxBudgetMaterialParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

class PdfBudgetMaterialParserTest {

    @Test
    void parsesTextPdfRowsWithAmountAtEnd() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            try (PDPageContentStream content = new PDPageContentStream(document, document.getPage(0))) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(72, 700);
                content.showText("Office equipment 1,500.00");
                content.endText();
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                document.save(output);
                pdf = output.toByteArray();
            }
        }

        List<BudgetMaterialLine> lines = new PdfBoxBudgetMaterialParser().parse(
                new ByteArrayInputStream(pdf), "budget.pdf");

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).subjectName()).isEqualTo("Office equipment");
        assertThat(lines.get(0).declaredAmount()).isEqualByComparingTo("1500.00");
    }
}
