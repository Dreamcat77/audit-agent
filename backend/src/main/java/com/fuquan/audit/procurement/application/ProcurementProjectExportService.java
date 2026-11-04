package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementProjectSpreadsheetPort;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 采购需求 Excel 导出用例。 */
@Service
public class ProcurementProjectExportService {

    private final ProcurementProjectQueryService projectQueryService;
    private final ProcurementProjectSpreadsheetPort spreadsheetPort;

    public ProcurementProjectExportService(ProcurementProjectQueryService projectQueryService,
                                           ProcurementProjectSpreadsheetPort spreadsheetPort) {
        this.projectQueryService = projectQueryService;
        this.spreadsheetPort = spreadsheetPort;
    }

    public ExportResult export(UUID projectId) {
        ProcurementProject project = projectQueryService.requireById(projectId);
        try {
            return new ExportResult(project, spreadsheetPort.exportProject(project));
        } catch (IOException exception) {
            throw new BusinessException(BusinessErrorCode.INTERNAL_ERROR,
                    "采购需求导出失败", exception);
        }
    }

    public record ExportResult(ProcurementProject project, byte[] content) {}
}
