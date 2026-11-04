package com.fuquan.audit.blacklist.application.port;

import com.fuquan.audit.blacklist.domain.BlacklistMatchResult;
import java.util.List;

/**
 * F03 黑名单 — 命中检查 Port。
 */
public interface BlacklistCheckPort {

    /**
     * 检查供应商是否命中黑名单。
     *
     * @param procurementId 采购编号（可为 null，手动查询场景）
     * @param supplierName  供应商名称
     * @return 匹配结果列表（可能包含多条命中）
     */
    default List<BlacklistMatchResult> check(String procurementId, String supplierName) {
        return check(new CheckCommand(
                procurementId != null ? procurementId : "MANUAL",
                null, procurementId, supplierName, null)).matches();
    }

    CheckOutcome check(CheckCommand command);

    /**
     * Checks one logical batch. Implementations may override this to reuse a
     * consistent blacklist/rule snapshot instead of querying once per row.
     */
    default List<CheckOutcome> checkBatch(List<CheckCommand> commands) {
        return commands.stream().map(this::check).toList();
    }

    record CheckCommand(
            String batchId,
            Integer rowNumber,
            String procurementId,
            String supplierName,
            String supplierCode) {}

    record CheckOutcome(
            String batchId,
            String supplierName,
            String supplierCode,
            boolean matched,
            List<BlacklistMatchResult> matches,
            com.fuquan.audit.blacklist.domain.BlacklistAlert persistedAlert) {}
}
