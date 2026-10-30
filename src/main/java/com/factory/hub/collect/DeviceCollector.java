package com.factory.hub.collect;

import com.factory.hub.driver.DriverContext;
import com.factory.hub.driver.ProtocolDriver;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointDef;
import com.factory.hub.model.PointValue;
import com.factory.hub.web.WsHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单台设备的采集器。一个实例 = 一个线程 = 一台设备。
 * <p>
 * 设计要点，全是现场踩出来的：
 * 1. 每台设备独立线程、独立超时 —— 一台掉线不拖累其他
 * 2. 出错只影响自己，断开重连 + 退避，不退出循环
 * 3. 采集周期按"上次开始到现在"算，不会因为读得慢而漂移
 * 4. 只推送变化的值，前端不会被打爆
 * 5. 读和写共用一把锁 —— RTU 是半双工，读写撞一起必然乱码
 */
public class DeviceCollector implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeviceCollector.class);

    private final DeviceEntity device;
    private final List<PointDef> points;
    private final ProtocolDriver driver;
    private final DriverContext ctx;
    private final SnapshotStore store;
    private final WsHandler ws;
    private final ObjectMapper mapper;
    private final com.factory.hub.report.ProdRollup rollup;

    private final ReentrantLock ioLock = new ReentrantLock();
    private volatile boolean running = true;
    /** 连续失败次数，成功一次清零。用于判断是否真离线 + 计算退避 */
    private int failures = 0;
    private static final java.util.Random RND = new java.util.Random();
    private final Map<String, PointValue> lastPushed = new LinkedHashMap<>();
    private Boolean lastOnline = null;

    public DeviceCollector(DeviceEntity device,
                           List<PointDef> points,
                           ProtocolDriver driver,
                           DriverContext ctx,
                           SnapshotStore store,
                           WsHandler ws,
                           ObjectMapper mapper,
                           com.factory.hub.report.ProdRollup rollup) {
        this.device = device;
        this.points = points;
        this.driver = driver;
        this.ctx = ctx;
        this.store = store;
        this.ws = ws;
        this.mapper = mapper;
        this.rollup = rollup;
        this.store.runtime(device.getCode()).protocol = device.getProtocol();
    }

    public String deviceCode() {
        return device.getCode();
    }

    public List<PointDef> points() {
        return points;
    }

    public void stop() {
        running = false;
    }

    /** 远程下发：改配方、复位、启停。和采集共用一把锁，避免半双工冲突 */
    public boolean write(PointDef point, Object value) {
        ioLock.lock();
        try {
            if (!driver.isConnected()) {
                driver.connect(ctx);
            }
            driver.write(ctx, point, value);
            log.info("[{}] 下发成功 {} = {}", device.getCode(), point.getKey(), value);
            return true;
        } catch (Exception e) {
            log.warn("[{}] 下发失败 {}: {}", device.getCode(), point.getKey(), e.getMessage());
            return false;
        } finally {
            ioLock.unlock();
        }
    }

    @Override
    public void run() {
        log.info("[{}] 采集启动，协议={}，点数={}，周期={}ms",
                device.getCode(), device.getProtocol(), points.size(), device.getPollIntervalMs());

        while (running) {
            long start = System.currentTimeMillis();
            try {
                Map<String, Object> raw;
                ioLock.lock();
                try {
                    if (!driver.isConnected()) {
                        driver.connect(ctx);
                        log.info("[{}] 已连接", device.getCode());
                    }
                    raw = driver.read(ctx, points);
                } finally {
                    ioLock.unlock();
                }

                long now = System.currentTimeMillis();

                Map<String, PointValue> values = new LinkedHashMap<>();
                for (PointDef p : points) {
                    if (raw.containsKey(p.getKey())) {
                        values.put(p.getKey(), PointValue.good(device.getCode(), p, raw.get(p.getKey()), now));
                    } else {
                        values.put(p.getKey(), PointValue.bad(device.getCode(), p, now));
                    }
                }

                long cost = System.currentTimeMillis() - start;
                store.update(device.getCode(), values, cost);
                pushValues(values);
                pushStatus(true, null);
                failures = 0;

            } catch (Exception e) {
                failures++;
                // 无线链路丢一个包是常事。只失败一次就标离线，设备会在页面上闪红，
                // 运行人员很快学会"红色不用管"——真出事那天也没人看。
                // 所以连续失败到阈值才判离线；之前只是重试。
                if (failures >= device.getOfflineAfterFails()) {
                    pushStatus(false, e.getMessage());
                    store.markOffline(device.getCode(), e.getMessage());
                    log.warn("[{}] 采集失败 {} 次，判定离线: {}", device.getCode(), failures, e.getMessage());
                } else {
                    log.debug("[{}] 采集失败第 {} 次（未达离线阈值），重试: {}",
                            device.getCode(), failures, e.getMessage());
                }
                ioLock.lock();
                try {
                    driver.disconnect();
                } catch (Exception ignored) {
                } finally {
                    ioLock.unlock();
                }
                sleepQuietly(backoffMs(failures));
                continue;
            }

            long cost = System.currentTimeMillis() - start;
            long wait = Math.max(50, device.getPollIntervalMs() - cost);
            sleepQuietly(wait);
        }

        driver.disconnect();
        log.info("[{}] 采集停止", device.getCode());
    }

    private void pushValues(Map<String, PointValue> values) {
        List<PointValue> changed = new ArrayList<>();
        for (PointValue v : values.values()) {
            PointValue prev = lastPushed.get(v.pointKey());
            if (!v.sameAs(prev)) {
                changed.add(v);
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        for (PointValue v : changed) {
            lastPushed.put(v.pointKey(), v);
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "values");
        msg.put("deviceCode", device.getCode());
        msg.put("items", changed);
        // 今日产量一起推：卡片上的「今日产量」只有跟着推送走才会秒级跟涨
        msg.put("todayOutput", rollup.todayOutputOf(device.getCode()));
        broadcast(msg);
    }

    private void pushStatus(boolean online, String error) {
        if (lastOnline != null && lastOnline == online && error == null) {
            return;
        }
        lastOnline = online;

        SnapshotStore.Runtime rt = store.runtime(device.getCode());
        Map<String, Object> data = new HashMap<>();
        data.put("deviceCode", device.getCode());
        data.put("online", online);
        data.put("error", error);
        data.put("lastOkTs", rt.lastOkTs);
        data.put("successCount", rt.successCount);
        data.put("failCount", rt.failCount);
        data.put("costMs", rt.lastCostMs);

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "status");
        msg.put("ts", System.currentTimeMillis());
        msg.put("data", data);
        broadcast(msg);
    }

    private void broadcast(Map<String, Object> msg) {
        try {
            ws.broadcast(mapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.debug("推送失败: {}", e.getMessage());
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /**
     * 断线重连退避：1s → 2s → 4s → 8s → 16s → 30s 封顶，再加 0~30% 随机抖动。
     * <p>
     * 为什么要抖动：车间交换机重启或无线 AP 掉线，几百台设备会同时断、同时重连。
     * 固定间隔重连等于把恢复瞬间变成一次小型 SYN 洪水，设备侧缓冲区直接打满，
     * 结果是"网络已经好了，系统还要再花几分钟才恢复"。
     * 抖动让它们错开，总恢复时间反而更短。
     */
    private long backoffMs(int fails) {
        int shift = Math.min(Math.max(fails - 1, 0), 5);
        long base = Math.min(30_000L, 1000L << shift);
        long jitter = (long) (base * 0.3 * RND.nextDouble());
        return base + jitter;
    }
}
