package com.fuquan.audit.security.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定 local profile 可选的首账号初始化配置。
 *
 * <p>只接受外部环境提供的 Spring Security delegating password Hash，不承载或生成原始密码；该配置不用于 dev29/customer 正式账号管理。</p>
 */
@ConfigurationProperties("audit.security.local-account")
public class LocalAccountProperties {

    private String id;
    private String username;
    private String passwordHash;
    private String realName;
    private List<String> roles = new ArrayList<>();
    private List<ApprovalUserProperties> approvalUsers = new ArrayList<>();

    /**
     * 返回 local 首账号的稳定用户标识。
     *
     * @return 用户标识
     */
    public String getId() {
        return id;
    }

    /**
     * 供配置绑定设置 local 首账号用户标识。
     *
     * @param id 用户标识
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * 返回 local 首账号用户名。
     *
     * @return 用户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * 供配置绑定设置 local 首账号用户名。
     *
     * @param username 用户名
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * 返回外部注入的 delegating password Hash。
     *
     * @return 密码 Hash，未配置时可为空
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * 供配置绑定设置已离线生成的密码 Hash。
     *
     * @param passwordHash Spring Security delegating password Hash，禁止传入原始密码
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * 返回 local 首账号显示名称。
     *
     * @return 用户显示名称
     */
    public String getRealName() {
        return realName;
    }

    /**
     * 供配置绑定设置 local 首账号显示名称。
     *
     * @param realName 用户显示名称
     */
    public void setRealName(String realName) {
        this.realName = realName;
    }

    /**
     * 返回 local 首账号角色列表。
     *
     * @return 不带 {@code ROLE_} 前缀的业务角色
     */
    public List<String> getRoles() {
        return roles;
    }

    /**
     * 供配置绑定设置 local 首账号角色。
     *
     * @param roles 不带 {@code ROLE_} 前缀的业务角色列表
     */
    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    /** 返回 local 受控审批测试账号配置。 */
    public List<ApprovalUserProperties> getApprovalUsers() {
        return approvalUsers;
    }

    /** 设置 local 受控审批测试账号配置。 */
    public void setApprovalUsers(List<ApprovalUserProperties> approvalUsers) {
        this.approvalUsers = approvalUsers;
    }

    /** 一个 local 审批测试账号的外部配置快照，不承载明文密码。 */
    public static class ApprovalUserProperties {
        private String id;
        private String username;
        private String passwordHash;
        private String realName;
        private List<String> roles = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public String getRealName() {
            return realName;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }
}
