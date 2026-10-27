package com.factory.hub.energy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 能耗汇总，粒度是「一台设备 × 一个小时 × 一个电价时段」。
 * <p>
 * 为什么不存每秒的原始数据：1 秒 1 条，500 台设备一年 157 亿条，
 * H2 会被撑死，MySQL 也要上千万的机器。而客户真正会看的是
 * 「今天多少度、这个月多少钱、哪台机器最费电」——小时级完全够。
 * <p>
 * 单价在写入时就存成快照。以后客户改电价，历史账单不会跟着变，
 * 这对账很重要。
 * <p>
 * 数据量：一台设备一年 8760 行（24 小时 × 365 天），500 台约 438 万行/年。
 * 这个量级 H2 带得动，但查询会变慢。上到 2000 台以上就该换 MySQL，
 * 或者把这批汇总数据迁到 TDengine。见 {@link EnergyRollup} 的清理逻辑。
 */
@Entity
@Table(name = "energy_hourly",
        indexes = {
                @Index(name = "idx_energy_code_hour", columnList = "device_code,hour_start"),
                @Index(name = "idx_energy_hour", columnList = "hour_start")
        })
public class EnergyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    /** 整点时间戳（epoch ms），如 2026-10-26 14:00:00 */
    @Column(name = "hour_start", nullable = false)
    private long hourStart;

    /** TIP / PEAK / FLAT / VALLEY */
    @Column(name = "seg_type", nullable = false, length = 16)
    private String segType;

    /** 该小时该时段用了多少度电 */
    @Column(name = "kwh")
    private double kwh;

    /** 写入时的电价快照，元/度 */
    @Column(name = "price")
    private double price;

    /** 电费 = kwh × price */
    @Column(name = "cost")
    private double cost;

    /** 其中有多少度是设备断线后补记的，对账时用来解释异常 */
    @Column(name = "backfill_kwh")
    private double backfillKwh;

    public EnergyRecord() {
    }

    public EnergyRecord(String deviceCode, long hourStart, String segType,
                        double kwh, double price, double backfillKwh) {
        this.deviceCode = deviceCode;
        this.hourStart = hourStart;
        this.segType = segType;
        this.kwh = kwh;
        this.price = price;
        this.cost = kwh * price;
        this.backfillKwh = backfillKwh;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public long getHourStart() {
        return hourStart;
    }

    public void setHourStart(long hourStart) {
        this.hourStart = hourStart;
    }

    public String getSegType() {
        return segType;
    }

    public void setSegType(String segType) {
        this.segType = segType;
    }

    public double getKwh() {
        return kwh;
    }

    public void setKwh(double kwh) {
        this.kwh = kwh;
        this.cost = kwh * price;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
        this.cost = kwh * price;
    }

    public double getCost() {
        return cost;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }

    public double getBackfillKwh() {
        return backfillKwh;
    }

    public void setBackfillKwh(double backfillKwh) {
        this.backfillKwh = backfillKwh;
    }
}
