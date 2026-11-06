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

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
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
