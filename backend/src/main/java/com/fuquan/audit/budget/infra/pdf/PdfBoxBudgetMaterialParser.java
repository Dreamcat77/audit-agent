package com.fuquan.audit.budget.infra.pdf;

import com.fuquan.audit.budget.application.BudgetMaterialLine;
import com.fuquan.audit.budget.application.BudgetTextNormalizer;
import com.fuquan.audit.budget.application.port.BudgetMaterialParser;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * F01 文本型 PDF 预算材料解析 Adapter。
 */
public class PdfBoxBudgetMaterialParser implements BudgetMaterialParser {

    private static final Pattern AMOUNT_AT_END =
            Pattern.compile("^(.+?)[\\t ]+([￥¥]?[-+]?\\d[\\d,，]*(?:\\.\\d+)?(?:万元?|千元?|元)?)\\s*$");

    @Override
    public boolean supports(String fileName, String contentType) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(contentType);
    }

    @Override
    public List<BudgetMaterialLine> parse(InputStream inputStream, String fileName) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream)) {
            if (document.isEncrypted()) {
                throw new IOException("PDF 已加密，无法读取文本");
            }
            String text = new PDFTextStripper().getText(document);
            List<BudgetMaterialLine> lines = new ArrayList<>();
            int rowNumber = 1;
            for (String rawLine : text.split("\\R")) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isBlank() || isHeader(line)) {
                    continue;
                }
                Matcher matcher = AMOUNT_AT_END.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                String subject = matcher.group(1).trim();
                String amountText = matcher.group(2).trim();
                String[] columns = subject.split("[\\t|,，]", -1);
                if (columns.length >= 4) {
                    lines.add(new BudgetMaterialLine(
                            rowNumber++, columns[0].trim(), columns[1].trim(), amountText,
                            BudgetTextNormalizer.amount(amountText),
                            columns[2].trim(), columns[3].trim()));
                } else if (columns.length >= 3) {
                    lines.add(new BudgetMaterialLine(
                            rowNumber++, columns[0].trim(), columns[1].trim(), amountText,
                            BudgetTextNormalizer.amount(amountText),
                            columns[2].trim(), null));
                } else {
                    lines.add(new BudgetMaterialLine(
                            rowNumber++, null, subject, amountText,
                            BudgetTextNormalizer.amount(amountText), null, null));
                }
            }
            if (lines.isEmpty()) {
                throw new IOException("PDF 未解析出文本型预算明细；扫描 PDF 暂不支持 OCR");
            }
            return lines;
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("PDF 解析失败: " + exception.getMessage(), exception);
        }
    }

    private static boolean isHeader(String line) {
        String normalized = BudgetTextNormalizer.subject(line);
        return normalized != null
                && normalized.contains("科目名称")
                && normalized.contains("金额");
    }
}
