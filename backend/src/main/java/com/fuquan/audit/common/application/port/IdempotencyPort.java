package com.fuquan.audit.common.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IdempotencyPort {

    IdempotencyClaim claim(IdempotencyRequest request);

    IdempotencyRecord markSucceeded(
            IdempotencyRecord record,
            String resultResourceType,
            String resultResourceId,
            Instant completedAt);

    IdempotencyRecord markFailed(IdempotencyRecord record, Instant completedAt);

    /** 供 uncertain COMPLETE 判定和同 Key durable recovery 使用。 */
    default Optional<IdempotencyRecord> findByScopeAndKey(String scope, String idempotencyKey) {
        return Optional.empty();
    }

    /** 供 abandoned pending recovery 从既有 Document 反查原 durable claim。 */
    default Optional<IdempotencyRecord> findByResultResource(
            String resultResourceType, String resultResourceId) {
        return Optional.empty();
    }

    /** A resource may be the result of both an inner operation and its enclosing step operation. */
    default List<IdempotencyRecord> findAllByResultResource(
            String resultResourceType, String resultResourceId) {
        throw new UnsupportedOperationException("result resource enumeration is not supported");
    }

    /**
     * local 内存 Adapter 用于模拟 START 事务回滚；真实 DM8 Adapter 依赖数据库事务回滚，
     * 因而保持默认 no-op。只能撤销调用方本次刚取得且仍为 IN_PROGRESS 的 claim。
     */
    default void rollbackUncommittedClaim(IdempotencyRecord record) {
        // Database-backed implementations roll the insert back with the surrounding transaction.
    }
}
