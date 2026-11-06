package com.factory.hub.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.factory.hub.driver.modbus.ModbusRtuOverTcpDriver;
import com.factory.hub.driver.modbus.ModbusTcpDriver;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.service.DeviceService;

/**
 * 「协议 → 默认端口」的口径钉死。
 * <p>
 * <b>守的是什么坑</b>：502 是 <b>Modbus</b> 的端口，却曾被写成协议无关的全局默认，
 * 散落在实体、控制器、上下文等 5 处（前端还有 4 处）。后果：
 * <ul>
 *   <li>新建一台 OPC UA 设备不填端口 ⇒ 落库 502 ⇒ 驱动去连 502 ⇒ <b>必然连不上</b>；</li>
 *   <li>每接一个新协议就要改一遍那几处，漏改的那个协议就去连错端口
 *       （OPC UA 就是这么漏的）；</li>
 *   <li>前端预填 4840 只是<b>遮住</b>了 UI 路径 —— API 建设备、脚本、老数据仍是 502。</li>
 * </ul>
 * <p>
 * <b>现在的口径</b>：全局语义统一成「<b>未填 = null/0</b>」，
 * 「默认端口是多少」由<b>各协议自己的驱动</b>回落：
 * Modbus TCP / RTU-over-TCP = 502、OPC UA = 4840。
 * ⇒ 加第三个协议不用再动任何全局常量。
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
    @DisplayName("设备没填端口 ⇒ 落库为 null（不替用户决定），上下文里是 0（未指定）")
    void unfilledPortStaysUnfilled() {
        assertNull(new DeviceEntity().getPort(),
                "新建设备的端口默认应为 null（= 没填），不能是 502 —— "
                        + "那是 Modbus 的端口，OPC UA 用 502 必然连不上");

        DriverContext ctx = ctxOf("opcua", null);
        assertEquals(0, ctx.getPort(),
                "没填端口时上下文里应是 0（= 未指定），由各协议自己的驱动回落默认端口");
    }

    @Test
    @DisplayName("用户显式填的端口不被覆盖")
    void explicitPortIsKept() {
        DriverContext ctx = ctxOf("opcua", 4841);
        assertEquals(4841, ctx.getPort(), "用户填的端口必须原样传下去，不能被默认值覆盖");
    }

    @Test
    @DisplayName("protocol 为 null 也不 NPE（端口照样是 0 = 未指定）")
    void nullProtocolDoesNotBreak() {
        DriverContext ctx = ctxOf(null, null);
        assertEquals(0, ctx.getPort(), "protocol 为 null 时不应抛异常，端口应为 0（未指定）");
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
        assertEquals(1502, ModbusRtuOverTcpDriver.resolvePort(explicit),
                "用户填了端口就用填的，不能被默认值覆盖");
    }

    @Test
    @DisplayName("已填端口时驱动用用户填的值，不用默认值")
    void explicitPortWinsOverDefault() {
        DriverContext ctx = ctxOf("modbus-tcp", 1502);
        assertEquals(1502, ctx.getPort(), "用户填 1502 就该连 1502");
        assertTrue(ctx.getPort() > 0, "已填的端口是有效值（> 0），驱动的回落分支不该触发");
    }
}
