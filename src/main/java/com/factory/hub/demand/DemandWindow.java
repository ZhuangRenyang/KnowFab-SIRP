package com.factory.hub.demand;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 15 分钟需量窗口。
 * <p>
 * <b>为什么单独一张表，不在 energy_hourly 上加列：</b>
 * 基本电费按「15 分钟平均功率的最大值」收，小时数据根本算不出来。
 * 一小时内如果前 15 分钟满负荷、后 45 分钟停机，小时平均只有 1/4，
 * 而供电局按那 15 分钟收你满额需量。用小时表算需量，等于每月少算一大截，
 * 客户拿去跟电费单对不上，第一个怀疑的就是你。
 * <p>
 * 数据量：一天 96 个窗口，一台表一年 3.5 万行，比小时表多 4 倍但绝对值很小
 * （4 台表一年 14 万行）。超过 200 台表时只保留 13 个月，够做同比就行。
 */
@Entity
@Table(name = "demand_window",
        indexes = {
                @Index(name = "idx_demand_code_win", columnList = "device_code,window_start"),
                @Index(name = "idx_demand_win", columnList = "window_start")
        })
public class DemandWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    /** 窗口起点（epoch ms），对齐到整 15 分钟 */
    @Column(name = "window_start", nullable = false)
    private long windowStart;

    /** 这 15 分钟用了多少度电 */
    @Column(name = "kwh")
    private double kwh;

    /** 需量（kW）= kwh × 4。存下来，查询时不用每次都乘 */
    @Column(name = "demand_kw")
    private double demandKw;

    public DemandWindow() {
    }

    public DemandWindow(String deviceCode, long windowStart, double kwh) {
        this.deviceCode = deviceCode;
        this.windowStart = windowStart;
        this.kwh = kwh;
        this.demandKw = kwh * 4d;
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

    public long getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(long windowStart) {
        this.windowStart = windowStart;
    }

    public double getKwh() {
        return kwh;
    }

    public void setKwh(double kwh) {
        this.kwh = kwh;
        this.demandKw = kwh * 4d;
    }

    public double getDemandKw() {
        return demandKw;
    }

    public void setDemandKw(double demandKw) {
        this.demandKw = demandKw;
    }
}
