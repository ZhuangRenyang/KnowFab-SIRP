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
 *   <li><b>断线补偿的电量要摊回它真正发生的窗口，不能全塞进当前窗口，也不能整段丢弃。</b>
 *       设备离线两小时再上线，一次读数带回来 2 小时的电量。
 *       全塞进当前 15 分钟窗口，算出来是真实负荷的 8 倍——月峰值一旦被这条假数据顶上去，
 *       客户可能据此去申请扩容，白花钱，而且他不会知道是你的软件算错了。
 *       整段丢弃则是另一个方向错：断网若恰逢当月用电高峰，那个峰值在需量里<strong>完全不存在</strong>，
 *       月最大需量被低估 ⇒ 基本电费少算，更糟的是<strong>超容告警不会响</strong>，
 *       客户被供电局按实际峰值加收时，我们一句提醒都没有。
 *       <p>
 *       现在的做法是<b>按时间摊平</b>：份额 = 电量 × 本窗口内重叠时长 / 总跨度，
 *       并用 {@code estimated} 标记这些窗口（见 {@link DemandWindow#isEstimated()}）。
 *       摊平得到的是「这段的平均功率」，均匀摊平会把峰拉平，所以方向偏保守、不会造出假峰值。
 *       跨度超过 {@link #MAX_SPREAD_MS} 就丢弃——离线 3 天摊到 288 个窗口填同一个平均值，
 *       那不是数据，是伪造，客户拿它对账必然对不上。</li>
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

    /**
     * 断线电量最多摊多长（4 小时 = 16 个窗口）。
     * <p>
     * 超过这个跨度就不摊了，直接丢弃 + 记日志。理由：离线 3 天摊到 288 个窗口、
     * 每格填同一个平均值，得到的「月最大需量」等于「月平均负荷」——客户拿去跟电费单
     * 对账必然对不上，而且错得很隐蔽。4 小时以内，工厂负荷通常还在一个班次里，
     * 波动不大，摊平的峰值误差是个位数百分比。
     * <p>
     * 写死不做配置：客户真需要更长，先看他的采集为什么断这么久——那是要修的故障，
     * 不是要配的参数。
     */
    private static final long MAX_SPREAD_MS = 4 * 3_600_000L;

    /** 需量窗口保留天数。13 个月够做同比，再多没意义 */
    private static final int RETAIN_DAYS = 400;

    private final DemandWindowRepository repo;

    /** key = deviceCode|windowStart → 这窗口累计电量 */
    private final Map<String, Double> live = new ConcurrentHashMap<>();

    /**
     * 含摊平（推算）电量的窗口 key —— flush 时据此写 {@code estimated=true}。
     * <p>
     * 只增不减的语义很重要：落库后再读回来时这个集合是空的（重启），
     * 但库里那一行已经是 true。所以 flush 只「补写 true」，绝不「写回 false」，
     * 否则重启一次就把推算标记全抹掉了——那就成了无从追溯的假数据。
     */
    private final java.util.Set<String> estimated = ConcurrentHashMap.newKeySet();

    private long lastCleanupDay = -1L;

    public DemandTracker(DemandWindowRepository repo) {
        this.repo = repo;
    }

    /**
     * 喂一笔电量增量。
     *
     * @param fromTs   这段电量的起点（上一次读数的时刻）。只有 backfill 时才用得上，
     *                 正常采集传什么都行（会被忽略，因为全部记入 toTs 所在窗口）
     * @param toTs     本次读数时刻
     * @param backfill 断线补读回来的电量（跨过了采集间隔）。true 时按时间摊平，见类注释第 1 条
     */
    public void feed(String deviceCode, long fromTs, long toTs, double kwh, boolean backfill) {
        if (deviceCode == null || kwh <= 0) {
            return;
        }
        if (!backfill) {
            addTo(deviceCode, toTs - (toTs % WINDOW_MS), kwh, false);
            return;
        }

        // ---- 断线补偿：摊回它真正覆盖的每个窗口 ----
        long span = toTs - fromTs;
        if (fromTs <= 0L || span <= 0L) {
            // 没有起点就摊不了。宁可丢弃也不要瞎猜归属（猜错就是假峰值）
            log.warn("需量摊平跳过：缺少有效时间跨度（设备 {}，from={}，to={}，{} kWh）",
                    deviceCode, fromTs, toTs, round1(kwh));
            return;
        }
        if (span > MAX_SPREAD_MS) {
            log.warn("需量摊平跳过：断线 {} 分钟超过上限 {} 分钟（设备 {}，{} kWh）。"
                            + "这部分电量未计入需量，本月最大需量可能偏低；"
                            + "请检查该设备的采集为何断这么久",
                    span / 60_000L, MAX_SPREAD_MS / 60_000L, deviceCode, round1(kwh));
            return;
        }

        long win = fromTs - (fromTs % WINDOW_MS);
        double assigned = 0d;
        for (; win < toTs; win += WINDOW_MS) {
            // 窗口与本段的重叠时长 → 按比例分。首尾两个窗口通常只拿到一部分
            long lo = Math.max(win, fromTs);
            long hi = Math.min(win + WINDOW_MS, toTs);
            if (hi <= lo) {
                continue;
            }
            double share = kwh * (double) (hi - lo) / (double) span;
            if (share > 0d) {
                addTo(deviceCode, win, share, true);
                assigned += share;
            }
        }
        // 浮点余数塞进最后一个窗口，保证总电量守恒（否则窗口 kWh 之和会少于实际用电量）
        double rest = kwh - assigned;
        if (rest > 1e-9) {
            addTo(deviceCode, (toTs - 1L) - ((toTs - 1L) % WINDOW_MS), rest, true);
        }
    }

    /** 累加到某个窗口（摊平和正常采集共用一个入口，基线继承只在这里做） */
    private void addTo(String deviceCode, long win, double kwh, boolean est) {
        String key = deviceCode + "|" + win;
        // 重启后内存窗口从零开始，而 flush 是覆盖写——先把库里这一窗口已落的量继承过来，
        // 否则重启前的电量会被零值冲掉（本月最大需量被低估 → 基本电费少算，
        // 需量告警也可能不触发，峰值本已越限却没人知道）。
        if (!live.containsKey(key)) {
            live.putIfAbsent(key, windowBaseline(deviceCode, win));
        }
        live.merge(key, kwh, Double::sum);
        if (est) {
            estimated.add(key);
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }

    /** 这一窗口已落库的电量（重启后继承用；查不到就当从零开始） */
    private double windowBaseline(String deviceCode, long win) {
        try {
            for (DemandWindow w : repo.findByWindowStart(win)) {
                if (deviceCode.equals(w.getDeviceCode())) {
                    return w.getKwh();
                }
            }
        } catch (Exception e) {
            log.warn("读取需量窗口基线失败（{}），本窗口从零开始累计", e.getMessage());
        }
        return 0d;
    }

    /** 当前窗口的实时需量（kW），前端「当前负荷」用 */
    /**
     * 当前窗口功率，只累加指定的一组设备。
     * <p>
     * 进线口径用这个：全厂需量只该累加总表，机床的电已经包含在总表里了，
     * 一起加就是重复计算（演示库实测虚高 3.85 倍）。
     *
     * @param onlyDevices null 或空集合 = 不限制（所有设备）
     */
    public double currentWindowKw(String deviceCode, long now,
                                  java.util.Set<String> onlyDevices) {
        double sum = 0;
        long win = now - (now % WINDOW_MS);
        for (Map.Entry<String, Double> e : live.entrySet()) {
            int idx = e.getKey().lastIndexOf('|');
            if (idx <= 0) {
                continue;
            }
            if (Long.parseLong(e.getKey().substring(idx + 1)) != win) {
                continue;
            }
            String dev = e.getKey().substring(0, idx);
            if (deviceCode != null && !deviceCode.isEmpty() && !deviceCode.equals(dev)) {
                continue;
            }
            if (onlyDevices != null && !onlyDevices.isEmpty() && !onlyDevices.contains(dev)) {
                continue;
            }
            sum += e.getValue();
        }
        // 窗口还没走完，按已过时间折算成平均功率更接近电费单口径；
        // 但折算会把「刚过 1 分钟就用了 10 度」放大成 600kW 的假峰值，
        // 所以只做保守处理：按整 15 分钟算，宁可显示偏低也不制造假警报
        return sum * 4d;
    }

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
                String code = e.getKey();
                boolean est = estimated.contains(code + "|" + win);
                DemandWindow w = existing.get(code);
                if (w == null) {
                    w = new DemandWindow(code, win, e.getValue(), est);
                } else {
                    w.setKwh(e.getValue());
                    // 只补写 true，绝不写回 false：重启后 estimated 集合是空的，
                    // 但库里那一行已经是 true —— 无条件 set(est) 会把推算标记全抹掉
                    if (est) {
                        w.setEstimated(true);
                    }
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
        }

        // 内存清理必须放在整个循环**之后**：放在里面会在处理第一个窗口时
        // 就把后面几个窗口的内存条目（连同 estimated 标记）一起删掉 ——
        // 而它们还没轮到写库，于是推算标记丢失，客户分不清哪个数是算出来的。
        // 已经过去的窗口不会再变，落完库就可以从内存去掉。
        Iterator<String> it = live.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            int idx = k.lastIndexOf('|');
            if (idx > 0 && Long.parseLong(k.substring(idx + 1)) < currentWin) {
                it.remove();
                estimated.remove(k);      // 标记已落库，别在内存里越攒越多
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
