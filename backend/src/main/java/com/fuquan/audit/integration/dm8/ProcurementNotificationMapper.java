package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcurementNotificationMapper {
    int insert(ProcurementNotificationPersistenceRow row);
    List<ProcurementNotificationPersistenceRow> findByRecipient(@Param("userId") String userId,
                                                                 @Param("unreadOnly") boolean unreadOnly);
    int markRead(@Param("id") String id, @Param("userId") String userId, @Param("readTime") LocalDateTime readTime);
}
