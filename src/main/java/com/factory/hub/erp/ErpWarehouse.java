package com.factory.hub.erp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 仓库（ERP 基础资料之二，ROADMAP DH-43 前置）。
 * 参照 jshERP 的 depot 自研重写（改名纪律：depot → erp_warehouse）。
 * 支持多仓；isDefault 标默认仓（出入库单据不指定仓库时落这里）。
 */
@Entity
@Table(name = "erp_warehouse", indexes = {
        @Index(name = "idx_erp_wh_code", columnList = "code", unique = true)
})
public class ErpWarehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "manager", length = 64)
    private String manager;

    @Column(name = "phone", length = 32)
    private String phone;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "is_default", nullable = false)
    private boolean defaultWarehouse = false;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at", nullable = false)
    private long updatedAt = System.currentTimeMillis();

    @PreUpdate
    void touch() {
        updatedAt = System.currentTimeMillis();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getManager() { return manager; }
    public void setManager(String manager) { this.manager = manager; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public boolean isDefaultWarehouse() { return defaultWarehouse; }
    public void setDefaultWarehouse(boolean defaultWarehouse) { this.defaultWarehouse = defaultWarehouse; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
}
