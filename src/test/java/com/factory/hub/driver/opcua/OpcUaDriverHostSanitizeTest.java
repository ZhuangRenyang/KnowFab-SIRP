package com.factory.hub.driver.opcua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.opcua.OpcUaDriver;
import com.factory.hub.model.PointDef;

/**
 * 主机地址消毒：客户在前端填什么，最后拼出来的端点 URL 都得是对的。
 * <p>
 * <b>守的是什么坑</b>：{@code DeviceEntity.host} 是前端输入框，没有格式校验。
 * 客户填 {@code 192.168.1.10/UA}、{@code 192.168.1.10:4840}、{@code opc.tcp://192.168.1.10}
 * 都是很自然的事。改造前直接拼字符串，结果是：
 * <ul>
 *   <li>{@code 192.168.1.10/UA} → {@code opc.tcp://192.168.1.10/UA:4840} → 端口解析失败</li>
 *   <li>{@code 192.168.1.10:4840} → {@code opc.tcp://192.168.1.10:4840:4840} → URL 非法</li>
 * </ul>
 * 而 milo 报的是 {@code Bad_TcpEndpointUrlInvalid}，<b>长得像「网络不通」</b>，
 * 实际是「地址填歪了」—— 现场排查会往网络方向跑，方向一错就浪费时间。
 * <p>
 * <b>断言的是最终拼出的 URL 字符串</b>，不是「没抛异常」：
 * 拼错了但没抛异常的情况，正是这个测试要堵的。
 */
class OpcUaDriverHostSanitizeTest {

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

    private DriverContext ctx(String host, int port) {
        DriverContext ctx = new DriverContext();
        ctx.setHost(host);
        ctx.setPort(port);
        ctx.setTimeoutMs(3000);
        return ctx;
    }

    @ParameterizedTest(name = "host={0} -> opc.tcp://192.168.1.10:4840")
    @ValueSource(strings = {
            "192.168.1.10",
            "192.168.1.10/UA",
            "192.168.1.10:4840",
            "opc.tcp://192.168.1.10",
    })
    @DisplayName("四种常见填法都拼出同一个正确的端点 URL")
    void sanitizesCommonHostInputs(String rawHost) {
        String url = OpcUaEndpointUrl.build(ctx(rawHost, 4840));

        assertEquals("opc.tcp://192.168.1.10:4840", url,
                "host 填成「" + rawHost + "」时应消毒成同一个端点 URL");
    }

    @Test
    @DisplayName("端口取连接参数里的值（主机里填的端口被丢弃并记账）")
    void portComesFromConnectionParameter() {
        // 客户在主机里填了 49320，但端口配置项是 4840 → 以端口配置项为准
        assertEquals("opc.tcp://192.168.1.10:4840",
                OpcUaEndpointUrl.build(ctx("192.168.1.10:49320", 4840)),
                "端口应取连接参数里的值，不能取主机里夹带的那个");

        assertEquals("opc.tcp://192.168.1.10:49320",
                OpcUaEndpointUrl.build(ctx("192.168.1.10", 49320)),
                "连接参数里的端口应原样使用");
    }

    @Test
    @DisplayName("端口没填或填了非正数时用 OPC UA 标准端口 4840")
    void fallsBackToDefaultPort() {
        assertEquals("opc.tcp://192.168.1.10:4840",
                OpcUaEndpointUrl.build(ctx("192.168.1.10", 0)),
                "端口为 0 时应退回 4840");
        assertEquals("opc.tcp://192.168.1.10:4840",
                OpcUaEndpointUrl.build(ctx("192.168.1.10", -1)),
                "端口为负数时也应退回 4840");
    }

    @Test
    @DisplayName("主机地址为空或洗不出来时抛明确异常（不能拼出 opc.tcp://:4840）")
    void rejectsEmptyOrUnusableHost() {
        for (String bad : new String[]{null, "", "   ", "/", "opc.tcp://", "opc.tcp:///"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> OpcUaEndpointUrl.build(ctx(bad, 4840)),
                    "host=" + bad + " 应抛明确异常");
            assertTrue(ex.getMessage().contains("主机地址"),
                    "异常信息要说清是主机地址的问题，实际：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("主机名（不是 IP）同样能消毒")
    void sanitizesHostNames() {
        assertEquals("opc.tcp://plc-master.local:4840",
                OpcUaEndpointUrl.build(ctx("plc-master.local", 4840)));
        assertEquals("opc.tcp://plc-master.local:4840",
                OpcUaEndpointUrl.build(ctx("opc.tcp://plc-master.local:49320/UA", 4840)));
    }

    /**
     * 端到端：地址填歪了也要能连上（不是只断言拼出来的字符串好看）。
     * 少了这条，上面的用例可能全对、但驱动实际仍然连不上。
     */
    @Test
    @DisplayName("地址填歪了也能真的连上本地 OPC UA Server 并读到值")
    void connectsWithDirtyHostInput() throws Exception {
        DriverContext ctx = ctx("127.0.0.1/UA", server.port());
        OpcUaDriver driver = new OpcUaDriver();
        try {
            driver.connect(ctx);
            assertTrue(driver.isConnected(), "消毒后的地址应能连上");

            PointDef p = new PointDef();
            p.setKey("tag1");
            p.setNodeId("ns=2;s=TestTag1");
            p.setDataType("f64");

            Map<String, Object> values = driver.read(ctx, List.of(p));
            assertEquals(42.5d, (Double) values.get("tag1"), 1e-9, "应读到服务端真实的值");
        } finally {
            driver.disconnect();
        }
    }
}
