package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MaterialBindingMapper {
    int insert(MaterialBindingPersistenceRow row);
    MaterialBindingPersistenceRow findBySessionAndSlot(
            @Param("sessionId") String sessionId, @Param("slotKey") String slotKey);
    MaterialBindingPersistenceRow findById(@Param("id") String id);
    List<MaterialBindingPersistenceRow> findBySessionId(@Param("sessionId") String sessionId);
    int bind(
            @Param("id") String id,
            @Param("expectedDocumentId") String expectedDocumentId,
            @Param("expectedActiveFlag") int expectedActiveFlag,
            @Param("expectedVersion") long expectedVersion,
            @Param("newDocumentId") String newDocumentId,
            @Param("updatedAt") LocalDateTime updatedAt);
    int deactivate(
            @Param("id") String id,
            @Param("expectedDocumentId") String expectedDocumentId,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") LocalDateTime updatedAt);
}
