package com.fuquan.audit.contract.domain;

import java.util.Objects;

/**
 * 封装一次 F02 规则执行共享的合同上下文。
 *
 * @param contract 本次审查使用的不可替换合同快照
 */
public record ContractReviewContext(Contract contract) {

    /**
     * 保证规则上下文始终具有合同事实输入。
     *
     * @throws NullPointerException 合同为空时抛出
     */
    public ContractReviewContext {
        Objects.requireNonNull(contract, "contract must not be null");
    }
}
