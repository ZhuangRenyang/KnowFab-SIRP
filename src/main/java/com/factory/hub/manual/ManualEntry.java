package com.factory.hub.manual;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * 数据补录（DH-13）。断网、抄表漏了、新设备接入前那几天，客户要手工补。
 * <p>
 * <b>为什么单独建表，不直接往采集表里塞行：</b>
 * 采集表是「设备真的报上来的数」，补录是「人填的数」。混在一起以后，
 * 客户问「这个月产量为什么比上月高」，你没法一眼分清是干得多还是补得多；
 * 撤销补录也要从几万行里挑出来删。分开存，报表里单列一行「其中补录」，
 * 撤销就是改一个标志位，采集表一个字节都不动。
 * <p>
 * 一条记录 = 一台设备 × 一个自然日 × （电量 和/或 产量）。
 * 时段跨多天时由服务层拆成每天一条，保证月报按天汇总时不会漏也不会重。
 */
@Entity
@Table(name = "manual_entry",
        indexes = {
                @Index(name = "idx_manual_device_day", columnList = "device_code,day"),
                @Index(name = "idx_manual_day", columnList = "day")
        })
public class ManualEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一次批量补多天时共用；撤销可以按条撤，也可以按批查 */
    @Column(name = "batch_id", length = 32)
    private String batchId;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    /** 设备名快照：设备改名/删除后，补录历史仍然能看懂 */
    @Column(name = "device_name", length = 128)
    private String deviceName;

    /** 归属日（自然日）。只补一天就是当天 */
    @Column(name = "day", nullable = false)
    private LocalDate day;

    /** 补录电量（kWh），可以为 0（只补产量时） */
    @Column(name = "kwh")
    private double kwh;

    /**
     * 补录电量对应的电费。按当天 24 小时的分时电价摊算后存快照——
     * 以后客户改电价，历史补录的电费不跟着变（和能耗表的处理一致）。
     */
    @Column(name = "kwh_cost")
    private double kwhCost;

    /** 补录产量（件），可以为 0（只补电量时） */
    @Column(name = "output")
    private double output;

    /** 补录原因，审计要求必填 */
    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "operator", length = 64)
    private String operator;

    /** 录入时间（epoch ms） */
    @Column(name = "created_at")
    private long createdAt;

    /** 撤销后不进统计，但记录保留可查 */
    @Column(name = "revoked")
    private boolean revoked;

    @Column(name = "revoked_by", length = 64)
    private String revokedBy;

    @Column(name = "revoked_at")
    private Long revokedAt;

    public ManualEntry() {
    }

    public ManualEntry(String batchId, String deviceCode, String deviceName, LocalDate day,
                       double kwh, double kwhCost, double output, String reason,
                       String operator, long createdAt) {
        this.batchId = batchId;
        this.deviceCode = deviceCode;
        this.deviceName = deviceName;
        this.day = day;
        this.kwh = kwh;
        this.kwhCost = kwhCost;
        this.output = output;
        this.reason = reason;
        this.operator = operator;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public double getKwh() {
        return kwh;
    }

    public void setKwh(double kwh) {
        this.kwh = kwh;
    }

    public double getKwhCost() {
        return kwhCost;
    }

    public void setKwhCost(double kwhCost) {
        this.kwhCost = kwhCost;
    }

    public double getOutput() {
        return output;
    }

    public void setOutput(double output) {
        this.output = output;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(String revokedBy) {
        this.revokedBy = revokedBy;
    }

    public Long getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Long revokedAt) {
        this.revokedAt = revokedAt;
    }
}
