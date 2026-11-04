package com.fuquan.audit.blacklist.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import com.fuquan.audit.blacklist.infra.excel.ApachePoiBlacklistSpreadsheetAdapter;
import com.fuquan.audit.blacklist.infra.memory.InMemoryBlacklistSupplierOperationLogRepository;
import com.fuquan.audit.blacklist.infra.memory.InMemoryBlacklistSupplierRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlacklistSupplierManagementServiceTest {

    private InMemoryBlacklistSupplierRepository suppliers;
    private InMemoryBlacklistSupplierOperationLogRepository logs;
    private BlacklistSupplierManagementService service;

    @BeforeEach
    void setUp() {
        suppliers = new InMemoryBlacklistSupplierRepository();
        logs = new InMemoryBlacklistSupplierOperationLogRepository();
        service = new BlacklistSupplierManagementService(
                suppliers, logs, new ApachePoiBlacklistSpreadsheetAdapter());
    }

    @Test
    void removeDeactivatesEntryAndPersistsOperatorReason() {
        BlacklistSupplier entry = BlacklistSupplier.create(
                "待移除供应商", "CODE-REMOVE", "本单位黑名单",
                "已完成整改", "内部录入", LocalDate.now(), null, "creator");
        suppliers.save(entry);
        CurrentUser operator = new CurrentUser(
                "operator-001", "operator", "张三", Set.of("SYSTEM_ADMIN"));

        BlacklistSupplierManagementService.RemovalResult result =
                service.remove(entry.id(), operator, "供应商已完成整改并通过复核");

        assertThat(result.entry().active()).isFalse();
        assertThat(suppliers.stored()).containsExactly(result.entry());
        assertThat(logs.stored()).singleElement().satisfies(log -> {
            assertThat(log.blacklistId()).isEqualTo(entry.id());
            assertThat(log.operationType()).isEqualTo("REMOVE");
            assertThat(log.operatorUserId()).isEqualTo("operator-001");
            assertThat(log.operatorName()).isEqualTo("张三");
            assertThat(log.operationReason()).isEqualTo("供应商已完成整改并通过复核");
        });
    }

    @Test
    void importKeepsLongTermValidityAndReportsInvalidRows() throws IOException {
        byte[] workbookBytes;
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("黑名单");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("供应商名称");
            header.createCell(1).setCellValue("统一社会信用代码");
            Row valid = sheet.createRow(1);
            valid.createCell(0).setCellValue("导入供应商");
            valid.createCell(1).setCellValue("IMPORT-001");
            Row invalid = sheet.createRow(2);
            invalid.createCell(1).setCellValue("IMPORT-002");
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }

        CurrentUser operator = new CurrentUser(
                "operator-002", "operator", "李四", Set.of("SYSTEM_ADMIN"));
        BlacklistSupplierManagementService.ImportResult result =
                service.importEntries(new ByteArrayInputStream(workbookBytes), operator);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).singleElement()
                .satisfies(error -> assertThat(error).contains("供应商名称不能为空"));
        assertThat(suppliers.stored()).singleElement().satisfies(entry -> {
            assertThat(entry.active()).isTrue();
            assertThat(entry.expiryDate()).isNull();
            assertThat(entry.createdBy()).isEqualTo("operator");
        });
    }
}
