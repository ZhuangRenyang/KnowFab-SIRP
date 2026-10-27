package com.factory.hub.driver;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 驱动注册表。Spring 启动时会自动把所有 DriverFactory Bean 注入进来，
 * 加新协议只需要新增一个 @Component 工厂类，这里不用动。
 */
@Component
public class DriverRegistry {

    private final Map<String, DriverFactory> factories = new LinkedHashMap<>();

    public DriverRegistry(List<DriverFactory> list) {
        for (DriverFactory f : list) {
            factories.put(f.type(), f);
        }
    }

    public ProtocolDriver create(String type) {
        DriverFactory f = factories.get(type);
        if (f == null) {
            throw new IllegalArgumentException(
                    "不支持的协议类型: " + type + "，已注册: " + factories.keySet());
        }
        return f.create();
    }

    public Set<String> types() {
        return Collections.unmodifiableSet(factories.keySet());
    }
}
