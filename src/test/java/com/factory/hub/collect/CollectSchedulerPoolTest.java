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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 采集调度的护栏：**线程数与设备数量无关**。
 *
 * <p>为什么这条必须钉住：以前是「一台设备一个常驻线程」
 * ⇒ 上千台设备 = 上千个线程，而每轮采集实际只占几毫秒。
 * 线程数 × 栈内存是 OOM 的头号原因（Windows 线程栈 1MB、Linux 64 位默认预留 8MB）。
 *
 * <p>⚠️ 这类改动最容易被「顺手改回去」——
 * 「一台设备一个线程」写起来更直观，也不用管错峰、退避、排队。
 * ⇒ 所以这里用**行为断言**（真跑起来数线程）而不是只断言代码长什么样。
 */
class CollectSchedulerPoolTest {

    private CollectScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private static DeviceEntity device(String code) {
        DeviceEntity d = new DeviceEntity();
        d.setCode(code);
        d.setName(code);
        d.setProtocol("modbus-tcp");
        d.setHost("127.0.0.1");
        d.setPort(502);
        d.setPollIntervalMs(60);          // 周期短一点，让错峰在一小会儿内铺开
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

    /** 一个只会返回固定值的假驱动（不碰网络） */
    private static ProtocolDriver fakeDriver() throws Exception {
        ProtocolDriver driver = mock(ProtocolDriver.class);
        when(driver.type()).thenReturn("modbus-tcp");
        when(driver.isConnected()).thenReturn(true);
        when(driver.read(any(), any())).thenAnswer(inv -> {
            Map<String, Object> raw = new HashMap<>();
            raw.put("p1", 1.0);
            return raw;
        });
        return driver;
    }

    @Test
    @DisplayName("50 台设备、4 个工作线程 ⇒ 实际线程数不超过 4（线程数与设备数量无关）")
    void threadCountDoesNotGrowWithDeviceCount() throws Exception {
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
        // ⚠️ 先把 driver 建好再 stub：fakeDriver() 内部自己也在 when(...)，
        //   嵌在 thenReturn(...) 的参数里求值会触发 Mockito 的 UnfinishedStubbing。
        ProtocolDriver drv = fakeDriver();
        when(registry.create(anyString())).thenReturn(drv);

        SnapshotStore store = new SnapshotStore();
        WsHandler ws = mock(WsHandler.class);
        com.factory.hub.report.ProdRollup rollup = mock(com.factory.hub.report.ProdRollup.class);

        scheduler = new CollectScheduler(deviceService, registry, store, ws, new ObjectMapper(), rollup, poolSize);
        scheduler.reload();

        assertEquals(devices, scheduler.runningCount(),
                "所有设备都应该被排上采集任务（任务数 = 设备数，与线程数无关）");

        // 等一小会儿，让线程真正被创建出来
        Thread.sleep(600);

        // ⚠️ **数的是所有采集相关线程**，不是只数 "collect-pool" ——
        //   第一版只数 collect-pool，结果把「每设备一个线程」写回去（线程名是
        //   collect-DEV-xxx）它照样绿 ⇒ **断言没覆盖它声称要证明的那件事**。
        //   ⇒ 现在按前缀 collect 数**全部**采集线程，池线程与每设备线程都算在内。
        int collectThreads = 0;
        int poolThreads = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!t.getName().startsWith("collect") || !t.isAlive()) {
                continue;
            }
            collectThreads++;
            if (t.getName().startsWith("collect-pool")) {
                poolThreads++;
            }
        }
        assertTrue(collectThreads <= poolSize,
                "采集线程总数必须 <= 池大小（" + poolSize + "），实际 " + collectThreads
                + "（其中池线程 " + poolThreads + "）—— 设备数 " + devices + " 台，"
                + "若线程数跟着涨，说明又退回「一台设备一个线程」了");

        // 反向对照：线程数**不是** 0 ⇒ 说明任务真的在跑，不是「什么都没采集」
        assertTrue(collectThreads > 0, "采集线程一个都没有，说明任务压根没跑起来");
        assertFalse(store.latest("DEV-000").isEmpty(),
                "设备应该真的被采到了（不能只是「线程数对了但什么也没采」）");
    }

    @Test
    @DisplayName("任务结构里不再有「每设备一个线程」的字段")
    void taskHasNoPerDeviceThread() {
        // 结构护栏：Task 里若又出现 Thread 字段，说明有人把老写法抄回来了
        Class<?> taskClass = null;
        for (Class<?> c : CollectScheduler.class.getDeclaredClasses()) {
            if ("Task".equals(c.getSimpleName())) {
                taskClass = c;
            }
        }
        assertTrue(taskClass != null, "找不到 CollectScheduler.Task 内部类");
        for (Field f : taskClass.getDeclaredFields()) {
            assertFalse(Thread.class.equals(f.getType()),
                    "Task 里不该再有 Thread 字段（" + f.getName() + "）—— "
                    + "那是「一台设备一个常驻线程」的老写法，设备上千台时就是上千个线程");
        }
    }

    @Test
    @DisplayName("失败时走退避（不丢语义），成功时按周期减去耗时")
    void failureKeepsBackoffAndSuccessUsesPollInterval() throws Exception {
        // 退避语义是现场踩出来的：断线重连 1s→2s→4s→8s→16s→30s 封顶 + 抖动
        // 抽成 collectOnce 之后**最容易丢的就是这条** ⇒ 钉住它。
        DeviceEntity d = device("DEV-BACKOFF");
        d.setPollIntervalMs(1000);

        ProtocolDriver failing = mock(ProtocolDriver.class);
        when(failing.type()).thenReturn("modbus-tcp");
        when(failing.isConnected()).thenReturn(true);
        when(failing.read(any(), any())).thenThrow(new RuntimeException("模拟读失败"));

        DeviceCollector collector = new DeviceCollector(
                d, onePoint(), failing, new DriverContext(),
                new SnapshotStore(), mock(WsHandler.class), new ObjectMapper(),
                mock(com.factory.hub.report.ProdRollup.class));

        collector.collectOnce();
        long delay = collector.nextDelayMs(0);
        assertTrue(delay >= 1000,
                "失败后必须退避（至少 1 秒），实际 " + delay
                + " —— 退避丢了会让掉线的设备疯狂重连，把网线和 CPU 一起打爆");
        assertTrue(delay <= 30_000 + 9_000,
                "退避上限是 30 秒 + 30% 抖动，实际 " + delay);
    }
}
