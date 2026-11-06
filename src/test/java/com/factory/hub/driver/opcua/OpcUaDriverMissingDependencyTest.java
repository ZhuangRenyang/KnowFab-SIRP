package com.factory.hub.driver.opcua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「运行类路径上缺 javax.xml.bind」时，驱动必须<b>只丢坏的那个点</b>。
 * <p>
 * <b>守的是什么坑</b>：{@code NoClassDefFoundError} 继承 {@code LinkageError}
 * （是 Error 不是 Exception），驱动里原先的 {@code catch (Exception e)} 抓不到它
 * ⇒ 它会穿透 {@code read()} 抛出去 ⇒ <b>整批点位全废</b>，
 * 现场表现是「设备在线、心跳正常、但所有数据都是空的」，而且日志里一行线索都没有。
 * <p>
 * <b>怎么造出「缺依赖」的环境</b>：本测试 JVM 里有 jaxb-api（pom 里是 runtime 作用域），
 * 所以用一个<b>屏蔽了 jaxb 的类加载器</b>重新加载驱动与它的依赖，
 * 让它看到的就是「交付包漏了这个 jar」的真实环境。
 * <p>
 * 单独一个类，是因为这个场景要自己起 server（用反射调驱动，不能和普通用例混在一起），
 * 混进去会让 {@code OpcUaDriverLocalServerTest} 超过单文件 300 行的门禁。
 */
class OpcUaDriverMissingDependencyTest {

    private OpcUaTestServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = OpcUaTestServer.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("缺 javax.xml.bind 时坏点只丢自己，同批次其它点照常读到（不能整批失败）")
    void skipsOnlyBrokenPointWhenDependencyMissing() throws Exception {
        try (URLClassLoader noJaxb = NoJaxbClassLoader.create()) {
            Class<?> driverClass = noJaxb.loadClass("com.factory.hub.driver.opcua.OpcUaDriver");
            Class<?> ctxClass = noJaxb.loadClass("com.factory.hub.driver.DriverContext");
            Class<?> pointClass = noJaxb.loadClass("com.factory.hub.model.PointDef");

            Object driver = driverClass.getDeclaredConstructor().newInstance();
            Object ctx = newContext(ctxClass);

            Object good = newPoint(pointClass, "tag1", "ns=2;s=TestTag1");
            Object bad = newPoint(pointClass, "bstr", OpcUaTestServer.BYTE_STRING_TAG_NODE_ID);

            Method read = driverClass.getMethod("read", ctxClass, List.class);
            Map<?, ?> out = (Map<?, ?>) read.invoke(driver, ctx, List.of(good, bad));

            assertEquals(1, out.size(), "坏点应被跳过，好点照常读到，实际：" + out);
            assertEquals(42.5d, ((Number) out.get("tag1")).doubleValue(), 1e-9,
                    "好点的值应照常读到 42.5");
        }
    }

    @Test
    @DisplayName("缺 javax.xml.bind 时只读坏点，异常信息要能看出是缺依赖（不是笼统的「格式不对」）")
    void reportsMissingDependencyInExceptionMessage() throws Exception {
        try (URLClassLoader noJaxb = NoJaxbClassLoader.create()) {
            Class<?> driverClass = noJaxb.loadClass("com.factory.hub.driver.opcua.OpcUaDriver");
            Class<?> ctxClass = noJaxb.loadClass("com.factory.hub.driver.DriverContext");
            Class<?> pointClass = noJaxb.loadClass("com.factory.hub.model.PointDef");

            Object driver = driverClass.getDeclaredConstructor().newInstance();
            Object ctx = newContext(ctxClass);
            Object bad = newPoint(pointClass, "bstr", OpcUaTestServer.BYTE_STRING_TAG_NODE_ID);

            Method read = driverClass.getMethod("read", ctxClass, List.class);
            InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                    () -> read.invoke(driver, ctx, List.of(bad)),
                    "只读坏点时应抛出异常");
            String message = ex.getCause().getMessage();
            assertTrue(message != null && message.contains("javax.xml.bind"),
                    "异常信息应指出缺 javax.xml.bind，实际：" + message);
            assertTrue(message != null && message.contains(OpcUaTestServer.BYTE_STRING_TAG_NODE_ID),
                    "异常信息应带上原始 nodeId，实际：" + message);
        }
    }

    /** 建一个指向本地测试 server 的连接参数（屏蔽类加载器里的那个 DriverContext）。 */
    private Object newContext(Class<?> ctxClass) throws Exception {
        Object ctx = ctxClass.getDeclaredConstructor().newInstance();
        ctxClass.getMethod("setHost", String.class).invoke(ctx, "127.0.0.1");
        ctxClass.getMethod("setPort", int.class).invoke(ctx, server.port());
        ctxClass.getMethod("setTimeoutMs", int.class).invoke(ctx, 3000);
        return ctx;
    }

    private static Object newPoint(Class<?> pointClass, String key, String nodeId) throws Exception {
        Object p = pointClass.getDeclaredConstructor().newInstance();
        pointClass.getMethod("setKey", String.class).invoke(p, key);
        pointClass.getMethod("setNodeId", String.class).invoke(p, nodeId);
        pointClass.getMethod("setDataType", String.class).invoke(p, "f64");
        return p;
    }
}
