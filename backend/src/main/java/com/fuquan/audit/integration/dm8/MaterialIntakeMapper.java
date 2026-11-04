package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MaterialIntakeMapper {
    List<MaterialIntakePersistenceRow> timedOutForCleanup(@Param("limit") int limit);
    int cleaned(@Param("id") String id,@Param("reason") String reason);
    MaterialIntakePersistenceRow find(@Param("id") String id);
    MaterialIntakePersistenceRow findOperation(@Param("actorId") String actorId, @Param("key") String key);
    List<MaterialIntakePersistenceRow> eligible(@Param("keyword") String keyword, @Param("limit") int limit);
    List<MaterialIntakePersistenceRow> eligibleForProcurement(@Param("actorId") String actorId, @Param("keyword") String keyword, @Param("limit") int limit);
    List<MaterialIntakePersistenceRow> recent(@Param("actorId") String actorId, @Param("limit") int limit);
    List<MaterialIntakePersistenceRow> pending(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int renew(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);
    int insert(MaterialIntakePersistenceRow row);
    int claim(@Param("row") MaterialIntakePersistenceRow row, @Param("now") LocalDateTime now);
    int claimPending(@Param("row") MaterialIntakePersistenceRow row, @Param("now") LocalDateTime now);
    int complete(MaterialIntakePersistenceRow row);
}
