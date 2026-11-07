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

    /**
     * 反射调 DeviceController.apply(d, req, created) —— 它只改实体，不碰外部依赖。
     *
     * @param created 是否新建。新建时 d 里的 protocol 只是实体字段默认值，
     *                 <b>不代表用户意图</b>，所以「协议切换时端口跟随」那条规则不该触发。
     */
    private static void apply(DeviceEntity d, DeviceUpsert req, boolean created) throws Exception {
        DeviceController controller = new DeviceController(null, null, null, null, null, null, null);
        Method m = DeviceController.class
                .getDeclaredMethod("apply", DeviceEntity.class, DeviceUpsert.class, boolean.class);
        m.setAccessible(true);
        m.invoke(controller, d, req, created);
    }

    /** 新建一台设备（实体是全新的，protocol 字段默认值 "modbus-tcp"）。 */
    private static void create(DeviceEntity d, DeviceUpsert req) throws Exception {
        apply(d, req, true);
    }

    /** 更新一台已有设备（d 里带着库里真实的旧协议与旧端口）。 */
    private static void update(DeviceEntity d, DeviceUpsert req) throws Exception {
        apply(d, req, false);
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
                null,             // isMeter
                null);            // sourceAddress
    }

    @Test
    @DisplayName("新建设备没填端口 ⇒ 按协议给默认端口（OPC UA 4840 / Modbus 502）")
    void newDeviceGetsProtocolDefaultPort() throws Exception {
        DeviceEntity opcua = new DeviceEntity();
        create(opcua, req("opcua", null));
        assertEquals(4840, opcua.getPort(), "OPC UA 设备没填端口 ⇒ 落库 4840（不能是 502）");

        DeviceEntity modbus = new DeviceEntity();
        create(modbus, req("modbus-tcp", null));
        assertEquals(502, modbus.getPort(), "Modbus 设备没填端口 ⇒ 落库 502（行为不能变）");
    }

    @Test
    @DisplayName("用户显式填的端口不被覆盖（Modbus 的 502 也不例外）")
    void explicitPortIsNotOverwritten() throws Exception {
        DeviceEntity d = new DeviceEntity();
        create(d, req("opcua", 4841));
        assertEquals(4841, d.getPort(), "用户填的端口必须原样落库");
    }

    /**
     * <b>新建</b> OPC UA 设备、端口手填 502 ⇒ <b>落库 502</b>，不被改成 4840。
     * <p>
     * <b>为什么这条要单独钉住</b>：新建的实体里 {@code protocol} 字段的默认值是
     * {@code "modbus-tcp"}，那是<b>实体字段的默认值</b>，不代表用户的任何意图。
     * 拿它当「旧协议」⇒ 用户明确填的 502 会被静默改成 4840 ⇒ 界面与实际不一致。
     * 「协议切换时端口跟随」的前提是<b>存在一台旧设备</b>，新建时不存在 ⇒ 这条规则不适用。
     * <p>
     * 后果（明确接受）：保留 502 ⇒ 驱动去连 502 ⇒ 连不上 ⇒
     * <b>那是用户填错的直接后果，系统不替他改</b> ——
     * 「用户填了就是用户的」比「替他猜对」更重要（OPC UA 在 502 上监听约等于不可能）。
     */
    @Test
    @DisplayName("新建 OPC UA 手填 502 ⇒ 落库 502（用户的值不被改，新建不走「端口跟随」）")
    void createKeepsUserFilledPortEvenIfItLooksLikeAnotherProtocolDefault() throws Exception {
        DeviceEntity d = new DeviceEntity();
        // 注意 d.getProtocol() 此时是实体字段默认值 "modbus-tcp"，
        // 它<b>不是</b>用户的旧协议 —— 新建时不存在旧设备
        assertEquals("modbus-tcp", d.getProtocol(), "新建实体的协议字段默认值（前提，不是用户意图）");

        create(d, req("opcua", 502));

        assertEquals("opcua", d.getProtocol());
        assertEquals(502, d.getPort(),
                "新建时端口完全按用户填的来：用户填了 502 就保留 502，"
                        + "不能被静默改成 4840（那会造成界面与实际不一致）");
    }

    @Test
    @DisplayName("更新（原来是 modbus + 502）→ 改成 opcua ⇒ 落库 4840（端口跟随）")
    void portFollowsProtocolSwitch() throws Exception {
        DeviceEntity d = new DeviceEntity();
        d.setProtocol("modbus-tcp");
        d.setPort(502);                 // 原来是 Modbus，端口 502
        update(d, req("opcua", 502));     // 客户改了协议，端口没动（还是带着 502）

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
        update(d, req("opcua", 1502));

        assertEquals("opcua", d.getProtocol());
        assertEquals(1502, d.getPort(), "用户明确填的端口不能被协议切换改掉");
    }

    @Test
    @DisplayName("Modbus 设备的端口行为完全不变（回归：改口径不许动到存量语义）")
    void modbusBehaviourUnchanged() throws Exception {
        DeviceEntity d = new DeviceEntity();
        create(d, req("modbus-tcp", null));
        assertEquals("modbus-tcp", d.getProtocol());
        assertEquals(502, d.getPort(), "Modbus 没填端口 ⇒ 仍然是 502，一样都不许变");

        DeviceEntity rtu = new DeviceEntity();
        create(rtu, req("modbus-rtu-tcp", null));
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
    @DisplayName("模板端口填了但不是数字 ⇒ 落库 0（未指定），不能兜成 502")
    void templateWithGarbagePortBecomesUnspecified() throws Exception {
        DeviceTemplateEntity t = new DeviceTemplateEntity();
        Map<String, Object> body = new HashMap<>();
        body.put("protocol", "opcua");
        body.put("port", "不是数字");

        applyCommon(t, body);

        assertEquals(0, t.getPort(),
                "端口解析失败应是「未指定」(0)，不能兜成 502 —— "
                        + "兜成 502 会又造出「未填」与「Modbus 默认」混用的场景");
    }

    @Test
    @DisplayName("模板端口填了 4840 ⇒ 落库 4840（用户的值不被覆盖）")
    void templateKeepsExplicitPort() throws Exception {
        DeviceTemplateEntity t = new DeviceTemplateEntity();
        Map<String, Object> body = new HashMap<>();
        body.put("protocol", "opcua");
        body.put("port", 4840);

        applyCommon(t, body);

        assertEquals(4840, t.getPort(), "用户填的端口必须原样落库");
    }

    /**
     * 模板套用到设备上的端口链路。
     * <p>
     * <b>后端没有「套用模板到设备」的接口</b> —— 模板是前端填进表单、再提交 DeviceUpsert 的。
     * 所以「模板端口 null/0 → 设备端口按协议兜底」这条链的落点是
     * {@code DeviceController.apply}：它必须把 <b>0 也当成「没填」</b>，
     * 否则前端提交 0 时，设备会落库 0 → 采集时靠驱动兜底并打一条「上游漏了解析」的 warn。
     */
    @Test
    @DisplayName("模板端口是 0/null 时，套用到 OPC UA 设备上 ⇒ 设备端口按协议兜底成 4840")
    void templatePortUnspecifiedFallsBackByProtocolOnDevice() throws Exception {
        DeviceEntity fromTemplate = new DeviceEntity();
        create(fromTemplate, req("opcua", 0));       // 前端把模板的 0 填进端口栏再提交
        assertEquals(4840, fromTemplate.getPort(),
                "端口是 0（未指定）时，设备端口应按协议兜底成 4840，不能落库 0");

        DeviceEntity fromNull = new DeviceEntity();
        create(fromNull, req("opcua", null));
        assertEquals(4840, fromNull.getPort(), "端口是 null 时同样按协议兜底");
    }

    @Test
    @DisplayName("模板端口没填 → 落库 null（= 没填），不能偷偷填成 502")
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
