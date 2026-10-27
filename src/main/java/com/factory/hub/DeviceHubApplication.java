package com.factory.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 工厂设备联网平台 - 采集骨架
 * <p>
 * 启动后访问 http://localhost:8080 看板
 * <p>
 * @EnableScheduling 是给授权模块用的：每分钟重算一次授权状态，
 * 这样跨零点到期、客户把系统时间改回来都能自动生效，不用重启服务。
 */
@SpringBootApplication
@EnableScheduling
public class DeviceHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeviceHubApplication.class, args);
    }
}
