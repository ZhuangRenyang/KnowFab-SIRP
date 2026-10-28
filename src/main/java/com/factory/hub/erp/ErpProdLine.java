package com.factory.hub.erp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 产线（ROADMAP DH-40/41）。
 * <p>
 * 客户口径原话："每个机器单独计数 N 件，最后一台机器计数为一条产线的成品数量，
 * 可单独设置一台机器为成品数量件"。所以产线本身<b>不存产量</b>——
 * 产量永远等于「成品计数设备」的计数器增量，设备自己的计数一分不动。
 * <p>
 * 为什么不在产线上存一个累计数：存了就有两份账，设备断线补记、换班清零、
 * 客户改成品计数点之后，两份账必然对不上；不存就只有一份事实（prod_hourly）。
 * <p>
 * 成品计数点规则：
 * <ol>
 *   <li>{@link #finishedDeviceCode} 有值且这台设备确实在这条线上 → 用它；</li>
 *   <li>没有指定 → 取工位序号（device.line_seq）最大的一台，即"最后一台机器"；</li>
 *   <li>线上没有设备 → 没有产量，界面显示"--"，不要显示 0 骗人。</li>
 * </ol>
 */
@Entity
@Table(name = "erp_prod_line", indexes = {
        @Index(name = "uk_prod_line_code", columnList = "code", unique = true),
        @Index(name = "idx_prod_line_sort", columnList = "sort_order")
})
public class ErpProdLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 所属车间/工段，大屏和报表按它分组 */
    @Column(name = "workshop", length = 64)
    private String workshop;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /**
     * 成品计数设备编码；null = 自动取工位序号最大的一台。
     * 存编码而不是 id，是因为设备换台（旧机报废换新机、编码沿用）时不用改配置。
     */
    @Column(name = "finished_device_code", length = 64)
    private String finishedDeviceCode;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "remark")
    private String remark;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at", nullable = false)
    private long updatedAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getWorkshop() { return workshop; }
    public void setWorkshop(String workshop) { this.workshop = workshop; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getFinishedDeviceCode() { return finishedDeviceCode; }
    public void setFinishedDeviceCode(String finishedDeviceCode) { this.finishedDeviceCode = finishedDeviceCode; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
