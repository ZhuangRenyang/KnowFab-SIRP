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
        @Index(name = "idx_erp_wh_code", columnList = "code", unique = true),
        @Index(name = "idx_erp_wh_kind", columnList = "kind")
})
public class ErpWarehouse {

    /** 正常仓：货可以直接出，销售可用量算它 */
    public static final String KIND_STANDARD = "STANDARD";
    /**
     * 禁发仓：仓里的货<b>默认不可出库</b>，放行必须有依据（QC-SPEC §2.11 fail-safe 判定）。
     * <p>
     * <b>一个值装两类货</b>：让步待发的货 + 待判定的客户退货。因为在"能不能发"这件事上
     * 它们是同一件事——都不能直接发。所以别再叫"特采仓/待处理仓"当类型名，
     * 类型叫禁发，具体仓名由客户自己起（默认建一个叫「待处理仓」的）。
     * <p>
     * <b>为什么必须有这个列，而不是按仓名/编码判断</b>：客户会改仓库名、改编码。
     * 靠 {@code name like '%特采%'} 认仓，改个名字硬拦就静默失效，而且失效时不报错——
     * 这是"两份口径各写一遍"的典型死法。
     * <p>
     * <b>误设的后果不对称</b>：误设成 BLOCKED → 那个仓发不出货（当场就有人喊，能发现）；
     * 误设成 STANDARD → 放行了不该放的货（事后对账才发现）。所以迁移脚本里
     * 按仓名推断时偏向 BLOCKED，并打日志让人确认。
     */
    public static final String KIND_BLOCKED = "BLOCKED";

    public static final java.util.List<String> KINDS = java.util.List.of(KIND_STANDARD, KIND_BLOCKED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 仓类型 STANDARD / BLOCKED；空值按 STANDARD 处理（升级上来的老行由 1.7.0 回填） */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind = KIND_STANDARD;

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
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    /**
     * 是不是禁发仓。
     * <p>
     * 判空按 STANDARD（升级上来的老行 kind 由 1.7.0 回填，但迁移被关掉的环境也要能跑）；
     * 未知值同样按 STANDARD——<b>这里刻意不按 BLOCKED 兜底</b>：如果因为脏值把正常仓
     * 判成禁发，全厂发不出货且没人知道为什么。真正的安全兜底在出库判定那一侧
     * （禁发仓才查依据），不在这里。
     */
    public boolean isBlocked() {
        return KIND_BLOCKED.equals(kind);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
}
