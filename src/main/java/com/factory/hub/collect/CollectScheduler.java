package com.factory.hub.collect;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.DriverRegistry;
import com.factory.hub.driver.ProtocolDriver;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.service.DeviceService;
import com.factory.hub.web.WsHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 采集调度器：负责按设备起停采集线程。
 * <p>
 * 核心是「增量同步」，不是「全部重启」：
 * 加一台设备只启动这一台，改一台只重启这一台，删一台只停这一台。
 * 几百台设备的厂里，加设备时其他设备的数据不能断——所以绝不能 stopAll。
 */
@Component
public class CollectScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectScheduler.class);

    private final DeviceService deviceService;
    private final DriverRegistry driverRegistry;
    private final SnapshotStore store;
    private final WsHandler wsHandler;
    private final ObjectMapper mapper;
    private final com.factory.hub.report.ProdRollup rollup;

    /** deviceCode -> 正在跑的采集任务 */
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    /** 一台设备正在跑的采集实例 + 它的配置指纹 */
    private static final class Task {
        final DeviceCollector collector;
        final Thread thread;
        final String fingerprint;

        Task(DeviceCollector collector, Thread thread, String fingerprint) {
            this.collector = collector;
            this.thread = thread;
            this.fingerprint = fingerprint;
        }
    }

    public CollectScheduler(DeviceService deviceService,
                            DriverRegistry driverRegistry,
                            SnapshotStore store,
                            WsHandler wsHandler,
                            ObjectMapper mapper,
                            com.factory.hub.report.ProdRollup rollup
                            ) {
        this.deviceService = deviceService;
        this.driverRegistry = driverRegistry;
        this.store = store;
        this.wsHandler = wsHandler;
        this.mapper = mapper;
        this.rollup = rollup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reload();
    }

    /**
     * 增量同步：只启动新增的、只重启配置变了的、只停掉已删除或已禁用的。
     * 没变化的设备连接不动，采集不中断。
     */
    public synchronized void reload() {
        List<DeviceEntity> devices = deviceService.enabledDevices();
        Set<String> wanted = new HashSet<>();
        int added = 0, restarted = 0, removed = 0, failed = 0;

        for (DeviceEntity d : devices) {
            String code = d.getCode();
            wanted.add(code);
            String fp = fingerprint(d);
            Task old = tasks.get(code);

            if (old != null && old.fingerprint.equals(fp)) {
                continue;               // 配置没变，一根手指都不动它
            }
            if (old != null) {
                stopTask(code);         // 配置变了，只重启这一台
                restarted++;
            } else {
                added++;
            }
            try {
                startTask(d, fp);
            } catch (Exception e) {
                failed++;
                log.error("设备 {} 启动失败: {}", code, e.getMessage());
                store.markOffline(code, e.getMessage());
            }
        }

        for (String code : new ArrayList<>(tasks.keySet())) {
            if (!wanted.contains(code)) {
                stopTask(code);
                removed++;
            }
        }

        log.info("采集同步完成：启用 {} 台，新增 {}，配置变更 {}，移除 {}，失败 {}，当前运行 {} 台",
                devices.size(), added, restarted, removed, failed, tasks.size());
    }

    private void startTask(DeviceEntity d, String fp) {
        List<PointDef> points = deviceService.pointsOf(d);
        if (points.isEmpty()) {
            throw new IllegalStateException("点表为空，无法采集");
        }
        ProtocolDriver driver = driverRegistry.create(d.getProtocol());
        DriverContext ctx = deviceService.contextOf(d);

        DeviceCollector collector = new DeviceCollector(
                d, points, driver, ctx, store, wsHandler, mapper, rollup);

        Thread t = new Thread(collector, "collect-" + d.getCode());
        t.setDaemon(true);

        // 先放进去再 start，避免线程已经跑起来但表里还没有
        tasks.put(d.getCode(), new Task(collector, t, fp));
        t.start();
    }

    /** 停一台，并等它真正退出（释放串口/socket），否则立刻重连会撞车 */
    private void stopTask(String code) {
        Task task = tasks.remove(code);
        if (task == null) {
            return;
        }
        task.collector.stop();
        task.thread.interrupt();
        try {
            task.thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 配置指纹：任何会影响采集行为的字段变了就重启这一台。
     * 改个设备名字、换个位置不该重启采集，所以只算连接参数和点表。
     */
    private String fingerprint(DeviceEntity d) {
        String s = String.join("|",
                str(d.getProtocol()), str(d.getHost()), str(d.getPort()),
                str(d.getSerialPort()), str(d.getBaudRate()), str(d.getDataBits()),
                str(d.getStopBits()), str(d.getParity()), str(d.getUnitId()),
                str(d.getPollIntervalMs()), str(d.getPointsJson()));
        return Integer.toHexString(s.hashCode());
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 远程下发一个点，返回是否成功 */
    public boolean write(String deviceCode, String pointKey, Object value) {
        Task t = tasks.get(deviceCode);
        if (t == null) {
            return false;
        }
        return t.collector.points().stream()
                .filter(p -> pointKey.equals(p.getKey()))
                .findFirst()
                .map(p -> t.collector.write(p, value))
                .orElse(false);
    }

    public Set<String> running() {
        return tasks.keySet();
    }

    public int runningCount() {
        return tasks.size();
    }

    @PreDestroy
    public void shutdown() {
        List<Task> all = new ArrayList<>(tasks.values());
        tasks.clear();
        // 先统一发停止信号，再统一等，避免串行等待拖慢关机
        for (Task t : all) {
            t.collector.stop();
            t.thread.interrupt();
        }
        for (Task t : all) {
            try {
                t.thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!all.isEmpty()) {
            log.info("采集已全部停止，共 {} 台", all.size());
        }
    }
}
