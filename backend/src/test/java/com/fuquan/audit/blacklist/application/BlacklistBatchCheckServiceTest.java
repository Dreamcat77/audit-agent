package com.fuquan.audit.blacklist.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.blacklist.application.port.BlacklistBatchSpreadsheetPort;
import com.fuquan.audit.blacklist.application.port.BlacklistCheckPort;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.blacklist.domain.BlacklistMatchResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlacklistBatchCheckServiceTest {

    @Test
    void checksEveryRowAndSeparatesHitsFromMisses() {
        UUID blacklistId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();
        BlacklistCheckPort checkPort = command -> {
            if ("命中供应商".equals(command.supplierName())) {
                BlacklistMatchResult match = new BlacklistMatchResult(
                        command.supplierName(), command.supplierCode(), "命中供应商",
                        "EXACT", 100, true, blacklistId, "本单位黑名单", "严重违规", "CRITICAL");
                BlacklistAlert alert = new BlacklistAlert(
                        alertId, command.batchId(), command.rowNumber(), command.procurementId(),
                        command.supplierName(), command.supplierCode(), blacklistId,
                        "命中供应商", "本单位黑名单", "严重违规", "EXACT", 100,
                        "CRITICAL", "PENDING", null, null, null,
                        java.time.Instant.now());
                return new BlacklistCheckPort.CheckOutcome(
                        command.batchId(), command.supplierName(), command.supplierCode(),
                        true, List.of(match), alert);
            }
            return new BlacklistCheckPort.CheckOutcome(
                    command.batchId(), command.supplierName(), command.supplierCode(),
                    false, List.of(), null);
        };
        BlacklistBatchCheckService service = new BlacklistBatchCheckService(
                checkPort,
                input -> List.of(new BlacklistBatchSpreadsheetPort.SupplierRow(
                        2, "unused", null, null)));

        BlacklistBatchCheckService.BatchResult result = service.check("BATCH-1", List.of(
                new BlacklistBatchCheckService.SupplierInput(1, "命中供应商", "CODE-1", "P-1"),
                new BlacklistBatchCheckService.SupplierInput(2, "正常供应商", null, "P-2")));

        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.hitCount()).isEqualTo(1);
        assertThat(result.missCount()).isEqualTo(1);
        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.rowNumber()).isEqualTo(1);
            assertThat(hit.blacklistId()).isEqualTo(blacklistId);
            assertThat(hit.alertId()).isEqualTo(alertId);
        });
        assertThat(result.misses()).singleElement().satisfies(miss -> {
            assertThat(miss.rowNumber()).isEqualTo(2);
            assertThat(miss.supplierName()).isEqualTo("正常供应商");
        });
    }

    @Test
    void uploadPathDelegatesSpreadsheetRowsToBatchCheck() throws Exception {
        BlacklistBatchCheckService service = new BlacklistBatchCheckService(
                command -> new BlacklistCheckPort.CheckOutcome(
                        command.batchId(), command.supplierName(), command.supplierCode(),
                        false, List.of(), null),
                input -> List.of(new BlacklistBatchSpreadsheetPort.SupplierRow(
                        2, "某供应商", "CODE-1", "P-1")));

        BlacklistBatchCheckService.BatchResult result = service.checkUpload(
                "BATCH-2", new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)));

        assertThat(result.batchId()).isEqualTo("BATCH-2");
        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.misses()).singleElement().satisfies(miss -> {
            assertThat(miss.rowNumber()).isEqualTo(2);
            assertThat(miss.supplierCode()).isEqualTo("CODE-1");
        });
    }
}
