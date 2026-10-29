package com.factory.hub.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 设备模板：把"配好的一台设备"浓缩成可复用的通信参数 + 点表。
 *
 * <p>2026-10-29 用户需求：车间同型号设备多，一台台配点表不现实，要能"配一台、存模板、其余套用"。
 * 内置模板（西门子/台达/电表等）在前端 deviceTemplates.ts 里随版本发布；
 * 这里存的是<b>客户自己存下来的模板</b>——从现有设备抓取或按当前表单保存。
 *
 * <p>为什么不做成"内置模板也入库"：内置模板跟着代码版本走，升级即生效；
 * 客户模板是现场资产，必须落库、可备份、可删改。两者在前端合并展示（source 字段区分）。
 */
@Entity
@Table(name = "device_template")
public class DeviceTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模板名（客户自己起，如"XX 车间 汇川 H3U"），唯一避免选错 */
    @Column(name = "name", unique = true, nullable = false, length = 128)
    private String name;

    @Column(name = "brand", length = 64)
    private String brand;

    @Column(name = "model", length = 128)
    private String model;

    /** modbus-tcp / modbus-rtu-tcp / modbus-rtu */
    @Column(name = "protocol", length = 32)
    private String protocol = "modbus-tcp";

    @Column(name = "port")
    private Integer port = 502;

    @Column(name = "unit_id")
    private Integer unitId = 1;

    @Column(name = "poll_interval_ms")
    private Integer pollIntervalMs = 1000;

    @Column(name = "timeout_ms")
    private Integer timeoutMs = 800;

    /* ---- 485 / Modbus RTU 串口参数（2026-10-29 用户问"485 端口模板能不能取固定值"）----
       大多数 485 设备的出厂默认就是 9600-8-N-1、从站号 1，这部分完全可以预设；
       唯独"串口号"不能固定——它取决于现场那台电脑插的 USB 转 485 是 COM3 还是 COM5，
       所以 serialPort 只给默认值、允许改，其余三项基本可以照抄。 */
    @Column(name = "serial_port", length = 32)
    private String serialPort = "COM3";

    @Column(name = "baud_rate")
    private Integer baudRate = 9600;

    @Column(name = "data_bits")
    private Integer dataBits = 8;

    @Column(name = "stop_bits")
    private Integer stopBits = 1;

    /** 0=无校验 1=奇校验 2=偶校验 */
    @Column(name = "parity")
    private Integer parity = 0;

    /** 点表数组 JSON，与 DeviceEntity.pointsJson 同结构 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "points_json", columnDefinition = "TEXT")
    private String pointsJson;

    /** 备注：地址怎么来的、哪个点是什么，给下次用的人看 */
    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "created_at")
    private Long createdAt = System.currentTimeMillis();

    @Column(name = "updated_at")
    private Long updatedAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public Integer getUnitId() { return unitId; }
    public void setUnitId(Integer unitId) { this.unitId = unitId; }

    public Integer getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(Integer pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }

    public String getSerialPort() { return serialPort; }
    public void setSerialPort(String serialPort) { this.serialPort = serialPort; }

    public Integer getBaudRate() { return baudRate; }
    public void setBaudRate(Integer baudRate) { this.baudRate = baudRate; }

    public Integer getDataBits() { return dataBits; }
    public void setDataBits(Integer dataBits) { this.dataBits = dataBits; }

    public Integer getStopBits() { return stopBits; }
    public void setStopBits(Integer stopBits) { this.stopBits = stopBits; }

    public Integer getParity() { return parity; }
    public void setParity(Integer parity) { this.parity = parity; }

    public String getPointsJson() { return pointsJson; }
    public void setPointsJson(String pointsJson) { this.pointsJson = pointsJson; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
