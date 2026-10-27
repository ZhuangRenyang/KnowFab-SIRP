package com.factory.hub.demand;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 15 分钟需量累计器。
 * <p>
 * 电费单上的「最大需量」是当月所有 15 分钟窗口里最大的那个平均功率，
 * 所以必须先把电量按 15 分钟切片。切片由 {@link com.factory.hub.energy.EnergyRollup}
 * 每秒算出的电量增量喂进来——不另起一个扫描线程，避免同一份快照被扫两遍。
 * <p>
 * <b>三个会让需量算错的坑：</b>
 * <ol>
 *   <li><b>断线补偿的电量不能计需量。</b>设备离线两小时再上线，一次读数带回来 2 小时的电量，
 *       全塞进当前 15 分钟窗口，算出来是真实负荷的 8 倍。月峰值一旦被这条假数据顶上去，
 *       客户可能据此去申请扩容，白花钱——而且他不会知道是你的软件算错了。
 *       这类电量在 {@code backfill=true} 时直接丢弃。</li>
 *   <li><b>窗口必须对齐整 15 分钟</b>（:00 :15 :30 :45），不能从服务启动时刻开始切。
 *       否则重启一次，切分点全乱，峰值也跟电费单对不上。</li>
 *   <li><b>多个电量点会重复计。</b>一台设备挂「总表 + 分表」两个 energy 点时，
 *       需量会加两遍。现场正确做法是一台设备只配一个进线电量点，
 *       分表配到别的设备上（README 第八节有说明）。</li>
 * </ol>
 */
@Component
public class DemandTracker {

    private static final Logger log = LoggerFactory.getLogger(DemandTracker.class);

    private static final long WINDOW_MS = 900_000L;      // 15 分钟

    /** 需量窗口保留天数。13 个月够做同比，再多没意义 */
    private static final int RETAIN_DAYS = 400;

    private final DemandWindowRepository repo;

    /** key = deviceCode|windowStart → 这窗口累计电量 */
    private final Map<String, Double> live = new ConcurrentHashMap<>();

    private long lastCleanupDay = -1L;

    public DemandTracker(DemandWindowRepository repo) {
        this.repo = repo;
    }

    /**
     * 喂一笔电量增量。
     *
     * @param backfill 断线补偿的电量，true 时不计入需量（见类注释第 1 条）
     */
    public void feed(String deviceCode, long ts, double kwh, boolean backfill) {
        if (deviceCode == null || kwh <= 0 || backfill) {
            return;
        }
        long win = ts - (ts % WINDOW_MS);
        live.merge(deviceCode + "|" + win, kwh, Double::sum);
    }

    /** 当前窗口的实时需量（kW），前端「当前负荷」用 */
    public double currentWindowKw(String deviceCode, long now) {
        long win = now - (now % WINDOW_MS);
        double sum = 0;
        for (Map.Entry<String, Double> e : live.entrySet()) {
            int idx = e.getKey().lastIndexOf('|');
            if (idx <= 0) {
                continue;
            }
            if (Long.parseLong(e.getKey().substring(idx + 1)) != win) {
                continue;
            }
            if (deviceCode != null && !deviceCode.isEmpty()
                    && !deviceCode.equals(e.getKey().substring(0, idx))) {
                continue;
            }
            sum += e.getValue();
        }
        // 窗口还没走完，按已过时间折算成平均功率更接近电费单口径；
        // 但折算会把「刚过 1 分钟就用了 10 度」放大成 600kW 的假峰值，
        // 所以只做保守处理：按整 15 分钟算，宁可显示偏低也不制造假警报
        return sum * 4d;
    }

    public long currentWindowStart(long now) {
        return now - (now % WINDOW_MS);
    }

    /** 每分钟把当前窗口落一次库，服务重启最多丢 1 分钟 */
    @Scheduled(fixedDelay = 60_000L)
    public synchronized void checkpoint() {
        flush();
    }

    @PreDestroy
    public synchronized void onShutdown() {
        flush();
    }

    private void flush() {
        if (live.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long currentWin = now - (now % WINDOW_MS);

        Map<Long, Map<String, Double>> byWin = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : live.entrySet()) {
            int idx = e.getKey().lastIndexOf('|');
            if (idx <= 0) {
                continue;
            }
            long win = Long.parseLong(e.getKey().substring(idx + 1));
            byWin.computeIfAbsent(win, k -> new LinkedHashMap<>())
                    .put(e.getKey().substring(0, idx), e.getValue());
        }

        for (Map.Entry<Long, Map<String, Double>> winEntry : byWin.entrySet()) {
            long win = winEntry.getKey();
            Map<String, DemandWindow> existing = new HashMap<>();
            try {
                for (DemandWindow w : repo.findByWindowStart(win)) {
                    existing.put(w.getDeviceCode(), w);
                }
            } catch (Exception ex) {
                log.warn("读取需量窗口失败（{}），本轮跳过", ex.getMessage());
                continue;
            }
            List<DemandWindow> rows = new ArrayList<>();
            for (Map.Entry<String, Double> e : winEntry.getValue().entrySet()) {
                DemandWindow w = existing.get(e.getKey());
                if (w == null) {
                    w = new DemandWindow(e.getKey(), win, e.getValue());
                } else {
                    w.setKwh(e.getValue());
                }
                rows.add(w);
            }
            if (rows.isEmpty()) {
                continue;
            }
            try {
                repo.saveAll(rows);
            } catch (Exception ex) {
                log.error("需量窗口写库失败（内存保留）: {}", ex.getMessage());
                continue;
            }
            if (win < currentWin) {
                // 已经过去的窗口不会再变，从内存里去掉
                Iterator<String> it = live.keySet().iterator();
                while (it.hasNext()) {
                    String k = it.next();
                    int idx = k.lastIndexOf('|');
                    if (idx > 0 && Long.parseLong(k.substring(idx + 1)) < currentWin) {
                        it.remove();
                    }
                }
            }
        }
        maybeCleanup(now);
    }

    private void maybeCleanup(long now) {
        long dayKey = java.time.LocalDate.now().toEpochDay();
        if (lastCleanupDay == dayKey) {
            return;
        }
        lastCleanupDay = dayKey;
        try {
            int n = repo.deleteOlderThan(now - RETAIN_DAYS * 24L * 3_600_000L);
            if (n > 0) {
                log.info("清理 {} 天前的需量窗口 {} 行", RETAIN_DAYS, n);
            }
        } catch (Exception e) {
            log.warn("清理历史需量数据失败: {}", e.getMessage());
        }
    }

    /** 测试/排查用：当前内存里有多少个未落库窗口 */
    public int liveWindowCount() {
        return live.size();
    }
}
