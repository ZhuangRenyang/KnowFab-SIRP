package com.factory.hub.driver;

/**
 * 驱动连接参数。与 DeviceEntity 解耦，方便以后从别的地方（配置文件、远程下发）构造。
 */
public class DriverContext {

    private String host = "127.0.0.1";
    /** 设备编码。模拟器要靠它算这台机器的产量速率（跟播种的演示数据用同一套公式） */
    private String code = "";
    /**
     * 设备端口。<b>0 = 未指定</b>，由各协议自己的驱动回落默认端口
     * （Modbus TCP/RTU-over-TCP 502、OPC UA 4840）。
     * <p>
     * <b>为什么这里不写死 502</b>：502 是 Modbus 的端口，是协议知识，
     * 不是所有协议的共同默认。写死在这里会让 OPC UA 之类的协议去连 502，
     * 而且「协议默认端口」会散落在实体、控制器、上下文多处，改一处漏一处。
     */
    private int port = 0;
    private String serialPort = "COM3";
    private int baudRate = 9600;
    private int dataBits = 8;
    private int stopBits = 1;
    private int parity = 0;
    private int unitId = 1;
    /** 单次读写超时，毫秒。现场经验：不要超过 1000，否则一台设备卡住会拖慢整轮 */
    private int timeoutMs = 800;
    /**
     * 本机源地址（可选）：指定这台设备用<b>本机的哪个 IP</b>出去。
     * <p>
     * <b>null / 空 = 交给系统路由表决定</b>（绝大多数情况这样就对）。
     * 只有<b>同一台机器挂了多条同网段物理链路</b>、需要精确指定走哪条时才填。
     * <p>
     * ⚠️ <b>支持程度因协议而异</b>：
     * Modbus TCP / RTU-over-TCP 用 {@link java.net.Socket}，<b>可以</b> bind；
     * OPC UA 走 milo，其 {@code UaStackClientConfigBuilder} <b>没有</b> setLocalAddress
     * ⇒ <b>填了也不生效</b>，驱动会明确报出来（不静默忽略）。
     */
    private String sourceAddress;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getSourceAddress() {
        return sourceAddress;
    }

    public void setSourceAddress(String sourceAddress) {
        this.sourceAddress = sourceAddress;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getSerialPort() {
        return serialPort;
    }

    public void setSerialPort(String serialPort) {
        this.serialPort = serialPort;
    }

    public int getBaudRate() {
        return baudRate;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    public int getDataBits() {
        return dataBits;
    }

    public void setDataBits(int dataBits) {
        this.dataBits = dataBits;
    }

    public int getStopBits() {
        return stopBits;
    }

    public void setStopBits(int stopBits) {
        this.stopBits = stopBits;
    }

    public int getParity() {
        return parity;
    }

    public void setParity(int parity) {
        this.parity = parity;
    }

    public int getUnitId() {
        return unitId;
    }

    public void setUnitId(int unitId) {
        this.unitId = unitId;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
