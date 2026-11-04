package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditTaskMapper {

    int insert(AuditTaskPersistenceRow row);

    AuditTaskPersistenceRow findById(@Param("id") String id);

    AuditTaskPersistenceRow findLatest(
            @Param("sourceType") String sourceType,
            @Param("sourceId") String sourceId,
            @Param("reviewType") String reviewType);

    List<AuditTaskPersistenceRow> findLatestPage(
            @Param("sourceType") String sourceType,
            @Param("reviewType") String reviewType,
            @Param("offset") int offset,
            @Param("endRow") int endRow);

    long countLatest(
            @Param("sourceType") String sourceType,
            @Param("reviewType") String reviewType);

    List<AuditTaskPersistenceRow> findAllLatest(
            @Param("sourceType") String sourceType,
            @Param("reviewType") String reviewType);

    List<AuditTaskPersistenceRow> findAllPage(
            @Param("sourceType") String sourceType,
            @Param("reviewType") String reviewType,
            @Param("offset") int offset,
            @Param("endRow") int endRow);

    long countAll(@Param("sourceType") String sourceType, @Param("reviewType") String reviewType);

    List<AuditTaskPersistenceRow> findAll(
            @Param("sourceType") String sourceType, @Param("reviewType") String reviewType);

    int transition(
            @Param("id") String id,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("targetStatus") String targetStatus,
            @Param("occurredAt") LocalDateTime occurredAt);
}
