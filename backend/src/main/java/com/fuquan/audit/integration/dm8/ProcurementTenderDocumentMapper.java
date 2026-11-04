package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcurementTenderDocumentMapper {
    void insert(ProcurementTenderDocumentPersistenceRow row);
    int update(ProcurementTenderDocumentPersistenceRow row);
    ProcurementTenderDocumentPersistenceRow findById(@Param("id") String id);
    ProcurementTenderDocumentPersistenceRow findLatestByProjectId(@Param("projectId") String projectId);
    java.util.List<ProcurementTenderDocumentPersistenceRow> findByProjectId(@Param("projectId") String projectId);
}
