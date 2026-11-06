package com.factory.hub.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.factory.hub.driver.modbus.ModbusRtuOverTcpDriver;
import com.factory.hub.driver.modbus.ModbusTcpDriver;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.DeviceTemplateEntity;
import com.factory.hub.service.DeviceService;

/**
 * 「协议 → 默认端口」的口径钉死（口径只定义在 {@link ProtocolDefaults} 一处）。
 * <p>
 * <b>守的是什么坑</b>：502 是 <b>Modbus</b> 的端口，却曾被写成协议无关的全局默认，
 * 散落在实体、控制器、模板、上下文、大屏等 9 处。后果：
 * <ul>
 *   <li>新建一台 OPC UA 设备不填端口 ⇒ 落库 502 ⇒ 驱动去连 502 ⇒ <b>必然连不上</b>；</li>
 *   <li>每接一个新协议就要改一遍那几处，漏改的那个协议就去连错端口
 *       （OPC UA 就是这次漏的那个）；</li>
 *   <li>前端预填 4840 只是<b>遮住</b>了 UI 路径 —— API 建设备、脚本、老数据仍是 502。</li>
 * </ul>
 */
class DevicePortDefaultTest {

    private DriverContext ctxOf(String protocol, Integer port) {
        DeviceEntity d = new DeviceEntity();
        d.setProtocol(protocol);
        d.setPort(port);
        // contextOf 只读实体，不碰 repo/mapper，所以传 null 依赖不会出事
        return new DeviceService(null, null).contextOf(d);
    }

    @Test
    @DisplayName("口径只有一处：opcua → 4840，其余 → 502")
    void protocolDefaultsAreDefinedOnce() {
        assertEquals(4840, ProtocolDefaults.defaultPort("opcua"));
        assertEquals(502, ProtocolDefaults.defaultPort("modbus-tcp"));
        assertEquals(502, ProtocolDefaults.defaultPort("modbus-rtu-tcp"));
        assertEquals(502, ProtocolDefaults.defaultPort("modbus-rtu"));
        assertEquals(502, ProtocolDefaults.defaultPort("sim"));
        assertEquals(502, ProtocolDefaults.defaultPort(null), "protocol 为 null 要有确定的兜底，不能 NPE");
        assertEquals(4840, ProtocolDefaults.defaultPort("OPCUA"), "大小写/空格不该影响判定");
        assertEquals(4840, ProtocolDefaults.defaultPort("  opcua  "));
        assertNotEquals(ProtocolDefaults.MODBUS_TCP_PORT, ProtocolDefaults.OPC_UA_PORT,
                "两个协议的默认端口不该是同一个数（否则这个映射就没意义了）");
    }

    @Test
    @DisplayName("设备没填端口 ⇒ 实体 null，上下文里是按协议解析好的默认端口")
    void unfilledPortStaysUnfilled() {
        assertNull(new DeviceEntity().getPort(),
                "新建设备的端口默认应为 null（= 没填），不能是 502 —— "
                        + "那是 Modbus 的端口，OPC UA 用 502 必然连不上");
        assertNull(new DeviceTemplateEntity().getPort(),
                "新建模板的端口默认应为 null，同上");

        assertEquals(4840, ctxOf("opcua", null).getPort(),
                "OPC UA 设备没填端口 ⇒ 采集时用 4840");
        assertEquals(502, ctxOf("modbus-tcp", null).getPort(),
                "Modbus 设备没填端口 ⇒ 采集时用 502");
    }

    @Test
    @DisplayName("用户显式填的端口不被覆盖")
    void explicitPortIsKept() {
        assertEquals(4841, ctxOf("opcua", 4841).getPort(),
                "用户填的端口必须原样传下去，不能被默认值覆盖");
        assertEquals(1502, ctxOf("modbus-tcp", 1502).getPort());
    }

    @Test
    @DisplayName("protocol 为 null 也不 NPE（端口按 502 兜底）")
    void nullProtocolDoesNotBreak() {
        assertEquals(502, ctxOf(null, null).getPort(),
                "protocol 为 null 时不应抛异常，端口应有确定兜底");
    }

    @Test
    @DisplayName("Modbus 两个驱动：端口没填时回落到 502，填了就用填的")
    void modbusDriversApplyTheirOwnDefaultPort() {
        DriverContext unfilled = new DriverContext();
        unfilled.setPort(0);
        assertEquals(502, ModbusTcpDriver.resolvePort(unfilled),
                "Modbus TCP 没填端口时应回落到 502");
        assertEquals(502, ModbusRtuOverTcpDriver.resolvePort(unfilled),
                "Modbus RTU over TCP 没填端口时应回落到 502");

        DriverContext explicit = new DriverContext();
        explicit.setPort(1502);
        assertEquals(1502, ModbusTcpDriver.resolvePort(explicit),
                "用户填了端口就用填的，不能被默认值覆盖");
        assertEquals(1502, ModbusRtuOverTcpDriver.resolvePort(explicit));
    }

    @Test
    @DisplayName("已填端口时驱动用用户填的值，不用默认值")
    void explicitPortWinsOverDefault() {
        DriverContext ctx = ctxOf("modbus-tcp", 1502);
        assertEquals(1502, ctx.getPort(), "用户填 1502 就该连 1502");
        assertTrue(ctx.getPort() > 0, "已填的端口是有效值（> 0），驱动的回落分支不该触发");
    }
}
