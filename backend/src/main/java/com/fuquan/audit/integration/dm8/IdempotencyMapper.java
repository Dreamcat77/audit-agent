package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdempotencyMapper {

    int insert(IdempotencyPersistenceRow row);

    IdempotencyPersistenceRow findByScopeAndKey(
            @Param("scope") String scope,
            @Param("idempotencyKey") String idempotencyKey);

    IdempotencyPersistenceRow findById(@Param("id") String id);

    IdempotencyPersistenceRow findByResultResource(
            @Param("resultResourceType") String resultResourceType,
            @Param("resultResourceId") String resultResourceId);

    List<IdempotencyPersistenceRow> findAllByResultResource(
            @Param("resultResourceType") String resultResourceType,
            @Param("resultResourceId") String resultResourceId);

    int markSucceeded(
            @Param("id") String id,
            @Param("expectedVersion") long expectedVersion,
            @Param("resultResourceType") String resultResourceType,
            @Param("resultResourceId") String resultResourceId,
            @Param("completedAt") LocalDateTime completedAt);

    int markFailed(
            @Param("id") String id,
            @Param("expectedVersion") long expectedVersion,
            @Param("completedAt") LocalDateTime completedAt);
}
