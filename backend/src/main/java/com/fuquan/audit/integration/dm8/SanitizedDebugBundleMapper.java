package com.fuquan.audit.integration.dm8;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper
public interface SanitizedDebugBundleMapper {
    int insert(@Param("row") SanitizedDebugBundlePersistenceRow row);
    SanitizedDebugBundlePersistenceRow findById(@Param("id") String id);
    List<SanitizedDebugBundlePersistenceRow> findByRunId(@Param("runId") String runId);
    int transition(@Param("id") String id,@Param("status") String status,@Param("version") long version,
            @Param("nextStatus") String nextStatus,@Param("nextVersion") long nextVersion);
}
