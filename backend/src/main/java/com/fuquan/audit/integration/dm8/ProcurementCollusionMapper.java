package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcurementCollusionMapper {
    void insertDocument(ProcurementCollusionDocumentPersistenceRow row);
    List<ProcurementCollusionDocumentPersistenceRow> findDocuments(@Param("projectId") String projectId);
    void mergeAssessment(ProcurementCollusionAssessmentPersistenceRow row);
    ProcurementCollusionAssessmentPersistenceRow findAssessment(@Param("projectId") String projectId);

    void mergeSplitAssessment(ProcurementSplitPurchaseAssessmentPersistenceRow row);

    ProcurementSplitPurchaseAssessmentPersistenceRow findSplitAssessment(@Param("projectId") String projectId);
}
