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
 * 往来单位（ERP 基础资料之三）：供应商 / 客户 / 两者皆是。
 * 参照 jshERP 的 supplier+customer 合并建模（改名纪律 → erp_partner），
 * partner_type 区分身份——一个单位既是供应商又是客户时不用建两条。
 */
@Entity
@Table(name = "erp_partner", indexes = {
        @Index(name = "idx_erp_partner_code", columnList = "code", unique = true)
})
public class ErpPartner {

    public static final String TYPE_SUPPLIER = "SUPPLIER";
    public static final String TYPE_CUSTOMER = "CUSTOMER";
    public static final String TYPE_BOTH = "BOTH";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** SUPPLIER 供应商 / CUSTOMER 客户 / BOTH 两者 */
    @Column(name = "partner_type", nullable = false, length = 16)
    private String partnerType = TYPE_CUSTOMER;

    @Column(name = "contact", length = 64)
    private String contact;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "address", length = 255)
    private String address;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

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
    public String getPartnerType() { return partnerType; }
    public void setPartnerType(String partnerType) { this.partnerType = partnerType; }
    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
}
