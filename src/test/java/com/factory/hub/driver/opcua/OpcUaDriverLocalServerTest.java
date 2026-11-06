package com.factory.hub.driver.opcua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.opcua.OpcUaDriver;
import com.factory.hub.model.PointDef;

/**
 * 用<b>真的本地 OPC UA Server</b> 验 {@link OpcUaDriver} 在 Spring Boot 4.1.2 下能不能跑通。
 * <p>
 * <b>为什么要专门写这个测试</b>：现场真机是西门子 S7-200，<b>不支持 OPC UA</b>，
 * 所以「驱动代码本身在 Boot 4 下是不是好的」没法靠真机回答。
 * 这里起一个真的 milo OPC UA Server（同版本 0.6.11、同样的二进制 UASC 通道），
 * 用<b>项目里那份没改过的 {@code OpcUaDriver}</b> 去连、去读、去写，
 * 把「驱动代码在 Boot 4.1.2 + milo 0.6.11 下可用」这件事从推断变成实测。
 * <p>
 * <b>这个测试覆盖了什么、没覆盖什么</b>：
 * <ul>
 *   <li><b>覆盖</b>：会话建立、端点选择（SecurityPolicy.None）、批量 Read、Write、
 *       nodeId 解析、值转换（scale/offset）、实时值刷新。</li>
 *   <li><b>没覆盖</b>：真 PLC 的命名空间索引、品牌差异、用户名密码认证、
 *       加密端点（驱动第 74-76 行就只收 None）。这些要等有支持 OPC UA 的设备才能验。</li>
 * </ul>
 * <p>
 * <b>断言的是具体数值，不是「不抛异常」</b>：读不出来和不报错是两回事，
 * 必须断言服务端给的真实值（42.5 / 7.25 / 123 / true）逐个对上。
 */
class OpcUaDriverLocalServerTest {

    private OpcUaTestServer server;
    private OpcUaDriver driver;
    private DriverContext ctx;

    @BeforeEach
    void startServer() throws Exception {
        server = OpcUaTestServer.start();
        driver = new OpcUaDriver();
        ctx = new DriverContext();
        ctx.setHost("127.0.0.1");
        ctx.setPort(server.port());
        ctx.setCode("TEST-PLC");
        ctx.setTimeoutMs(3000);
    }

    @AfterEach
    void stopServer() {
        if (driver != null) {
            driver.disconnect();
        }
        if (server != null) {
            server.close();
        }
    }

    private PointDef point(String key, String nodeId, String dataType) {
        PointDef p = new PointDef();
        p.setKey(key);
        p.setNodeId(nodeId);
        p.setDataType(dataType);
        return p;
    }

    @Test
    @DisplayName("驱动能对本地 OPC UA Server 建立真实会话（Boot 4.1.2 + milo 0.6.11）")
    void driverEstablishesRealSession() throws Exception {
        driver.connect(ctx);

        assertTrue(driver.isConnected(), "驱动应处于已连接状态");
        assertEquals("opcua", driver.type(), "协议类型应为 opcua");
    }

    @Test
    @DisplayName("批量 Read 能读到服务端节点的真实值（逐个断言，不是只断言不抛异常）")
    void readsRealValuesFromServer() throws Exception {
        driver.connect(ctx);

        Map<String, Object> values = driver.read(ctx, List.of(
                point("tag1", "ns=2;s=TestTag1", "f64"),
                point("tag2", "ns=2;s=TestTag2", "f64")));

        assertEquals(2, values.size(), "两个点都应读到值，实际读到 " + values);
        assertEquals(42.5d, (Double) values.get("tag1"), 1e-9, "TestTag1 的值应等于服务端给的 42.5");
        assertEquals(7.25d, (Double) values.get("tag2"), 1e-9, "TestTag2 的值应等于服务端给的 7.25");
    }

    @Test
    @DisplayName("不同数据类型的点位都能读出来（Double / Int32 / Boolean）")
    void readsDifferentDataTypes() throws Exception {
        driver.connect(ctx);

        Map<String, Object> values = driver.read(ctx, List.of(
                point("dbl", "ns=2;s=TestTag1", "f64"),
                point("i32", "ns=2;s=TestTag3", "s32"),
                point("bool", "ns=2;s=TestTag4", "bool")));

        assertEquals(3, values.size(), "三个点都应读到值，实际读到 " + values);
        assertEquals(42.5d, (Double) values.get("dbl"), 1e-9);
        assertEquals(123.0d, (Double) values.get("i32"), 1e-9, "Int32 应转成 double");
        assertEquals(1.0d, (Double) values.get("bool"), 1e-9, "Boolean true 应转成 1.0");
    }

    @Test
    @DisplayName("scale / offset 在读出来之后按点表生效")
    void appliesScaleAndOffset() throws Exception {
        driver.connect(ctx);

        PointDef scaled = point("scaled", "ns=2;s=TestTag1", "f64");
        scaled.setScale(2.0d);
        scaled.setOffset(1.0d);

        Map<String, Object> values = driver.read(ctx, List.of(scaled));

        assertEquals(42.5d * 2.0d + 1.0d, (Double) values.get("scaled"), 1e-9,
                "应按 实际值 * scale + offset 换算");
    }

    @Test
    @DisplayName("ByteString 节点号（ns=2;b=...）能读到值（javax.xml.bind 在运行时类路径上到位了）")
    void readsByteStringNodeId() throws Exception {
        driver.connect(ctx);

        Map<String, Object> values = driver.read(ctx, List.of(
                point("bstr", OpcUaTestServer.BYTE_STRING_TAG_NODE_ID, "f64")));

        assertEquals(1, values.size(), "ByteString 节点号应读到值，实际读到 " + values);
        assertEquals(66.5d, (Double) values.get("bstr"), 1e-9,
                "ByteString 节点号的值应等于服务端给的 66.5");
    }

    @Test
    @DisplayName("读到的是实时值，不是缓存（服务端值在变，两次读应不同）")
    void readsLiveValuesNotCache() throws Exception {
        driver.connect(ctx);

        Double first = (Double) driver.read(ctx, List.of(
                point("live", "ns=2;s=" + OpcUaTestServer.LIVE_TAG, "f64"))).get("live");
        Thread.sleep(400L);
        Double second = (Double) driver.read(ctx, List.of(
                point("live", "ns=2;s=" + OpcUaTestServer.LIVE_TAG, "f64"))).get("live");

        assertNotNull(first);
        assertNotNull(second);
        assertTrue(second > first,
                "服务端每 100ms 递增一次，两次读应不同：第一次 " + first + "，第二次 " + second);
    }

    @Test
    @DisplayName("Write 能写进服务端，读回来是新值")
    void writesValueBackToServer() throws Exception {
        driver.connect(ctx);

        driver.write(ctx, point("tag1", "ns=2;s=TestTag1", "f64"), 99.5d);

        Double readBack = (Double) driver.read(ctx, List.of(
                point("tag1", "ns=2;s=TestTag1", "f64"))).get("tag1");
        assertEquals(99.5d, readBack, 1e-9, "写入后应能读回新值 99.5");
    }

    @Test
    @DisplayName("nodeId 不合法的点被跳过，不影响其他点（不因一个脏点整批失败）")
    void skipsInvalidNodeIdButKeepsOthers() throws Exception {
        driver.connect(ctx);

        Map<String, Object> values = driver.read(ctx, List.of(
                point("bad", "这不是一个合法的 nodeId", "f64"),
                point("good", "ns=2;s=TestTag2", "f64")));

        assertEquals(1, values.size(), "只有合法节点应返回：" + values);
        assertEquals(7.25d, (Double) values.get("good"), 1e-9);
    }

    @Test
    @DisplayName("所有点位都读不到时抛异常，而不是静默返回空 Map")
    void throwsWhenNoPointReadable() throws Exception {
        driver.connect(ctx);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> driver.read(ctx, List.of(point("missing", "ns=2;s=NotExistTag", "f64"))));
        assertTrue(ex.getMessage().contains("所有点位读取失败"),
                "异常信息应说明是点位读失败，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("服务端不在线时抛异常（不会假装连上了）")
    void failsWhenServerDown() {
        int deadPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ctx.setPort(deadPort);

        Exception ex = assertThrows(Exception.class, () -> driver.connect(ctx));
        // 注意：NoClassDefFoundError 是 Error 不是 Exception，真发生了会直接让本用例失败，
        // 这里再显式点一次名，是为了让失败信息一眼看出是「类加载」问题而不是「网络」问题
        assertFalse("NoClassDefFoundError".equals(ex.getClass().getSimpleName()),
                "连接失败不应是类加载失败（那说明 Boot 4 类路径上缺东西）：" + ex);
        assertFalse("ClassNotFoundException".equals(ex.getClass().getSimpleName()),
                "连接失败不应是类加载失败（那说明 Boot 4 类路径上缺东西）：" + ex);
        assertFalse(driver.isConnected(), "连不上时不应报告已连接");
    }
}
