package com.factory.hub.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Table;

/**
 * 设备实例。一行 = 现场一台机器。
 * pointsJson 里放该设备的点表数组，避免多表关联，单厂项目这样最省事。
 */
@Entity
@Table(name = "device")
public class DeviceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 设备编号，唯一，接口里用它寻址 */
    @Column(name = "code", unique = true, nullable = false, length = 64)
    private String code;

    @Column(name = "name", length = 128)
    private String name;

    @Column(name = "location", length = 128)
    private String location;

    /** modbus-tcp / modbus-rtu / sim */
    @Column(name = "protocol", length = 32)
    private String protocol = "modbus-tcp";

    @Column(name = "host", length = 64)
    private String host = "127.0.0.1";

    @Column(name = "port")
    private Integer port = 502;

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

    /** 从站地址 */
    @Column(name = "unit_id")
    private Integer unitId = 1;

    /** 采集周期，毫秒 */
    @Column(name = "poll_interval_ms")
    private Integer pollIntervalMs = 1000;

    /**
     * 单次读写超时，毫秒。默认 800 适合交换机直连；
     * 走 WiFi / 4G 路由 / 无线网桥时必须调大（2000~3000），
     * 否则链路抖一下就超时，页面上一片红，客户以为设备坏了。
     */
    @Column(name = "timeout_ms")
    private Integer timeoutMs = 800;

    /**
     * 连续失败几次才判离线。默认 2：无线网络偶发丢一个包不算掉线，
     * 判离线会让设备在页面上闪红，运行人员会被训练成"忽略红色"，
     * 真出事的时候也没人看。
     */
    @Column(name = "offline_after_fails")
    private Integer offlineAfterFails = 2;

    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;

    /**
     * 伺服/PLC 品牌，决定报警码用哪本字典翻译（三菱/台达/…）。
     * 留空 = 不翻译，维修卡只显示原始码值。
     */
    @Column(name = "servo_brand", length = 24)
    private String servoBrand;

    /**
     * 对外 API 是否启用。停用后这个 key 立刻失效，但设备照常采集——
     * 客户要停掉某条对接（比如外包的系统不合作了）时，不该连采集一起停。
     */
    @Column(name = "api_key_enabled")
    private Boolean apiKeyEnabled = Boolean.TRUE;

    @Column(name = "api_key", length = 64)
    private String apiKey;

    /** 点表 JSON 数组 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "points_json")
    private String pointsJson;

    /* ---- 生产入库联动（DH-44）：绑定成品 SKU 后，计数点每 +N 自动生成入库单 ---- */
    @Column(name = "prod_sku_id")
    private Long prodSkuId;

    @Column(name = "prod_wh_id")
    private Long prodWhId;

    /** 日产量统计清零时刻（0~23 点）：如填 8，则"一天"从早 8 点算到次日 8 点 */
    @Column(name = "prod_reset_hour", nullable = false)
    private int prodResetHour = 0;

    @Column(name = "prod_auto_stockin", nullable = false)
    private boolean prodAutoStockin = false;

    /** 所属产线（DH-40）；null = 未分配 */
    @Column(name = "prod_line_id")
    private Long prodLineId;

    /** 工位序号：产线内从 1 开始，最大的那台默认是成品计数点 */
    @Column(name = "line_seq", nullable = false)
    private int lineSeq = 0;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name == null ? code : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getSerialPort() {
        return serialPort;
    }

    public void setSerialPort(String serialPort) {
        this.serialPort = serialPort;
    }

    public Integer getBaudRate() {
        return baudRate;
    }

    public void setBaudRate(Integer baudRate) {
        this.baudRate = baudRate;
    }

    public Integer getDataBits() {
        return dataBits;
    }

    public void setDataBits(Integer dataBits) {
        this.dataBits = dataBits;
    }

    public Integer getStopBits() {
        return stopBits;
    }

    public void setStopBits(Integer stopBits) {
        this.stopBits = stopBits;
    }

    public Integer getParity() {
        return parity;
    }

    public void setParity(Integer parity) {
        this.parity = parity;
    }

    public Integer getUnitId() {
        return unitId;
    }

    public void setUnitId(Integer unitId) {
        this.unitId = unitId;
    }

    public Integer getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(Integer pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public Integer getTimeoutMs() {
        return timeoutMs == null || timeoutMs <= 0 ? 800 : timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Integer getOfflineAfterFails() {
        return offlineAfterFails == null || offlineAfterFails <= 0 ? 2 : offlineAfterFails;
    }

    public void setOfflineAfterFails(Integer offlineAfterFails) {
        this.offlineAfterFails = offlineAfterFails;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isApiKeyEnabled() {
        return apiKeyEnabled == null || apiKeyEnabled;
    }

    public void setApiKeyEnabled(Boolean apiKeyEnabled) {
        this.apiKeyEnabled = apiKeyEnabled;
    }

    public String getServoBrand() {
        return servoBrand;
    }

    public void setServoBrand(String servoBrand) {
        this.servoBrand = servoBrand;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getPointsJson() {
        return pointsJson;
    }

    public void setPointsJson(String pointsJson) {
        this.pointsJson = pointsJson;
    }

    public Long getProdSkuId() { return prodSkuId; }
    public void setProdSkuId(Long prodSkuId) { this.prodSkuId = prodSkuId; }
    public Long getProdWhId() { return prodWhId; }
    public void setProdWhId(Long prodWhId) { this.prodWhId = prodWhId; }
    public int getProdResetHour() { return prodResetHour; }
    public void setProdResetHour(int prodResetHour) { this.prodResetHour = prodResetHour; }
    public Long getProdLineId() { return prodLineId; }
    public void setProdLineId(Long prodLineId) { this.prodLineId = prodLineId; }
    public int getLineSeq() { return lineSeq; }
    public void setLineSeq(int lineSeq) { this.lineSeq = lineSeq; }
    public boolean isProdAutoStockin() { return prodAutoStockin; }
    public void setProdAutoStockin(boolean prodAutoStockin) { this.prodAutoStockin = prodAutoStockin; }
}
