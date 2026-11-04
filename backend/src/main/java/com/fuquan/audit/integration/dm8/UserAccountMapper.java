package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserAccountMapper {

    UserAccountPersistenceRow findByUsername(@Param("username") String normalizedUsername);

    UserAccountPersistenceRow findById(@Param("id") String id);

    UserAccountPersistenceRow findFirstActiveByRole(@Param("roleCode") String roleCode);

    List<String> findRoleCodesByUserId(@Param("userId") String userId);

    boolean existsActiveRole(@Param("roleCode") String roleCode);

    boolean existsUserRole(@Param("userId") String userId, @Param("roleCode") String roleCode);

    int insertUserRole(@Param("userId") String userId, @Param("roleCode") String roleCode);
    int deleteUserRole(@Param("userId") String userId, @Param("roleCode") String roleCode);

    int incrementFailedLogin(
            @Param("id") String id,
            @Param("maxFailedAttempts") int maxFailedAttempts,
            @Param("lockUntil") LocalDateTime lockUntil,
            @Param("occurredAt") LocalDateTime occurredAt);

    int recordSuccessfulLogin(
            @Param("id") String id,
            @Param("occurredAt") LocalDateTime occurredAt);

    int unlockIfExpired(
            @Param("id") String id,
            @Param("occurredAt") LocalDateTime occurredAt);
}
