package com.factory.hub.driver;

/**
 * 驱动工厂。每台设备要独占一个连接，所以驱动不能做成单例，
 * 由工厂按需创建实例。
 */
public interface DriverFactory {

    String type();

    ProtocolDriver create();
}
