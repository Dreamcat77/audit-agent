package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 提供 local profile 可重复的 F02 通过与失败合同 fixture。
 *
 * <p>这些 UUID 用于本地联调映射，不代表生产业务编号或生产数据。</p>
 */
public final class LocalContractFixtures {

    public static final UUID PASS_CONTRACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    public static final UUID FAIL_CONTRACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");

    /**
     * 禁止实例化仅提供 local fixture 的工具类。
     */
    private LocalContractFixtures() {
    }

    /**
     * 构造 local 环境的固定通过和失败合同集合。
     *
     * @return 可重复的 F02 合同 fixture 列表
     */
    public static List<Contract> contracts() {
        Instant timestamp = Instant.parse("2026-08-17T00:00:00Z");
        return List.of(
                contract(
                        PASS_CONTRACT_ID,
                        "LOCAL-PASS-001",
                        "Local passing contract",
                        "Synthetic Supplier A",
                        "91310000PASS000001",
                        "100.00",
                        "人民币壹佰元整",
                        "100.00",
                        "Synthetic Supplier A",
                        "91310000PASS000001",
                        timestamp),
                contract(
                        FAIL_CONTRACT_ID,
                        "LOCAL-FAIL-001",
                        "Local failing contract",
                        "Synthetic Supplier B",
                        "91310000FAIL000001",
                        "876000.00",
                        "人民币捌拾柒万陆仟元整",
                        "849000.00",
                        "Different Synthetic Supplier",
                        "91310000FAIL000099",
                        timestamp));
    }

    /**
     * 使用给定金额与供应商依据构造单个 local 合同聚合。
     *
     * @param id 合同 UUID
     * @param number 合同业务编号
     * @param name 合同名称
     * @param partyB 合同乙方
     * @param partyBCreditCode 乙方统一社会信用代码
     * @param amount 合同金额文本
     * @param words 合同金额中文大写
     * @param winningAmount 中标金额文本
     * @param winningBidder 中标供应商
     * @param winningCreditCode 中标供应商统一社会信用代码
     * @param timestamp 固定创建和更新时间
     * @return 包含一条明细和中标依据的合同聚合
     */
    private static Contract contract(
            UUID id,
            String number,
            String name,
            String partyB,
            String partyBCreditCode,
            String amount,
            String words,
            String winningAmount,
            String winningBidder,
            String winningCreditCode,
            Instant timestamp) {
        Money contractAmount = Money.of(new BigDecimal(amount));
        ContractLineItem item = new ContractLineItem(
                UUID.nameUUIDFromBytes((number + "-line-1").getBytes()), id, "ITEM-1", "Synthetic item",
                BigDecimal.ONE, contractAmount.amount(), contractAmount, 1);
        AwardNoticeReference award = new AwardNoticeReference(
                UUID.nameUUIDFromBytes((number + "-award").getBytes()), id, number + "-AWARD",
                name, winningBidder, winningCreditCode, Money.of(new BigDecimal(winningAmount)),
                LocalDate.of(2026, 8, 1), "local-fixture");
        return new Contract(
                id, number, name, "PROCUREMENT", "Synthetic Buyer", partyB, partyBCreditCode,
                contractAmount, words, "READY_FOR_REVIEW", 1, timestamp, timestamp, List.of(item), award);
    }
}
