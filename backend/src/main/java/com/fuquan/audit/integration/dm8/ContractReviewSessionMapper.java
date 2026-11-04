package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractReviewSessionMapper {
    int insert(ContractReviewSessionPersistenceRow row);
    ContractReviewSessionPersistenceRow findById(@Param("id") String id);
    List<ContractReviewSessionPersistenceRow> findByContractId(@Param("contractId") String contractId);
    int advancePreparingVersion(
            @Param("id") String id,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") LocalDateTime updatedAt);
    int confirmMaterials(
            @Param("id") String id,
            @Param("expectedVersion") long expectedVersion,
            @Param("confirmedAt") LocalDateTime confirmedAt);
    int softDelete(
            @Param("id") String id,
            @Param("expectedVersion") long expectedVersion,
            @Param("deletedBy") String deletedBy,
            @Param("deletedAt") LocalDateTime deletedAt);
    java.util.List<ReviewSessionTaskPersistenceRow> findTaskPage(
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("endRow") long endRow);
    long countTasks(
            @Param("status") String status,
            @Param("keyword") String keyword);
    String findCreatorDisplayName(@Param("sessionId") String sessionId);
}
