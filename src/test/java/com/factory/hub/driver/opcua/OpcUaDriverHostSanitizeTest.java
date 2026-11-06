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
 * 主机地址校验：客户在前端填什么，最后拼出来的端点 URL 都得是对的。
 * <p>
 * <b>守的是什么坑</b>：{@code DeviceEntity.host} 是前端输入框，没有格式校验。
 * 客户填 {@code 192.168.1.10/UA}、{@code 192.168.1.10:4840}、{@code opc.tcp://192.168.1.10}
 * 都是很自然的事。而三种填法<b>不能一刀切</b>：
 * <ul>
 *   <li><b>协议前缀</b>（{@code opc.tcp://}）→ 无害的冗余，静默去掉；</li>
 *   <li><b>末尾路径</b>（{@code /UA}）→ <b>必须报错</b>。端点带子路径是真实存在的形态，
 *       静默丢掉等于把客户指向<b>另一台</b>服务端；</li>
 *   <li><b>主机栏里的端口</b>（{@code :4840}）→ 与连接参数不一致就<b>必须报错</b>。
 *       静默替换可能连到<b>另一台设备</b>（端口不同 ⇒ 不同的服务）。</li>
 * </ul>
 * <p>
 * <b>为什么「静默丢弃」比「报错」危险</b>（项目铁律）：
 * 报错会留痕、客户当场能改对；静默丢弃只留一个查不出来的
 * {@code Bad_TcpEndpointUrlInvalid}，现场会一路往网络方向查，方向一错就浪费时间。
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
            "192.168.1.10:4840",
            "opc.tcp://192.168.1.10",
            "opc.tcp://192.168.1.10:4840",
    })
    @DisplayName("纯 IP、带协议前缀、端口与连接参数一致 → 都拼出同一个正确的端点 URL")
    void acceptsHarmlessHostInputs(String rawHost) {
        String url = OpcUaEndpointUrl.build(ctx(rawHost, 4840));

        assertEquals("opc.tcp://192.168.1.10:4840", url,
                "host 填成「" + rawHost + "」时应拼出同一个端点 URL");
    }

    @Test
    @DisplayName("主机栏带路径必须明确报错（不能静默丢掉 —— 会指向另一台服务端）")
    void rejectsHostWithPath() {
        for (String raw : new String[]{"192.168.1.10/UA", "192.168.1.10/milo",
                "opc.tcp://192.168.1.10:4840/milo"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> OpcUaEndpointUrl.build(ctx(raw, 4840)),
                    "host=" + raw + " 带路径应明确报错");
            assertTrue(ex.getMessage().contains("不能带路径"),
                    "异常信息要说清是路径的问题，实际：" + ex.getMessage());
            // 报错里要带原始输入：客户得知道自己填错了什么，才知道改哪
            assertTrue(ex.getMessage().contains("原始输入"),
                    "报错里要带原始输入，客户才知道改哪，实际：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("主机栏里的端口与连接参数不一致必须明确报错（静默替换可能连到别的设备）")
    void rejectsHostPortMismatch() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OpcUaEndpointUrl.build(ctx("192.168.1.10:4841", 4840)),
                "主机栏端口 4841 与连接参数端口 4840 不一致，应报错");
        assertTrue(ex.getMessage().contains("4841") && ex.getMessage().contains("4840"),
                "异常信息要把两个端口都写出来，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("主机栏里的端口不是数字也要明确报错")
    void rejectsNonNumericHostPort() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OpcUaEndpointUrl.build(ctx("192.168.1.10:abc", 4840)));
        assertTrue(ex.getMessage().contains("不是数字"),
                "异常信息要说清端口不是数字，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("端口取连接参数里的值（主机里夹带的端口一致时去掉、不一致时报错）")
    void portComesFromConnectionParameter() {
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
        for (String bad : new String[]{null, "", "   ", "opc.tcp://"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> OpcUaEndpointUrl.build(ctx(bad, 4840)),
                    "host=" + bad + " 应抛明确异常");
            assertTrue(ex.getMessage().contains("主机地址"),
                    "异常信息要说清是主机地址的问题，实际：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("端口没填（上下文里是 0）时 OPC UA 用自己的默认端口 4840")
    void opcUaUsesItsOwnDefaultPort() {
        // 「默认端口是多少」是协议知识：Modbus 502、OPC UA 4840，各驱动自己兜底。
        // 上游（实体/控制器/上下文）现在统一传 0 表示「未填」，这里验的就是这一段。
        DriverContext ctx = ctx("192.168.1.10", 0);
        assertEquals("opc.tcp://192.168.1.10:4840", OpcUaEndpointUrl.build(ctx),
                "端口未填时 OPC UA 应回落到 4840");

        DriverContext explicit = ctx("192.168.1.10", 4841);
        assertEquals("opc.tcp://192.168.1.10:4841", OpcUaEndpointUrl.build(explicit),
                "用户填了端口就用填的");
    }

    @Test
    @DisplayName("端口没填（上下文里是 0）且主机栏没端口时不该误报「端口不一致」")
    void unfilledPortDoesNotTriggerMismatchError() {
        // 这条是「端口默认值口径」改完之后才成立的：
        // 之前端口没填会被上游填成 502，于是主机栏里的 :4840 被判成「不一致」而误报。
        String url = OpcUaEndpointUrl.build(ctx("192.168.1.10:4840", 0));
        assertEquals("opc.tcp://192.168.1.10:4840", url,
                "端口未填时按协议兜底成 4840，主机栏里的 4840 与之一致 ⇒ 不该报错");
    }

    @Test
    @DisplayName("主机名（不是 IP）同样能消毒")
    void sanitizesHostNames() {
        assertEquals("opc.tcp://plc-master.local:4840",
                OpcUaEndpointUrl.build(ctx("plc-master.local", 4840)));
        assertEquals("opc.tcp://plc-master.local:4840",
                OpcUaEndpointUrl.build(ctx("opc.tcp://plc-master.local", 4840)));
    }

    @Test
    @DisplayName("裸 IPv6 必须补方括号（否则 URI.getHost() 返回 null、端口都解析不出来）")
    void bareIpv6GetsBrackets() {
        assertEquals("[::1]", OpcUaEndpointUrl.sanitizeHost("::1"));
        assertEquals("[fe80::1]", OpcUaEndpointUrl.sanitizeHost("fe80::1"));
        assertEquals("[::1]", OpcUaEndpointUrl.sanitizeHost("[::1]"), "已带括号不能重复加");
        assertEquals("[::1]", OpcUaEndpointUrl.sanitizeHost("[::1]:4840"));

        // 拼出来的 URL 必须能被 java.net.URI 正确解析出 host 与 port
        String url = OpcUaEndpointUrl.build(ctx("::1", 4840));
        assertEquals("opc.tcp://[::1]:4840", url);
        java.net.URI uri = java.net.URI.create(url);
        assertEquals("[::1]", uri.getHost(), "裸 IPv6 不补括号的话 getHost() 会是 null");
        assertEquals(4840, uri.getPort(), "裸 IPv6 不补括号的话 getPort() 会是 -1");
    }

    /**
     * 端到端：地址填歪了也要能连上（不是只断言拼出来的字符串好看）。
     * 少了这条，上面的用例可能全对、但驱动实际仍然连不上。
     */
    @Test
    @DisplayName("地址带协议前缀也能真的连上本地 OPC UA Server 并读到值")
    void connectsWithDirtyHostInput() throws Exception {
        DriverContext ctx = ctx("opc.tcp://127.0.0.1", server.port());
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
