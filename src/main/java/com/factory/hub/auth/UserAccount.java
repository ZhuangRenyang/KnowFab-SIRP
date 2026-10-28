package com.factory.hub.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 系统用户。
 * <p>
 * 三级角色对应车间里的真实岗位，不是硬凑的：
 * <ul>
 *   <li><b>ADMIN</b> 管理员——设备、电价、班次、用户、升级，全都能动。通常是你或客户的信息主管</li>
 *   <li><b>MANAGER</b> 车间主任——能改设备和报警规则、能确认告警，但碰不到电价和系统升级</li>
 *   <li><b>LEADER</b> 班组长——只能看和确认告警。让一线能用，但改不了配置</li>
 *   <li><b>SALES</b> 销售——只能查「销售查询」里的库存与可承诺量，看不到成本、设备与车间数据</li>
 * </ul>
 * 密码只存 BCrypt 哈希。这个库会被客户 DBA 备份走，明文密码等于送人。
 */
@Entity
@Table(name = "sys_user", indexes = {
        @Index(name = "idx_user_name", columnList = "username", unique = true)
})
public class UserAccount {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_LEADER = "LEADER";
    public static final String ROLE_SALES = "SALES";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 64, unique = true)
    private String username;

    @Column(name = "display_name", length = 64)
    private String displayName;

    /** BCrypt 哈希，长度固定 60，留 100 给以后换算法 */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "role", nullable = false, length = 16)
    private String role = ROLE_LEADER;

    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at")
    private long createdAt = System.currentTimeMillis();

    @Column(name = "last_login_at")
    private long lastLoginAt;

    public UserAccount() {
    }

    public UserAccount(String username, String displayName, String passwordHash, String role) {
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public boolean isEnabledNow() {
        return enabled == null || enabled;
    }

    /** 角色中文名，页面直接显示 */
    public String roleText() {
        return switch (role == null ? "" : role) {
            case ROLE_ADMIN -> "管理员";
            case ROLE_MANAGER -> "车间主任";
            case ROLE_LEADER -> "班组长";
            case ROLE_SALES -> "销售";
            default -> role;
        };
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(long lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}
