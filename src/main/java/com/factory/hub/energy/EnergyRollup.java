package com.factory.hub.energy;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.demand.DemandTracker;
import com.factory.hub.model.PointValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 能耗汇总引擎：把电表读数变成「每小时 × 每个电价时段」的度数和电费。
 * <p>
 * <b>为什么不挂在采集线程里算：</b>
 * 采集线程的职责就是稳定地把数据读回来，一秒一次，几百个线程在跑。
 * 往里塞统计逻辑，一旦统计代码抛异常或者变慢，整条采集链路跟着抖。
 * 所以这里用独立的定时任务，每秒扫一次快照把电量「捡」进来。
 * 采集和统计彻底解耦，互不影响。
 * <p>
 * <b>为什么要按小时切：</b>
 * 电价是分时段的，算钱必须知道这一点电量是几点用的。按小时切正好对齐
 * 国内的整点分时电价，而且数据量从「每秒一条」降到「每台每小时 4 条以内」。
 */
@Component
public class EnergyRollup {

    private static final Logger log = LoggerFactory.getLogger(EnergyRollup.class);

    private static final long HOUR_MS = 3_600_000L;

    /** 小时数据保留天数。留 400 天能跨年做同比，再老的自动删掉 */
    private static final int RETAIN_DAYS = 400;

    /** 时段没覆盖到的时间产生的电量归到这里，钱记 0 */
    public static final String SEG_UNPRICED = "UNPRICED";

    private final SnapshotStore store;
    private final TariffStore tariffStore;
    private final EnergyRepository repo;
    private final DemandTracker demandTracker;

    /** key = deviceCode|pointKey */
    private final Map<String, EnergyAccumulator> accs = new ConcurrentHashMap<>();

    /** 当前小时的累加桶，key = deviceCode|segType */
    private final Map<String, Bucket> current = new LinkedHashMap<>();

    private long currentHour = -1L;
    private long lastCleanupDay = -1L;
    private final Set<String> warnedUnpriced = ConcurrentHashMap.newKeySet();

    /** 一小时里某个电价时段的累计量 */
    private static final class Bucket {
        double kwh;
        double cost;
        double backfill;
    }

    public EnergyRollup(SnapshotStore store, TariffStore tariffStore, EnergyRepository repo,
                        DemandTracker demandTracker) {
        this.store = store;
        this.tariffStore = tariffStore;
        this.repo = repo;
        this.demandTracker = demandTracker;
    }

    /**
     * 每秒扫一次。用 fixedDelay 而不是 fixedRate——
     * 万一某次扫描卡了（比如数据库慢），下一次从这次结束开始算，不会堆积。
     */
    @Scheduled(fixedDelay = 1000L)
    public synchronized void scan() {
        long now = System.currentTimeMillis();
        long hour = now - (now % HOUR_MS);

        if (currentHour < 0) {
            currentHour = hour;
        } else if (hour != currentHour) {
            // 整点：上一小时的数据定稿，写库
            persist(currentHour);
            current.clear();
            currentHour = hour;
            log.debug("能耗整点结算完成：{}", Instant.ofEpochMilli(currentHour - HOUR_MS));
        }

        TariffPlan plan = tariffStore.current();

        for (Map.Entry<String, Map<String, PointValue>> e : store.entries()) {
            String code = e.getKey();
            for (PointValue pv : e.getValue().values()) {
                if (!"energy".equals(pv.role()) || !pv.isGood()) {
                    continue;
                }
                Double raw = pv.asDouble();
                if (raw == null) {
                    continue;
                }

                EnergyAccumulator acc = accs.computeIfAbsent(code + "|" + pv.pointKey(),
                        k -> new EnergyAccumulator());
                EnergyAccumulator.Delta d = acc.feed(pv.ts(), raw, pv.unit());
                if (!d.counted()) {
                    continue;
                }

                // 15 分钟需量切片。基本电费按它收，跟小时统计是两条口径，各自独立累计。
                // 断线补偿的电量不能进需量窗口（否则一个窗口塞进两小时电量，
                // 峰值翻几倍，客户会照着假峰值去申请扩容）
                demandTracker.feed(code, pv.ts(), d.kwh(), d.backfill());

                // 按读数时刻归属时段。采集周期是秒级，跨越整点的那一笔
                // 最多偏差一个周期，对电费的影响可以忽略。
                TariffSegment seg = plan.segmentAt(pv.ts());
                String segType;
                double price;
                if (seg == null) {
                    segType = SEG_UNPRICED;
                    price = 0d;
                    if (warnedUnpriced.add(code + "|" + pv.pointKey())) {
                        log.warn("[{}] 电量点 {} 的读数时刻没有对应的电价时段，这部分电量不计价。"
                                + "请到「能耗分析 → 电价设置」检查时段是否覆盖 24 小时", code, pv.pointKey());
                    }
                } else {
                    segType = seg.getType();
                    price = seg.getPrice();
                }

                Bucket b = current.computeIfAbsent(code + "|" + segType, k -> new Bucket());
                b.kwh += d.kwh();
                b.cost += d.kwh() * price;
                if (d.backfill()) {
                    b.backfill += d.kwh();
                }
            }
        }

        cleanupAccumulators();
        maybeCleanupOldData(now);
    }

    /**
     * 每 10 分钟把当前小时的数据落一次库。
     * 不落的话，服务重启会丢掉「本小时还没到整点」的那部分电量，
     * 最多丢 10 分钟，比丢 1 小时好。
     */
    @Scheduled(fixedDelay = 600_000L)
    public synchronized void checkpoint() {
        if (currentHour > 0) {
            persist(currentHour);
        }
    }

    /** 把内存桶写成数据库行。同一小时重复写会更新已有行，不会重复插入 */
    private void persist(long hourStart) {
        if (current.isEmpty()) {
            return;
        }
        Map<String, EnergyRecord> existing = new HashMap<>();
        for (EnergyRecord r : repo.findByHourStart(hourStart)) {
            existing.put(r.getDeviceCode() + "|" + r.getSegType(), r);
        }

        List<EnergyRecord> rows = new ArrayList<>();
        for (Map.Entry<String, Bucket> e : current.entrySet()) {
            Bucket b = e.getValue();
            if (b.kwh <= 0d) {
                continue;
            }
            int idx = e.getKey().indexOf('|');
            if (idx <= 0) {
                continue;
            }
            String code = e.getKey().substring(0, idx);
            String seg = e.getKey().substring(idx + 1);
            double price = b.cost / b.kwh;

            EnergyRecord r = existing.get(e.getKey());
            if (r == null) {
                r = new EnergyRecord(code, hourStart, seg, b.kwh, price, b.backfill);
            } else {
                r.setKwh(b.kwh);
                r.setPrice(price);
                r.setBackfillKwh(b.backfill);
            }
            rows.add(r);
        }
        if (rows.isEmpty()) {
            return;
        }
        try {
            repo.saveAll(rows);
        } catch (Exception ex) {
            log.error("能耗数据写库失败（内存数据保留，下次重试）: {}", ex.getMessage());
        }
    }

    /**
     * 设备删掉之后，它的增量计算器还留在内存里。
     * 不清理的话，长期运行会一直涨（虽然不多，但这种泄漏不能留）。
     */
    private void cleanupAccumulators() {
        if (accs.size() <= store.deviceCodes().size() * 12 + 64) {
            return;                       // 没明显膨胀，不值得每秒都扫一遍
        }
        Set<String> alive = new HashSet<>(store.deviceCodes());
        int before = accs.size();
        accs.keySet().removeIf(k -> {
            int idx = k.indexOf('|');
            return idx <= 0 || !alive.contains(k.substring(0, idx));
        });
        if (before != accs.size()) {
            log.debug("清理已删除设备的增量计算器 {} 个", before - accs.size());
        }
    }

    /** 每天清一次过期数据，别让库无限长 */
    private void maybeCleanupOldData(long now) {
        LocalDate today = LocalDate.now();
        long dayKey = today.toEpochDay();
        if (lastCleanupDay == dayKey) {
            return;
        }
        lastCleanupDay = dayKey;
        long before = now - RETAIN_DAYS * 24L * HOUR_MS;
        try {
            int n = repo.deleteOlderThan(before);
            if (n > 0) {
                log.info("清理 {} 天前的小时能耗数据 {} 行", RETAIN_DAYS, n);
            }
        } catch (Exception e) {
            log.warn("清理历史能耗数据失败: {}", e.getMessage());
        }
    }

    /* ==================== 给查询层用的实时视图 ==================== */

    public long currentHourStart() {
        return currentHour;
    }

    /** 当前这一小时全厂各时段的实时电量（还没落库的那部分） */
    public synchronized Map<String, double[]> liveBySegment() {
        Map<String, double[]> out = new HashMap<>();
        for (Map.Entry<String, Bucket> e : current.entrySet()) {
            String seg = e.getKey().substring(e.getKey().indexOf('|') + 1);
            double[] arr = out.computeIfAbsent(seg, k -> new double[2]);
            arr[0] += e.getValue().kwh;
            arr[1] += e.getValue().cost;
        }
        return out;
    }

    /** 当前这一小时各设备的实时电量 */
    public synchronized Map<String, double[]> liveByDevice() {
        Map<String, double[]> out = new HashMap<>();
        for (Map.Entry<String, Bucket> e : current.entrySet()) {
            String code = e.getKey().substring(0, e.getKey().indexOf('|'));
            double[] arr = out.computeIfAbsent(code, k -> new double[2]);
            arr[0] += e.getValue().kwh;
            arr[1] += e.getValue().cost;
        }
        return out;
    }

    /** 单台设备的增量器状态，前端用来提示点表可能配错了 */
    public EnergyAccumulator accumulator(String deviceCode, String pointKey) {
        return accs.get(deviceCode + "|" + pointKey);
    }

    public long zoneOffsetHours() {
        return ZoneId.systemDefault().getRules()
                .getOffset(Instant.now()).getTotalSeconds() / 3600L;
    }
}
