package com.fuquan.audit.blacklist.application;

import com.fuquan.audit.blacklist.application.port.BlacklistBatchSpreadsheetPort;
import com.fuquan.audit.blacklist.application.port.BlacklistCheckPort;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.blacklist.domain.BlacklistMatchResult;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderMapping;
import org.springframework.stereotype.Service;

/**
 * F03 采购供应商批量黑名单检查用例。
 *
 * <p>批量编排只负责逐行调用既有命中检查 Port 和汇总结果，匹配规则与告警落库仍由
 * {@link BlacklistCheckPort} 的既有实现负责。</p>
 */
@Service
public class BlacklistBatchCheckService {

    private static final Comparator<BlacklistMatchResult> PRIMARY_MATCH =
            Comparator.comparingInt((BlacklistMatchResult item) -> levelRank(item.alertLevel()))
                    .thenComparingInt(BlacklistMatchResult::matchScore);

    private final BlacklistCheckPort blacklistCheckPort;
    private final BlacklistBatchSpreadsheetPort spreadsheetPort;

    public BlacklistBatchCheckService(
            BlacklistCheckPort blacklistCheckPort,
            BlacklistBatchSpreadsheetPort spreadsheetPort) {
        this.blacklistCheckPort = blacklistCheckPort;
        this.spreadsheetPort = spreadsheetPort;
    }

    public BatchResult check(String batchId, List<SupplierInput> suppliers) {
        List<Hit> hits = new ArrayList<>();
        List<Miss> misses = new ArrayList<>();
        List<BlacklistCheckPort.CheckCommand> commands = suppliers.stream()
                .map(supplier -> new BlacklistCheckPort.CheckCommand(
                        batchId, supplier.rowNumber(), supplier.procurementId(),
                        supplier.supplierName(), supplier.supplierCode()))
                .toList();
        List<BlacklistCheckPort.CheckOutcome> outcomes = blacklistCheckPort.checkBatch(commands);
        if (outcomes.size() != suppliers.size()) {
            throw new IllegalStateException("批量黑名单检查返回数量与输入数量不一致");
        }

        for (int index = 0; index < suppliers.size(); index++) {
            SupplierInput supplier = suppliers.get(index);
            BlacklistCheckPort.CheckOutcome outcome = outcomes.get(index);
            if (!outcome.matched()) {
                misses.add(new Miss(
                        supplier.rowNumber(), supplier.supplierName(), supplier.supplierCode()));
                continue;
            }

            BlacklistMatchResult match = outcome.matches().stream()
                    .max(PRIMARY_MATCH)
                    .orElseThrow(() -> new IllegalStateException(
                            "matched outcome must contain at least one match"));
            BlacklistAlert alert = outcome.persistedAlert();
            hits.add(new Hit(
                    supplier.rowNumber(), supplier.supplierName(), supplier.supplierCode(),
                    match.blacklistId(), match.blacklistType(), match.blacklistReason(),
                    match.matchType(), match.matchScore(), alert == null ? null : alert.id()));
        }

        return new BatchResult(batchId, suppliers.size(), hits.size(), misses.size(),
                List.copyOf(hits), List.copyOf(misses));
    }

    public BatchResult checkUpload(String batchId, InputStream inputStream) throws IOException {
        List<SupplierInput> suppliers = spreadsheetPort.readSupplierRows(inputStream).stream()
                .map(row -> new SupplierInput(
                        row.rowNumber(), row.supplierName(), row.supplierCode(), row.procurementId()))
                .toList();
        return check(batchId, suppliers);
    }

    public BatchResult checkUploadConfirmed(String batchId, InputStream inputStream,
                                            int headerRowNumber,
                                            List<SpreadsheetHeaderMapping> mappings) throws IOException {
        List<SupplierInput> suppliers = spreadsheetPort.readSupplierRows(
                inputStream, headerRowNumber, mappings).stream().map(row -> new SupplierInput(
                        row.rowNumber(), row.supplierName(), row.supplierCode(), row.procurementId())).toList();
        return check(batchId, suppliers);
    }

    private static int levelRank(String level) {
        return switch (level) {
            case "CRITICAL" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }

    public record SupplierInput(
            int rowNumber,
            String supplierName,
            String supplierCode,
            String procurementId) {
    }

    public record BatchResult(
            String batchId,
            int totalCount,
            int hitCount,
            int missCount,
            List<Hit> hits,
            List<Miss> misses) {
    }

    public record Hit(
            int rowNumber,
            String supplierName,
            String supplierCode,
            java.util.UUID blacklistId,
            String blacklistType,
            String blacklistReason,
            String matchType,
            int matchScore,
            java.util.UUID alertId) {
    }

    public record Miss(
            int rowNumber,
            String supplierName,
            String supplierCode) {
    }
}
