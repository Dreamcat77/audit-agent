package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementProject;
import java.io.IOException;

/** 采购需求 Excel 导出端口。 */
public interface ProcurementProjectSpreadsheetPort {

    byte[] exportProject(ProcurementProject project) throws IOException;
}
