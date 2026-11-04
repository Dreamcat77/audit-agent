package com.fuquan.audit.review.domain;

/**
 * 单条审查规则的评价状态：通过、失败或因依据不足未评价。
 */
public enum CheckStatus {
    PASS,
    FAIL,
    NOT_EVALUATED
}
