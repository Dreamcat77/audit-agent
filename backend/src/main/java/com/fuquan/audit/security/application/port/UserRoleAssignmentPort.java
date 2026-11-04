package com.fuquan.audit.security.application.port;

import java.util.List;

/** 用户与系统角色授权关系的持久化边界。 */
public interface UserRoleAssignmentPort {

    /**
     * 判断角色是否存在且处于启用状态。
     *
     * @param roleCode 稳定角色编码
     * @return 角色可分配时返回 {@code true}
     */
    boolean isActiveRole(String roleCode);

    /**
     * 判断用户当前是否已经拥有角色。
     *
     * @param userId 用户标识
     * @param roleCode 角色编码
     * @return 已存在时返回 {@code true}
     */
    boolean hasRole(String userId, String roleCode);

    /**
     * 创建用户角色关联。该操作必须保持幂等，重复授权不应产生第二条关系。
     *
     * @param userId 用户标识
     * @param roleCode 角色编码
     */
    void assignRole(String userId, String roleCode);

    void removeRole(String userId, String roleCode);

    /**
     * 查询用户当前启用角色编码。
     *
     * @param userId 用户标识
     * @return 排序后的角色编码
     */
    List<String> findActiveRoleCodesByUserId(String userId);
}
