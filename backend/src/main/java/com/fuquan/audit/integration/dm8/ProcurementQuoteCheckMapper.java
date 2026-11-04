package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** F06 询比价分析 DM8 Mapper。 */
@Mapper
public interface ProcurementQuoteCheckMapper {

    void insertCheck(ProcurementQuoteCheckPersistenceRow row);

    void insertItems(@Param("list") List<ProcurementQuoteCheckItemPersistenceRow> rows);

    ProcurementQuoteCheckPersistenceRow findLatestByBatchId(@Param("batchId") String batchId);

    ProcurementQuoteCheckPersistenceRow findById(@Param("id") String id);

    void insertComparison(@Param("id") String id, @Param("json") String json);

    String findComparison(@Param("id") String id);

    List<ProcurementQuoteCheckItemPersistenceRow> findItemsByCheckId(@Param("checkId") String checkId);

    List<ProcurementSupplierQuoteTimelinePersistenceRow> findLatestSupplierQuotesBySubject(
            @Param("subjectCode") String subjectCode,
            @Param("fiscalYearStart") java.time.LocalDateTime fiscalYearStart,
            @Param("fiscalYearEnd") java.time.LocalDateTime fiscalYearEnd);
}
