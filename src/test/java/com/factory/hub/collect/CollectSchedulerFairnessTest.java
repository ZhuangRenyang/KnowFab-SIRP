package com.factory.hub.collect;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.DriverRegistry;
import com.factory.hub.driver.ProtocolDriver;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.web.WsHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 采集调度的**公平性**护栏：池大小远小于设备数时，每台设备都该被采到。
 *
 * <p><b>为什么这条必须钉住</b>：共享线程池把「每设备一线程」改成「共享 N 个线程」之后，
 * 出现了一个<b>新形态的饿死</b>：调度不公平时，某些设备可能一直排不上队，
 * 表现为「大部分设备数据正常，个别设备永远不更新」。
 * <p>这种缺陷<b>不会让任何测试变红</b>——
 * 「设备总数没错」「线程数没涨」「快照里有数据」这些断言全都照样过，
 * 因为<b>被饿死的恰恰是那几个设备</b>，而断言只看总数。
 * <p>⇒ 这里断言的是<b>每台设备的轮数</b>，而不是加总。
 * 这正是「断言太松 = 自己伪造绿灯」的典型：加总 &gt; 0 会掩盖掉个别设备的饿死。
 */
class CollectSchedulerFairnessTest {

    private CollectScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("50 台设备、4 个工作线程 ⇒ 每台设备的采集轮数一致（没有设备被饿死）")
    void everyDeviceGetsItsTurn() throws Exception {
        int devices = 50;
        int poolSize = 4;

        com.factory.hub.service.DeviceService deviceService = mock(com.factory.hub.service.DeviceService.class);
        List<DeviceEntity> list = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            list.add(device(String.format("DEV-%03d", i)));
        }
        when(deviceService.enabledDevices()).thenReturn(list);
        when(deviceService.pointsOf(any())).thenReturn(onePoint());
        when(deviceService.contextOf(any())).thenReturn(new DriverContext());

        DriverRegistry registry = mock(DriverRegistry.class);
        ProtocolDriver drv = countingDriver();
        when(registry.create(anyString())).thenReturn(drv);

        SnapshotStore store = new SnapshotStore();
        WsHandler ws = mock(WsHandler.class);
        com.factory.hub.report.ProdRollup rollup = mock(com.factory.hub.report.ProdRollup.class);

        scheduler = new CollectScheduler(deviceService, registry, store, ws, new ObjectMapper(), rollup, poolSize);
        scheduler.reload();

        // 让采集跑一段时间（周期 60ms ⇒ 1 秒能跑十几轮，足够看出分配是否均匀）
        Thread.sleep(2000);

        // ---- 逐台断言：轮数不能差太多 ----
        int min = Integer.MAX_VALUE;
        int max = 0;
        int zero = 0;
        for (DeviceEntity d : list) {
            SnapshotStore.Runtime rt = store.runtime(d.getCode());
            long n = rt.successCount;
            if (n == 0) {
                zero++;
            }
            min = (int) Math.min(min, n);
            max = (int) Math.max(max, n);
        }

        assertEquals(0, zero,
                "有 " + zero + " 台设备一轮都没被采集到 —— 它们被饿死了"
                        + "（调度不公平：共享池里总有个别设备排不上队）");
        // 允许 1 轮的差异（轮次边界不齐是正常的），但不能差一半
        assertTrue(max - min <= 1,
                "各设备采集轮数差异过大：最小=" + min + " 最大=" + max
                        + "（差 " + (max - min) + " 轮）⇒ 调度不公平，有设备被长期饿死");
        assertTrue(max >= 3,
                "采集轮数太少（最大只有 " + max + " 轮）⇒ 采集可能压根没跑起来"
                        + "（别把这当成「公平性通过」）");
    }

    private static DeviceEntity device(String code) {
        DeviceEntity d = new DeviceEntity();
        d.setCode(code);
        d.setName(code);
        d.setProtocol("modbus-tcp");
        d.setHost("127.0.0.1");
        d.setPort(502);
        d.setPollIntervalMs(60);
        d.setOfflineAfterFails(3);
        d.setTimeoutMs(200);
        d.setEnabled(true);
        return d;
    }

    private static List<PointDef> onePoint() {
        PointDef p = new PointDef();
        p.setKey("p1");
        return List.of(p);
    }

    /**
     * 只返回固定值的假驱动。
     * <p>⚠️ 这里**故意**返回常量值：本测试只关心「每台设备有没有被采到」，
     * 不关心推送路径 ⇒ 不需要递增值。
     * （递增值是 {@link CollectScaleBench} 的要求，两者目的不同，别混用。）
     */
    private static ProtocolDriver countingDriver() throws Exception {
        ProtocolDriver driver = mock(ProtocolDriver.class);
        when(driver.type()).thenReturn("modbus-tcp");
        when(driver.isConnected()).thenReturn(true);
        when(driver.read(any(), any())).thenAnswer(inv -> Map.of("p1", 1.0));
        return driver;
    }
}
