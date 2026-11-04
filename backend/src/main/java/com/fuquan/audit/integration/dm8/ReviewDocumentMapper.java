package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReviewDocumentMapper {
    int insert(ReviewDocumentPersistenceRow row);
    ReviewDocumentPersistenceRow findById(@Param("id") String id);
    ReviewDocumentPersistenceRow findBySessionAndId(
            @Param("sessionId") String sessionId, @Param("id") String id);
    List<ReviewDocumentPersistenceRow> findBySessionId(@Param("sessionId") String sessionId);
    List<ReviewDocumentPersistenceRow> findPendingBySessionId(@Param("sessionId") String sessionId);
    int markAvailable(
            @Param("id") String id,
            @Param("expectedVersion") long expectedVersion,
            @Param("storedAt") LocalDateTime storedAt);
    int markFailed(
            @Param("id") String id,
            @Param("expectedVersion") long expectedVersion,
            @Param("failedAt") LocalDateTime failedAt);
}
