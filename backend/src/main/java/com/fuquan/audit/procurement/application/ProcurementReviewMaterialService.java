package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementQuoteSpreadsheetPort;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import com.fuquan.audit.common.material.application.RepeatableMaterialInput;

/** One procurement review upload entry; F06 is selected by source headers, never a required stage. */
@Service
public class ProcurementReviewMaterialService {
    private final ProcurementTenderDocumentService documents;
    private final ProcurementTenderRuleScanService scans;
    private final ProcurementQuoteSpreadsheetPort spreadsheets;
    private final ProcurementQuoteCheckService quotes;
    private final ProcurementProjectQueryService projects;
    private final Semaphore capacity = new Semaphore(2);
    private ProcurementSpreadsheetMaterialService materialSheets;

    @org.springframework.beans.factory.annotation.Autowired
    public void setMaterialSheets(ProcurementSpreadsheetMaterialService service) { this.materialSheets=service; }

    public Result analyzeSaved(UUID projectId, UUID sourceId, String operator) throws IOException {
        return materialSheets.analyze(projectId, sourceId, operator);
    }

    public ProcurementReviewMaterialService(ProcurementTenderDocumentService documents,
            ProcurementTenderRuleScanService scans, ProcurementQuoteSpreadsheetPort spreadsheets,
            ProcurementQuoteCheckService quotes, ProcurementProjectQueryService projects) {
        this.documents = documents; this.scans = scans; this.spreadsheets = spreadsheets;
        this.quotes = quotes; this.projects = projects;
    }

    // No surrounding transaction: existing durable services own their persistence boundaries.
    public Result upload(UUID projectId, String filename, String contentType, InputStream input,
            String operator) throws IOException {
        if (!capacity.tryAcquire()) throw new BusinessException(BusinessErrorCode.CONFLICT,
                "材料处理中，请稍后重试同一文件");
        try {
            projects.requireById(projectId);
            if (filename == null || filename.isBlank()) throw new BusinessException(
                    BusinessErrorCode.INVALID_ARGUMENT, "请选择采购材料");
            String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (Set.of("xlsx", "xls").contains(extension)) {
                if(materialSheets!=null) return materialSheets.upload(projectId,filename,contentType,input,operator);
                try (var source = RepeatableMaterialInput.copy(input); var previewInput=source.open()) {
                var preview = spreadsheets.preview(previewInput, 10);
                var fields = preview.mappings().stream().map(m -> m.standardField()).collect(java.util.stream.Collectors.toSet());
                if (!fields.contains("QUOTE_MATRIX") && !fields.containsAll(Set.of("SUPPLIER_NAME", "QUOTE_AMOUNT"))) {
                    return new Result("NOT_APPLICABLE", "本材料未形成可比报价，本次未执行三方比价", null, null, null);
                }
                ProcurementQuoteCheck result;
                try(var quoteInput=source.open()) { result = quotes.upload(projectId.toString(), quoteInput); }
                return new Result("QUOTE_ANALYZED", "已识别报价信息并完成辅助分析", null, null, result);
                }
            }
            if (!Set.of("docx", "pdf").contains(extension)) throw new BusinessException(
                    BusinessErrorCode.INVALID_ARGUMENT, "采购审查材料支持 DOCX、PDF、XLSX、XLS");
            var document = documents.upload(projectId, filename, contentType, input, false, operator);
            var scan = "AVAILABLE".equals(document.status()) ? scans.scan(projectId, document.id()) : null;
            boolean complete = scan != null && scan.aiStatus() == ProcurementTenderRuleScanService.AiStatus.AVAILABLE;
            return new Result(scan == null ? "PARSE_INCOMPLETE" : complete ? "DOCUMENT_REVIEWED" : "INCOMPLETE",
                    scan == null ? "材料解析未完成，可重新上传后继续" : complete ? "材料审查已完成"
                            : "规则检查结果已保存，AI 语义审查尚未完成", document, scan, null);
        } finally { capacity.release(); }
    }

    public record Result(String status, String message,
            ProcurementTenderDocumentService.TenderDocumentResult document,
            ProcurementTenderRuleScanService.ScanResult scan, ProcurementQuoteCheck quote) {}
}
