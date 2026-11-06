package com.factory.hub.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.factory.hub.DeviceTemplateController;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.DeviceTemplateEntity;
import com.factory.hub.web.DeviceController;
import com.factory.hub.web.dto.DeviceUpsert;

/**
 * 「协议 → 默认端口」这条口径在<b>保存路径</b>上的行为。
 * <p>
 * <b>为什么这两个用例要用反射调私有方法</b>：口径的决策点就在
 * {@code DeviceController.apply} / {@code DeviceTemplateController.applyCommon} 里，
 * 而这两个方法是私有的、没有外部依赖（不碰 repo/mapper），
 * 所以可以直接反射调 —— 比为了测它去重构控制器更小、更不打扰生产代码。
 * <p>
 * <b>守的是什么坑</b>：
 * <ul>
 *   <li>协议换了但端口没跟着换 ⇒ 设备连错端口；</li>
 *   <li>模板里选 OPC UA 却被静默改成 modbus-tcp ⇒ 界面选的协议和实际生效的不是一个，
 *       现场表现是「我明明选了 OPC UA，怎么连不上」，比端口填错更难查。</li>
 * </ul>
 */
class DevicePortSavePathTest {

    /** 反射调 DeviceController.apply(d, req) —— 它只改实体，不碰外部依赖。 */
    private static void apply(DeviceEntity d, DeviceUpsert req) throws Exception {
        DeviceController controller = new DeviceController(null, null, null, null, null, null, null);
        Method m = DeviceController.class.getDeclaredMethod("apply", DeviceEntity.class, DeviceUpsert.class);
        m.setAccessible(true);
        m.invoke(controller, d, req);
    }

    /** 反射调 DeviceTemplateController.applyCommon(t, body) —— 同样只改实体。 */
    private static void applyCommon(DeviceTemplateEntity t, Map<String, Object> body) throws Exception {
        DeviceTemplateController controller = new DeviceTemplateController(null, null, null, null);
        Method m = DeviceTemplateController.class
                .getDeclaredMethod("applyCommon", DeviceTemplateEntity.class, Map.class);
        m.setAccessible(true);
        m.invoke(controller, t, body);
    }

    private static DeviceUpsert req(String protocol, Integer port) {
        // 字段顺序照 DeviceUpsert 记录逐个列出来（28 个），改 DTO 时这里会编译报错 ——
        // 比「多写几个 null」靠位置对齐安全。
        // points 传 null ⇒ apply 里不碰 mapper（传 null 的 mapper 也不会 NPE）
        return new DeviceUpsert(
                "DEV-1",          // code
                "设备1",           // name
                "车间",            // location
                protocol,          // protocol
                "127.0.0.1",      // host
                port,             // port
                null,             // serialPort
                null,             // baudRate
                null,             // dataBits
                null,             // stopBits
                null,             // parity
                null,             // unitId
                null,             // pollIntervalMs
                null,             // timeoutMs
                null,             // offlineAfterFails
                null,             // apiKeyEnabled
                null,             // servoBrand
                null,             // enabled
                null,             // apiKey
                null,             // points
                null,             // prodSkuId
                null,             // prodWarehouseId
                null,             // prodResetHour
                null,             // prodAutoStockin
                null,             // dailyTarget
                null,             // idealCycleSec
                null,             // counterMode
                null);            // isMeter
    }

    @Test
    @DisplayName("新建设备没填端口 ⇒ 按协议给默认端口（OPC UA 4840 / Modbus 502）")
    void newDeviceGetsProtocolDefaultPort() throws Exception {
        DeviceEntity opcua = new DeviceEntity();
        apply(opcua, req("opcua", null));
        assertEquals(4840, opcua.getPort(), "OPC UA 设备没填端口 ⇒ 落库 4840（不能是 502）");

        DeviceEntity modbus = new DeviceEntity();
        apply(modbus, req("modbus-tcp", null));
        assertEquals(502, modbus.getPort(), "Modbus 设备没填端口 ⇒ 落库 502（行为不能变）");
    }

    @Test
    @DisplayName("用户显式填的端口不被覆盖（Modbus 的 502 也不例外）")
    void explicitPortIsNotOverwritten() throws Exception {
        DeviceEntity d = new DeviceEntity();
        apply(d, req("opcua", 4841));
        assertEquals(4841, d.getPort(), "用户填的端口必须原样落库");
    }

    @Test
    @DisplayName("协议从 modbus-tcp 换成 opcua、端口还是旧的默认值 502 ⇒ 跟着新协议走")
    void portFollowsProtocolSwitch() throws Exception {
        DeviceEntity d = new DeviceEntity();
        d.setProtocol("modbus-tcp");
        d.setPort(502);                 // 原来是 Modbus，端口 502
        apply(d, req("opcua", 502));     // 客户改了协议，端口没动（还是带着 502）

        assertEquals("opcua", d.getProtocol());
        assertEquals(4840, d.getPort(),
                "端口还是上一个协议的默认值 ⇒ 应跟着新协议走（否则就是去连 502，必然连不上）");
    }

    @Test
    @DisplayName("协议换了但用户明确填了别的端口 ⇒ 原样保留，不替用户决定")
    void explicitPortSurvivesProtocolSwitch() throws Exception {
        DeviceEntity d = new DeviceEntity();
        d.setProtocol("modbus-tcp");
        d.setPort(1502);
        apply(d, req("opcua", 1502));

        assertEquals("opcua", d.getProtocol());
        assertEquals(1502, d.getPort(), "用户明确填的端口不能被协议切换改掉");
    }

    @Test
    @DisplayName("Modbus 设备的端口行为完全不变（回归：改口径不许动到存量语义）")
    void modbusBehaviourUnchanged() throws Exception {
        DeviceEntity d = new DeviceEntity();
        apply(d, req("modbus-tcp", null));
        assertEquals("modbus-tcp", d.getProtocol());
        assertEquals(502, d.getPort(), "Modbus 没填端口 ⇒ 仍然是 502，一样都不许变");

        DeviceEntity rtu = new DeviceEntity();
        apply(rtu, req("modbus-rtu-tcp", null));
        assertEquals(502, rtu.getPort(), "Modbus RTU over TCP 没填端口 ⇒ 仍然是 502");
    }

    @Test
    @DisplayName("模板里选 opcua，保存后协议仍是 opcua（不能被兜底改成 modbus-tcp）")
    void templateKeepsOpcuaProtocol() throws Exception {
        DeviceTemplateEntity t = new DeviceTemplateEntity();
        Map<String, Object> body = new HashMap<>();
        body.put("protocol", "opcua");
        body.put("port", 4840);

        applyCommon(t, body);

        assertEquals("opcua", t.getProtocol(),
                "模板选了 opcua 却被改成了 " + t.getProtocol()
                        + " —— 界面选的协议和实际生效的不是一个，现场会表现为「明明选了 OPC UA 却连不上」");
        assertEquals(4840, t.getPort(), "用户填的端口应原样保留");
    }

    @Test
    @DisplayName("模板里选了真正脏的协议 ⇒ 仍然兜底成 modbus-tcp（别让脏协议进库）")
    void templateStillRejectsGarbageProtocol() throws Exception {
        DeviceTemplateEntity t = new DeviceTemplateEntity();
        Map<String, Object> body = new HashMap<>();
        body.put("protocol", "不存在的协议");

        applyCommon(t, body);

        assertEquals("modbus-tcp", t.getProtocol(), "不受支持的协议仍应兜底成 modbus-tcp");
    }

    @Test
    @DisplayName("模板没填端口 ⇒ 保持 null，应用到设备上时再按协议给默认端口")
    void templateWithoutPortStaysNull() throws Exception {
        DeviceTemplateEntity t = new DeviceTemplateEntity();
        Map<String, Object> body = new HashMap<>();
        body.put("protocol", "opcua");

        applyCommon(t, body);

        assertNotNull(t.getProtocol());
        assertEquals("opcua", t.getProtocol());
        assertTrue(t.getPort() == null,
                "模板没填端口 ⇒ 保持 null（= 没填），不能偷偷填成 502 —— "
                        + "填了 502 的话，应用到 OPC UA 设备上就会去连 502");
    }
}
