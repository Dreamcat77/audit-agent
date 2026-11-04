package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BlacklistAlertMapper {

    void insert(BlacklistAlertPersistenceRow row);

    void insertBatch(@Param("list") List<BlacklistAlertPersistenceRow> list);

    BlacklistAlertPersistenceRow findById(@Param("id") String id);

    List<BlacklistAlertPersistenceRow> findAll(@Param("status") String status);

    List<BlacklistAlertPersistenceRow> findPage(
            @Param("batchId") String batchId,
            @Param("alertLevel") String alertLevel,
            @Param("status") String status,
            @Param("supplierName") String supplierName,
            @Param("startRow") int startRow,
            @Param("endRow") int endRow);

    long count(
            @Param("batchId") String batchId,
            @Param("alertLevel") String alertLevel,
            @Param("status") String status,
            @Param("supplierName") String supplierName);

    int countByBatchAndSupplier(
            @Param("batchId") String batchId,
            @Param("supplierName") String supplierName);

    BlacklistAlertPersistenceRow findByBatchAndSupplier(
            @Param("batchId") String batchId,
            @Param("supplierName") String supplierName);

    int insertIfAbsent(BlacklistAlertPersistenceRow row);

    int updateIfStatus(
            @Param("row") BlacklistAlertPersistenceRow row,
            @Param("expectedStatus") String expectedStatus);
}
