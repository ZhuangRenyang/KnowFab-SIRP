package com.factory.hub.driver;

import com.factory.hub.model.PointDef;

import java.util.List;
import java.util.Map;

/**
 * 协议驱动接口——这是整个框架最值钱的一个抽象。
 * <p>
 * 接一台新设备：实现这个接口，注册成 Spring Bean，业务代码一行不用改。
 */
public interface ProtocolDriver {

    /** 协议标识，要和 device.protocol 对上，如 modbus-tcp */
    String type();

    /** 建立连接（或确认连接可用） */
    void connect(DriverContext ctx) throws Exception;

    boolean isConnected();

    /**
     * 批量读取点表。
     *
     * @return key = PointDef.key，value = 已按 scale/offset 换算好的业务值
     */
    Map<String, Object> read(DriverContext ctx, List<PointDef> points) throws Exception;

    /** 写单个点（远程下发：改配方、复位、启停） */
    void write(DriverContext ctx, PointDef point, Object value) throws Exception;

    /** 断开连接，必须不抛异常 */
    void disconnect();
}
