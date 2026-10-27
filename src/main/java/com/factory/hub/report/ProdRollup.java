package com.factory.hub.report;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.model.PointValue;
import com.factory.hub.service.DeviceService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产汇总引擎：把「每台设备当前是运行还是停机」「累计产量读数」变成
 * 「每小时各状态占多少秒、出了多少件」。
 * <p>
 * 和能耗统计一样，独立定时任务每秒扫一遍快照，不挂在采集线程里——
 * 采集线程只负责把数据读回来，统计逻辑一旦出错不能把采集带崩。
 * <p>
 * <b>这个模块最容易算错的地方，按踩坑顺序：</b>
 * <ol>
 *   <li><b>停机时间算成运行时间</b>——必须用「上一次状态持续到这一次采样」的区间归属，
 *       而不是「这次采到什么就记一秒」。设备 1 秒采样一次，靠 ts 差值算秒数，
 *       不然丢一次采样就少记一秒，一天下来差几个小时。</li>
 *   <li><b>掉线期间瞎记</b>——设备离线两小时再上线，两次采样 ts 差 7200 秒，
 *       直接记进某一小时会把那小时填满。所以间隔超过 {@link #MAX_GAP_MS} 一律不记时长，
 *       那段空白留在「未记录」里，看报表的人一眼能看出数据不连续。</li>
 *   <li><b>产量计数器清零</b>——换班清零、换表清零，读数变小当成「负数产量」会把
 *       整班产量抹掉。跟能耗一样处理：读数变小就记 0，只统计 resets 次数。</li>
 *   <li><b>产量算到错误的小时</b>——断线重连时一次性补记的产量，归到重连那一小时，
 *       并标记 backfill，让客户知道这小时的数字为什么突然高一截。</li>
 * </ol>
 */
@Component
public class ProdRollup {

    private static final Logger log = LoggerFactory.getLogger(ProdRollup.class);

    private static final long HOUR_MS = 3_600_000L;

    /** 两次采样间隔超过这个值就认为中间断过，不补记时长（正常采集周期 1~5 秒） */
    private static final long MAX_GAP_MS = 15_000L;

    /** 数据保留天数 */
    private static final int RETAIN_DAYS = 400;

    private final SnapshotStore store;
    private final DeviceService deviceService;
    private final ProdHourlyRepository repo;

    /** key = deviceCode|hourStart，值是该小时该设备的累计 */
    private final Map<String, ProdHourly> buckets = new ConcurrentHashMap<>();

    /** 每台设备的上一笔采样，用来算时间差和产量增量 */
    private final Map<String, DevState> states = new ConcurrentHashMap<>();

    private long lastCleanupDay = -1L;

    private static final class DevState {
        long statusTs = 0;
        int status = -1;
        long countTs = 0;
        double count = -1;
        long resets = 0;
    }

    public ProdRollup(SnapshotStore store, DeviceService deviceService, ProdHourlyRepository repo) {
        this.store = store;
        this.deviceService = deviceService;
        this.repo = repo;
    }

    /**
     * 每秒扫一次。fixedDelay 而不是 fixedRate：某次卡了不堆积。
     */
    @Scheduled(fixedDelay = 1000L)
    public synchronized void scan() {
        long now = System.currentTimeMillis();

        for (Map.Entry<String, Map<String, PointValue>> e : store.entries()) {
            String code = e.getKey();
            Map<String, PointValue> points = e.getValue();
            if (points == null || points.isEmpty()) {
                continue;
            }
            DevState st = states.computeIfAbsent(code, k -> new DevState());

            PointValue statusPv = pick(points, "status");
            if (statusPv != null) {
                long ts = statusPv.ts();
                Double sv = statusPv.asDouble();
                int cur = sv == null ? -1 : (int) Math.round(sv);
                if (st.statusTs > 0 && ts > st.statusTs && st.status >= 0) {
                    long dt = ts - st.statusTs;
                    if (dt > 0 && dt <= MAX_GAP_MS) {
                        // 用「上一笔状态」占住这段区间：状态是在这次采样才变的，
                        // 中间那段时间设备还是老状态
                        bucket(code, st.statusTs).addStatus(st.status, dt / 1000d);
                    }
                }
                st.status = cur;
                st.statusTs = ts;
            }

            PointValue countPv = pick(points, "count");
            Double cv = countPv == null ? null : countPv.asDouble();
            if (countPv != null && cv != null) {
                long ts = countPv.ts();
                double cur = cv;
                if (st.count >= 0 && ts > st.countTs) {
                    double delta = cur - st.count;
                    boolean backfill = (ts - st.countTs) > MAX_GAP_MS;
                    if (delta < 0) {
                        // 换班清零 / 换表。记 0，不能记负数
                        st.resets++;
                        delta = 0;
                    }
                    if (delta > 0) {
                        ProdHourly p = bucket(code, ts);
                        p.setOutput(p.getOutput() + delta);
                        if (backfill) {
                            p.setBackfill(p.getBackfill() + delta);
                        }
                    }
                }
                st.count = cur;
                st.countTs = ts;
            }
        }

        cleanupStates();
        maybeCleanupOldData(now);
    }

    /**
     * 每分钟落一次库。
     * <p>
     * 不做「整点才落」：客户在 10:59 打开报表看到 10 点那行是空的会以为系统坏了。
     * 提前落库，当前小时的数字也在，只是还会继续涨。
     */
    @Scheduled(fixedDelay = 60_000L)
    public synchronized void checkpoint() {
        flush(true);
    }

    /** 关机前把内存里的数据写掉，最多丢 60 秒 */
    @PreDestroy
    public synchronized void onShutdown() {
        flush(true);
    }

    private void flush(boolean dropClosedHours) {
        if (buckets.isEmpty()) {
            return;
        }
        long currentHour = System.currentTimeMillis();
        currentHour -= currentHour % HOUR_MS;

        Map<Long, Map<String, ProdHourly>> byHour = new HashMap<>();
        for (Map.Entry<String, ProdHourly> e : buckets.entrySet()) {
            byHour.computeIfAbsent(e.getValue().getHourStart(), k -> new LinkedHashMap<>())
                    .put(e.getKey(), e.getValue());
        }

        for (Map.Entry<Long, Map<String, ProdHourly>> hourEntry : byHour.entrySet()) {
            long hour = hourEntry.getKey();
            Map<String, ProdHourly> existing = new HashMap<>();
            try {
                for (ProdHourly r : repo.findByHourStart(hour)) {
                    existing.put(r.getDeviceCode(), r);
                }
            } catch (Exception ex) {
                log.warn("读取小时生产数据失败（{}），本轮跳过这一小时", ex.getMessage());
                continue;
            }
            List<ProdHourly> rows = new ArrayList<>();
            for (Map.Entry<String, ProdHourly> e : hourEntry.getValue().entrySet()) {
                ProdHourly cur = e.getValue();
                if (cur.getDeviceCode() == null) {
                    continue;                 // 只有被写过的桶才有设备码
                }
                ProdHourly old = existing.get(cur.getDeviceCode());
                if (old != null) {
                    // 内存里是这一小时的全量累计，直接覆盖
                    old.setDeviceName(cur.getDeviceName());
                    old.setRunSec(cur.getRunSec());
                    old.setIdleSec(cur.getIdleSec());
                    old.setStopSec(cur.getStopSec());
                    old.setAlarmSec(cur.getAlarmSec());
                    old.setUnknownSec(cur.getUnknownSec());
                    old.setOutput(cur.getOutput());
                    old.setBackfill(cur.getBackfill());
                    rows.add(old);
                } else {
                    rows.add(cur);
                }
            }
            if (rows.isEmpty()) {
                continue;
            }
            try {
                repo.saveAll(rows);
            } catch (Exception ex) {
                log.error("小时生产数据写库失败（内存保留，下轮重试）: {}", ex.getMessage());
                continue;
            }
            if (dropClosedHours && hour < currentHour) {
                // 已经过去的小时不会再变，从内存里扔掉，防止长期运行越攒越多
                Iterator<Map.Entry<String, ProdHourly>> it = buckets.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue().getHourStart() < currentHour) {
                        it.remove();
                    }
                }
            }
        }
    }

    /** 取某个时刻所属小时、某台设备的累计对象，没有就建一个 */
    private ProdHourly bucket(String deviceCode, long ts) {
        long hour = ts - (ts % HOUR_MS);
        String key = deviceCode + "|" + hour;
        ProdHourly p = buckets.get(key);
        if (p != null) {
            return p;
        }
        return buckets.computeIfAbsent(key, k -> {
            ProdHourly n = new ProdHourly(deviceCode, deviceName(deviceCode), hour);
            return n;
        });
    }

    private String deviceName(String code) {
        try {
            var d = deviceService.byCode(code);
            if (d != null && d.getName() != null) {
                return d.getName();
            }
        } catch (Exception ignored) {
        }
        return code;
    }

    private PointValue pick(Map<String, PointValue> points, String role) {
        PointValue fallback = null;
        for (PointValue pv : points.values()) {
            if (!pv.isGood()) {
                continue;
            }
            if (role.equals(pv.role())) {
                return pv;
            }
            if (fallback == null && pv.asDouble() != null) {
                fallback = pv;
            }
        }
        return null;   // 没有对应的角色就返回 null，绝不拿别的点凑数
    }

    /** 设备被删掉后状态还留在内存里，长期运行会涨，定期清 */
    private void cleanupStates() {
        Set<String> alive = new HashSet<>(store.deviceCodes());
        if (states.size() <= alive.size() * 3 + 32) {
            return;
        }
        states.keySet().removeIf(k -> !alive.contains(k));
    }

    private void maybeCleanupOldData(long now) {
        java.time.LocalDate today = java.time.LocalDate.now();
        long dayKey = today.toEpochDay();
        if (lastCleanupDay == dayKey) {
            return;
        }
        lastCleanupDay = dayKey;
        long before = now - RETAIN_DAYS * 24L * HOUR_MS;
        try {
            int n = repo.deleteOlderThan(before);
            if (n > 0) {
                log.info("清理 {} 天前的小时生产数据 {} 行", RETAIN_DAYS, n);
            }
        } catch (Exception e) {
            log.warn("清理历史生产数据失败: {}", e.getMessage());
        }
    }
}
