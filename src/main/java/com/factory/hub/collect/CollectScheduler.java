package com.factory.hub.collect;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.DriverRegistry;
import com.factory.hub.driver.ProtocolDriver;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.service.DeviceService;
import com.factory.hub.web.WsHandler;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    /**
     * 一台设备正在跑的采集任务。
     * <p>
     * ⚠️ 以前这里是「一台设备一个常驻线程」（thread 字段）——
     * 上千台设备就是上千个线程，而每轮采集实际只占几毫秒。
     * ⇒ 现在改成：任务本身只是一次「采集一轮」，跑完自己排下一轮，
     * 所有任务共享一个固定大小的线程池。
     */
    private final class Task implements Runnable {
        final DeviceCollector collector;
        final String fingerprint;
        /** 当前这一轮的 future（自调度会不断换新） */
        volatile ScheduledFuture<?> future;
        /** 已停止：不再排下一轮 */
        volatile boolean stopped;

        Task(DeviceCollector collector, String fingerprint) {
            this.collector = collector;
            this.fingerprint = fingerprint;
        }

        /** 排一轮。延迟由上一次的结果决定（成功=周期-耗时，失败=退避） */
        void schedule(long delayMs) {
            if (stopped) {
                return;
            }
            try {
                future = pool.schedule(this, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // 池已关闭（关机中）⇒ 忽略，不要在关机路径上抛异常
                if (!pool.isShutdown()) {
                    log.warn("设备 {} 排下一轮失败: {}", collector.deviceCode(), e.getMessage());
                }
            }
        }

        @Override
        public void run() {
            if (stopped || !collector.isRunning()) {
                return;
            }
            long cost;
            try {
                cost = collector.collectOnce();
            } catch (Throwable t) {
                // collectOnce 内部已经兜住了采集异常；到这里说明是别的问题
                // （比如某台设备的点表有脏数据）⇒ 绝不能让它把线程池的线程带走
                log.warn("设备 {} 采集轮次异常: {}", collector.deviceCode(), t.toString());
                cost = 0;
            }
            if (stopped) {
                return;
            }
            schedule(collector.nextDelayMs(cost));
        }
    }

    /** 采集线程池：大小固定，与设备数量无关 */
    private final ScheduledThreadPoolExecutor pool;

    public CollectScheduler(DeviceService deviceService,
                            DriverRegistry driverRegistry,
                            SnapshotStore store,
                            WsHandler wsHandler,
                            ObjectMapper mapper,
                            com.factory.hub.report.ProdRollup rollup,
                            @Value("${collect.threads:32}") int poolSize
                            ) {
        this.deviceService = deviceService;
        this.driverRegistry = driverRegistry;
        this.store = store;
        this.wsHandler = wsHandler;
        this.mapper = mapper;
        this.rollup = rollup;
        this.pool = new ScheduledThreadPoolExecutor(Math.max(1, poolSize), r -> {
            Thread t = new Thread(r, "collect-pool");
            t.setDaemon(true);
            return t;
        });
        // ⚠️ 取消的任务必须从延迟队列里移除：上千个已取消的任务堆着，
        // 内存与调度延迟都受影响（设备反复改配置时会累积）。
        this.pool.setRemoveOnCancelPolicy(true);
        log.info("采集线程池已就绪：{} 个工作线程（设备数量不受此限制）", Math.max(1, poolSize));
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

        Task task = new Task(collector, fp);
        // 先放进去再排第一轮，避免任务已经跑起来但表里还没有
        tasks.put(d.getCode(), task);
        // ⚠️ **首次执行要错峰**：上千台设备若在同一时刻到期，全挤在队列里，
        //   轮询周期会被拉长（看起来像「设备变慢了」）。
        //   按设备编码哈希把首次执行铺开在一个采集周期内
        //   （同一台设备的相位稳定，不会因为 reload 顺序变化而跳变）。
        int workers = Math.max(1, pool.getCorePoolSize());
        int phase = Math.floorMod(d.getCode().hashCode(), workers);
        long poll = Math.max(50, d.getPollIntervalMs());
        task.schedule((long) phase * Math.max(1, poll / workers));
    }

    /** 停一台，并等它真正退出（释放串口/socket），否则立刻重连会撞车 */
    private void stopTask(String code) {
        Task task = tasks.remove(code);
        if (task == null) {
            return;
        }
        // ① 先标记停止 ⇒ 正在跑的那一轮结束后不会再排下一轮
        task.stopped = true;
        // ② 取消当前这一轮（还没开始的直接取消；已在跑的让它跑完）
        ScheduledFuture<?> f = task.future;
        if (f != null) {
            f.cancel(false);
        }
        // ③ 让采集器自己收尾（它会在当前轮结束后断开连接并退出）
        task.collector.stop();
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
            t.stopped = true;
            ScheduledFuture<?> f = t.future;
            if (f != null) {
                f.cancel(false);
            }
            t.collector.stop();
        }
        // 再关线程池：正在跑的那一轮最多再跑一个采集周期，不会拖住关机
        pool.shutdownNow();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!all.isEmpty()) {
            log.info("采集已全部停止，共 {} 台", all.size());
        }
    }
}
