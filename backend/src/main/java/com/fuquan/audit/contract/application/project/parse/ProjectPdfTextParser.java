package com.fuquan.audit.contract.application.project.parse;

import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class ProjectPdfTextParser implements ProjectDocumentParser {
    @Override public boolean supports(ProjectFile file) {
        return file.originalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    @Override public ParsedProjectDocument parse(ProjectFile file, InputStream input) {
        try (PDDocument document = PDDocument.load(input)) {
            List<ParsedProjectDocument.Segment> segments = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                segments.add(new ParsedProjectDocument.Segment("pdf/page:" + page,
                        stripper.getText(document).strip(), page, null));
            }
            return new ParsedProjectDocument(segments);
        } catch (IOException exception) {
            throw new ProjectParseException("PDF_PARSE_FAILED", exception);
        }
    }
}
