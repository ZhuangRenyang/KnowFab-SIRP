package com.factory.hub.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 生产汇总，粒度是「一台设备 × 一个小时」。
 * <p>
 * 存四样东西：各状态占了多少秒、这一个小时出了多少件。
 * 班次报表、稼动率、日产量全靠这一张表算出来。
 * <p>
 * <b>为什么按小时存，不按班次直接存：</b>
 * 客户换班次（三班倒改成两班倒、夜班从 20 点改到 21 点）是常事。
 * 按小时存，班次怎么改都能重算，历史报表不会跟着变；
 * 按班次存就死了，改一次班次等于把历史数据全废掉。
 * <p>
 * 数据量：一台设备一年 8760 行，500 台约 438 万行/年，跟能耗表一个量级。
 * 超过 2000 台要换 MySQL 或迁时序库。
 */
@Entity
@Table(name = "prod_hourly",
        indexes = {
                @Index(name = "idx_prod_code_hour", columnList = "device_code,hour_start"),
                @Index(name = "idx_prod_hour", columnList = "hour_start")
        })
public class ProdHourly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    /** 设备名快照。设备改名以后，历史报表还是当时的名字，对账时不会串 */
    @Column(name = "device_name", length = 128)
    private String deviceName;

    /** 整点时间戳（epoch ms） */
    @Column(name = "hour_start", nullable = false)
    private long hourStart;

    /** 运行时长（秒） */
    @Column(name = "run_sec")
    private double runSec;

    /** 待机时长（秒），通电但没干活 */
    @Column(name = "idle_sec")
    private double idleSec;

    /** 停机时长（秒） */
    @Column(name = "stop_sec")
    private double stopSec;

    /** 报警中时长（秒）。停机报警两回事，分开记 */
    @Column(name = "alarm_sec")
    private double alarmSec;

    /** 状态点读不到值的时长（秒）。这个数大说明点表配错了或设备掉线 */
    @Column(name = "unknown_sec")
    private double unknownSec;

    /** 这一小时的产量（累计计数器增量） */
    @Column(name = "output")
    private double output;

    /** 其中有多少件是断线重连后一次性补记进来的，跟能耗的 backfill 一个道理 */
    @Column(name = "backfill")
    private double backfill;

    public ProdHourly() {
    }

    public ProdHourly(String deviceCode, String deviceName, long hourStart) {
        this.deviceCode = deviceCode;
        this.deviceName = deviceName;
        this.hourStart = hourStart;
    }

    /** 这一段有多少秒被记录下来了，用来判断数据完整度 */
    public double countedSec() {
        return runSec + idleSec + stopSec + alarmSec + unknownSec;
    }

    /** 稼动率 = 运行 / 已记录时长。数据不足 60 秒时返回 -1，让前端显示「--」而不是 0% */
    public double availability() {
        double total = countedSec();
        if (total < 60) {
            return -1;
        }
        return runSec / total;
    }

    public void addStatus(int status, double seconds) {
        switch (status) {
            case 0 -> stopSec += seconds;
            case 1 -> idleSec += seconds;
            case 2 -> runSec += seconds;
            case 3 -> alarmSec += seconds;
            default -> unknownSec += seconds;
        }
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

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public long getHourStart() {
        return hourStart;
    }

    public void setHourStart(long hourStart) {
        this.hourStart = hourStart;
    }

    public double getRunSec() {
        return runSec;
    }

    public void setRunSec(double runSec) {
        this.runSec = runSec;
    }

    public double getIdleSec() {
        return idleSec;
    }

    public void setIdleSec(double idleSec) {
        this.idleSec = idleSec;
    }

    public double getStopSec() {
        return stopSec;
    }

    public void setStopSec(double stopSec) {
        this.stopSec = stopSec;
    }

    public double getAlarmSec() {
        return alarmSec;
    }

    public void setAlarmSec(double alarmSec) {
        this.alarmSec = alarmSec;
    }

    public double getUnknownSec() {
        return unknownSec;
    }

    public void setUnknownSec(double unknownSec) {
        this.unknownSec = unknownSec;
    }

    public double getOutput() {
        return output;
    }

    public void setOutput(double output) {
        this.output = output;
    }

    public double getBackfill() {
        return backfill;
    }

    public void setBackfill(double backfill) {
        this.backfill = backfill;
    }
}
