package com.factory.hub.energy;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointValue;
import com.factory.hub.service.DeviceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能耗查询。
 * <p>
 * 所有查询都要拼两块数据：数据库里已结算的小时数据 + 内存里当前这一小时的实时值。
 * 拆开的原因见 {@link EnergyRollup}：统计任务每 10 分钟才落一次库，
 * 但客户点开页面必须看到「到现在为止」的数，不能等到整点。
 * <p>
 * 上界统一用 {@code currentHourStart}（不含），保证不重复计算。
 */
@Service
public class EnergyService {

    private final EnergyRepository repo;
    private final EnergyRollup rollup;
    private final TariffStore tariffStore;
    private final SnapshotStore snapshot;
    private final DeviceService deviceService;

    public EnergyService(EnergyRepository repo,
                         EnergyRollup rollup,
                         TariffStore tariffStore,
                         SnapshotStore snapshot,
                         DeviceService deviceService) {
        this.repo = repo;
        this.rollup = rollup;
        this.tariffStore = tariffStore;
        this.snapshot = snapshot;
        this.deviceService = deviceService;
    }

    /**
     * 首页那几个大数字 + 峰谷分布 + 日趋势。
     *
     * @param days 趋势显示最近几天
     */
    public Map<String, Object> overview(int days) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long todayStart = startOf(today, zone);
        long monthStart = startOf(today.withDayOfMonth(1), zone);
        long trendStart = startOf(today.minusDays(Math.max(1, days) - 1L), zone);
        long cut = upperBound();

        // ---- 当前小时的实时量 ----
        Map<String, double[]> liveSeg = rollup.liveBySegment();
        double liveKwh = 0d;
        double liveCost = 0d;
        for (double[] a : liveSeg.values()) {
            liveKwh += a[0];
            liveCost += a[1];
        }
        long liveHour = rollup.currentHourStart();
        boolean liveToday = liveHour >= todayStart;
        boolean liveMonth = liveHour >= monthStart;

        // ---- 今日 / 本月 ----
        double[] t = total(todayStart, cut);
        double[] m = total(monthStart, cut);
        if (liveToday) {
            t[0] += liveKwh;
            t[1] += liveCost;
        }
        if (liveMonth) {
            m[0] += liveKwh;
            m[1] += liveCost;
        }

        // ---- 峰谷分布 ----
        Map<String, double[]> segMap = new HashMap<>();
        for (Object[] r : repo.sumBySegment(todayStart, cut)) {
            segMap.put(String.valueOf(r[0]), new double[]{d(r[1]), d(r[2])});
        }
        if (liveToday) {
            for (Map.Entry<String, double[]> e : liveSeg.entrySet()) {
                double[] a = segMap.computeIfAbsent(e.getKey(), k -> new double[2]);
                a[0] += e.getValue()[0];
                a[1] += e.getValue()[1];
            }
        }
        TariffPlan plan = tariffStore.current();
        Map<String, Double> priceOf = new HashMap<>();
        for (TariffSegment s : plan.getSegments()) {
            priceOf.putIfAbsent(s.getType(), s.getPrice());
        }

        List<Map<String, Object>> segments = new ArrayList<>();
        double segTotal = 0d;
        for (double[] a : segMap.values()) {
            segTotal += a[0];
        }
        for (String type : TariffPlan.TYPES) {
            double[] a = segMap.getOrDefault(type, new double[2]);
            segments.add(segRow(type, TariffPlan.typeName(type), a, priceOf.getOrDefault(type, 0d), segTotal));
        }
        double[] un = segMap.get(EnergyRollup.SEG_UNPRICED);
        if (un != null && un[0] > 0) {
            segments.add(segRow(EnergyRollup.SEG_UNPRICED, "未计价", un, 0d, segTotal));
        }

        // ---- 日趋势 ----
        Map<LocalDate, double[]> byDay = new LinkedHashMap<>();
        for (int i = Math.max(1, days) - 1; i >= 0; i--) {
            byDay.put(today.minusDays(i), new double[2]);
        }
        for (Object[] r : repo.sumByHour(trendStart, cut)) {
            LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(l(r[0])), zone);
            double[] a = byDay.get(day);
            if (a != null) {
                a[0] += d(r[1]);
                a[1] += d(r[2]);
            }
        }
        if (liveToday) {
            double[] a = byDay.get(today);
            if (a != null) {
                a[0] += liveKwh;
                a[1] += liveCost;
            }
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map.Entry<LocalDate, double[]> e : byDay.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", e.getKey().toString());
            row.put("kwh", round(e.getValue()[0], 2));
            row.put("cost", round(e.getValue()[1], 2));
            trend.add(row);
        }

        // ---- 当前负荷 ----
        double power = currentPower();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todayKwh", round(t[0], 2));
        out.put("todayCost", round(t[1], 2));
        out.put("monthKwh", round(m[0], 2));
        out.put("monthCost", round(m[1], 2));
        out.put("power", round(power, 2));
        out.put("segments", segments);
        out.put("trend", trend);
        out.put("tariffName", plan.getName());
        out.put("liveHourStart", liveHour > 0 ? liveHour : null);
        out.put("hasEnergyPoint", hasEnergyPoint());
        out.put("unpricedKwh", round(segMap.containsKey(EnergyRollup.SEG_UNPRICED)
                ? segMap.get(EnergyRollup.SEG_UNPRICED)[0] : 0d, 2));
        return out;
    }

    private Map<String, Object> segRow(String type, String name, double[] a,
                                       double price, double total) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("name", name);
        row.put("kwh", round(a[0], 2));
        row.put("cost", round(a[1], 2));
        row.put("price", price);
        row.put("ratio", total > 0 ? round(a[0] / total * 100d, 1) : 0d);
        return row;
    }

    /** 设备能耗排行。range: today / month / 7d */
    public List<Map<String, Object>> deviceRank(String range) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long from;
        long to = upperBound();
        switch (range == null ? "today" : range) {
            case "month" -> from = startOf(today.withDayOfMonth(1), zone);
            case "7d" -> from = startOf(today.minusDays(6), zone);
            default -> from = startOf(today, zone);
        }

        Map<String, double[]> map = new HashMap<>();
        for (Object[] r : repo.sumByDevice(from, to)) {
            map.put(String.valueOf(r[0]), new double[]{d(r[1]), d(r[2]), d(r[3])});
        }
        // 加上当前小时的实时量
        long liveHour = rollup.currentHourStart();
        if (liveHour >= from) {
            for (Map.Entry<String, double[]> e : rollup.liveByDevice().entrySet()) {
                double[] a = map.computeIfAbsent(e.getKey(), k -> new double[3]);
                a[0] += e.getValue()[0];
                a[1] += e.getValue()[1];
            }
        }

        Map<String, DeviceEntity> devices = new HashMap<>();
        for (DeviceEntity dev : deviceService.all()) {
            devices.put(dev.getCode(), dev);
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, double[]> e : map.entrySet()) {
            double[] a = e.getValue();
            if (a[0] <= 0) {
                continue;
            }
            DeviceEntity dev = devices.get(e.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", e.getKey());
            row.put("name", dev != null ? dev.getName() : e.getKey());
            row.put("location", dev != null ? dev.getLocation() : "");
            row.put("online", snapshot.runtime(e.getKey()).online);
            row.put("kwh", round(a[0], 2));
            row.put("cost", round(a[1], 2));
            row.put("backfillKwh", round(a[2], 2));
            list.add(row);
        }
        list.sort((x, y) -> Double.compare((Double) y.get("kwh"), (Double) x.get("kwh")));
        return list;
    }

    /**
     * 单台设备的能耗明细：指定日期 24 小时曲线 + 各时段汇总。
     *
     * @param date yyyy-MM-dd，空表示今天
     */
    public Map<String, Object> deviceDetail(String code, String date) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day;
        try {
            day = (date == null || date.isBlank()) ? LocalDate.now(zone) : LocalDate.parse(date);
        } catch (Exception e) {
            day = LocalDate.now(zone);
        }
        long from = startOf(day, zone);
        long to = startOf(day.plusDays(1), zone);
        long cut = upperBound();
        boolean isToday = day.equals(LocalDate.now(zone));

        double[] hourly = new double[24];
        String[] hourlySeg = new String[24];
        double total = 0d, cost = 0d;
        for (EnergyRecord r : repo.findByDeviceCodeAndHourStartBetweenOrderByHourStartAsc(code, from, to)) {
            int h = (int) ((r.getHourStart() - from) / 3_600_000L);
            if (h < 0 || h > 23) {
                continue;
            }
            hourly[h] += r.getKwh();
            hourlySeg[h] = r.getSegType();
            total += r.getKwh();
            cost += r.getCost();
        }

        // 当前小时的实时量补进去
        long liveHour = rollup.currentHourStart();
        if (isToday && liveHour >= from && liveHour < to) {
            int h = (int) ((liveHour - from) / 3_600_000L);
            for (Map.Entry<String, double[]> e : rollup.liveByDevice().entrySet()) {
                if (!e.getKey().equals(code)) {
                    continue;
                }
                hourly[h] += e.getValue()[0];
                total += e.getValue()[0];
                cost += e.getValue()[1];
            }
            if (hourlySeg[h] == null) {
                TariffSegment seg = tariffStore.current().segmentAt(liveHour);
                hourlySeg[h] = seg != null ? seg.getType() : EnergyRollup.SEG_UNPRICED;
            }
        }

        DeviceEntity dev = deviceService.byCode(code);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("name", dev != null ? dev.getName() : code);
        out.put("date", day.toString());
        out.put("kwh", round(total, 2));
        out.put("cost", round(cost, 2));
        out.put("hourly", hourlyList(hourly, hourlySeg));
        out.put("segments", deviceSegments(code, from, cut, isToday));
        return out;
    }

    private List<Map<String, Object>> hourlyList(double[] hourly, String[] seg) {
        List<Map<String, Object>> list = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hour", h);
            row.put("label", String.format("%02d:00", h));
            row.put("kwh", round(hourly[h], 3));
            row.put("segType", seg[h] == null ? "" : seg[h]);
            list.add(row);
        }
        return list;
    }

    private List<Map<String, Object>> deviceSegments(String code, long from, long cut, boolean isToday) {
        Map<String, double[]> map = new HashMap<>();
        for (Object[] r : repo.sumBySegmentOfDevice(code, from, cut)) {
            map.put(String.valueOf(r[0]), new double[]{d(r[1]), d(r[2])});
        }
        if (isToday) {
            long liveHour = rollup.currentHourStart();
            if (liveHour >= from) {
                for (Map.Entry<String, double[]> e : rollup.liveByDevice().entrySet()) {
                    if (!e.getKey().equals(code)) {
                        continue;
                    }
                    // 该设备的实时量只归到「当前时段」，颗粒度足够用于展示
                    TariffSegment seg = tariffStore.current().segmentAt(liveHour);
                    String type = seg != null ? seg.getType() : EnergyRollup.SEG_UNPRICED;
                    double[] a = map.computeIfAbsent(type, k -> new double[2]);
                    a[0] += e.getValue()[0];
                    a[1] += e.getValue()[1];
                }
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (String type : TariffPlan.TYPES) {
            double[] a = map.getOrDefault(type, new double[2]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("name", TariffPlan.typeName(type));
            row.put("kwh", round(a[0], 2));
            row.put("cost", round(a[1], 2));
            list.add(row);
        }
        return list;
    }

    /* ==================== 内部工具 ==================== */

    /** 查询上界 = 当前小时起点。当前小时的数据从内存取，不重复算 */
    private long upperBound() {
        long h = rollup.currentHourStart();
        return h > 0 ? h : Long.MAX_VALUE;
    }

    private static long startOf(LocalDate d, ZoneId zone) {
        return d.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private double currentPower() {
        double sum = 0d;
        for (Map.Entry<String, Map<String, PointValue>> e : snapshot.entries()) {
            for (PointValue pv : e.getValue().values()) {
                if ("power".equals(pv.role()) && pv.isGood()) {
                    Double v = pv.asDouble();
                    if (v != null) {
                        sum += v;
                    }
                }
            }
        }
        return sum;
    }

    /** 有没有任何一台设备配了电量点，没有的话前端要引导用户去配 */
    private boolean hasEnergyPoint() {
        for (Map.Entry<String, Map<String, PointValue>> e : snapshot.entries()) {
            for (PointValue pv : e.getValue().values()) {
                if ("energy".equals(pv.role())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double[] pair(Object[] row) {
        if (row == null || row.length < 2) {
            return new double[2];
        }
        return new double[]{d(row[0]), d(row[1])};
    }

    /** 取区间的总量和总电费。聚合查询没数据时会返回一行 [null, null] */
    private double[] total(long from, long to) {
        List<Object[]> rows = repo.totalBetween(from, to);
        return rows.isEmpty() ? new double[2] : pair(rows.get(0));
    }

    private static double d(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0d;
    }

    private static long l(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double round(double v, int digits) {
        double p = Math.pow(10, digits);
        return Math.round(v * p) / p;
    }
}
