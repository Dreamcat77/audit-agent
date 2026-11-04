package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ProjectSessionBindingMapper {
    int insert(ProjectSessionBindingPersistenceRow row);
    List<ProjectSessionBindingPersistenceRow> findBySessionId(@Param("sessionId") String sessionId);
    int deactivateBySessionId(@Param("sessionId") String sessionId);
}
