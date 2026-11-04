package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProcurementReviewReportMapper {

    void insert(ProcurementReviewReportPersistenceRow row);

    ProcurementReviewReportPersistenceRow findLatestByProjectId(String projectId);
}
