package com.factory.hub.energy;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.manual.ManualEntryService;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.model.PointValue;
import com.factory.hub.service.DeviceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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
    private final ManualEntryService manualService;
    /** 产线上的 workshop 字段是用能单元（车间）的唯一来源——不新表，避免"两处维护车间" */
    private final com.factory.hub.erp.ErpProdLineRepository lineRepo;

    /** 计量层级口径：有总表就只算总表（规则见 {@link MeterScope}） */
    private final MeterScope scope;

    public EnergyService(EnergyRepository repo,
                         EnergyRollup rollup,
                         TariffStore tariffStore,
                         SnapshotStore snapshot,
                         DeviceService deviceService,
                         ManualEntryService manualService,
                         com.factory.hub.erp.ErpProdLineRepository lineRepo,
                         MeterScope scope) {
        this.repo = repo;
        this.rollup = rollup;
        this.tariffStore = tariffStore;
        this.snapshot = snapshot;
        this.deviceService = deviceService;
        this.manualService = manualService;
        this.lineRepo = lineRepo;
        this.scope = scope;
    }

    /**
     * 全厂口径下该计入哪些设备。
     * <p>
     * 有总表 → 只算总表（机床的电已含在总表里，一起加是重复计算）；
     * 没有总表（客户还没勾）→ 全算，并退回旧口径（不偷偷改客户现有数字）。
     */
    private java.util.Set<String> plantScope() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        for (com.factory.hub.model.DeviceEntity d : deviceService.all()) {
            all.add(d.getCode());
        }
        return scope.pick(all);
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

        // ---- 当前小时的实时量（按计量口径过滤：只算总表）----
        Map<String, double[]> liveSeg = rollup.liveBySegmentOf(plantScope());
        double liveKwh = 0d;
        double liveCost = 0d;
        for (double[] a : liveSeg.values()) {
            liveKwh += a[0];
            liveCost += a[1];
        }
        long liveHour = rollup.currentHourStart();
        boolean liveToday = liveHour >= todayStart;
        boolean liveMonth = liveHour >= monthStart;

        // ---- 今日 / 本月（按计量口径：全厂只算总表，避免重复计算）----
        java.util.Set<String> counted = plantScope();
        double[] t = total(counted, todayStart, cut);
        double[] m = total(counted, monthStart, cut);
        if (liveToday) {
            t[0] += liveKwh;
            t[1] += liveCost;
        }
        if (liveMonth) {
            m[0] += liveKwh;
            m[1] += liveCost;
        }

        // ---- 补录（DH-13）：并进总量，不硬塞进分时构成（补录没有真实时段）----
        double[] manualToday = manualService.totals(today, today);
        double[] manualMonth = manualService.totals(today.withDayOfMonth(1), today);
        t[0] += manualToday[0];
        t[1] += manualToday[1];
        m[0] += manualMonth[0];
        m[1] += manualMonth[1];

        // ---- 峰谷分布 ----
        Map<String, double[]> segMap = new HashMap<>();
        for (Object[] r : repo.sumBySegmentOf(counted, todayStart, cut)) {
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
        // 计量口径标记：LEGACY = 没标总表，全厂含所有设备（机床的电与总表重复计算）
        out.put("meterMode", scope.legacy() ? "LEGACY" : "METER");
        out.put("meters", scope.meters().stream().map(d -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("code", d.getCode());
            mm.put("name", d.getName());
            mm.put("location", d.getLocation());
            return mm;
        }).toList());
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
        out.put("manualTodayKwh", round(manualToday[0], 2));
        out.put("manualMonthKwh", round(manualMonth[0], 2));
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

    /**
     * 用能单元（ROADMAP DH-11）：全厂 → 车间 → 产线 三级能耗聚合。
     * <p>
     * <b>零新表</b>：车间来自 {@code erp_prod_line.workshop}，产线是 {@code erp_prod_line}，
     * 设备挂线是 {@code device.prod_line_id}——三者都是已有数据，不再维护第二套组织树，
     * 从根上避免"改了产线车间、能耗页还对不上"。
     * <p>
     * 数据源与设备排行同源（energy_hourly + 当前小时实时量），所以
     * <b>各级合计必然等于全厂</b>：车间合计 + 未分配 = 全厂，接口里带 check 字段自证。
     * 没挂产线的设备进"未分配"，不硬塞进任何车间——宁可显示缺口，也不做假账。
     *
     * @param range today / 7d / month
     */
    public Map<String, Object> byNode(String range) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long from;
        long to = upperBound();
        switch (range == null ? "today" : range) {
            case "month" -> from = startOf(today.withDayOfMonth(1), zone);
            case "7d" -> from = startOf(today.minusDays(6), zone);
            default -> from = startOf(today, zone);
        }

        // 1. 每台设备的电量（与 deviceRank 同一口径，含当前小时实时量）
        Map<String, double[]> perDevice = new HashMap<>();
        for (Object[] r : repo.sumByDevice(from, to)) {
            perDevice.put(String.valueOf(r[0]), new double[]{d(r[1]), d(r[2]), d(r[3])});
        }
        long liveHour = rollup.currentHourStart();
        if (liveHour >= from) {
            for (Map.Entry<String, double[]> e : rollup.liveByDevice().entrySet()) {
                double[] a = perDevice.computeIfAbsent(e.getKey(), k -> new double[3]);
                a[0] += e.getValue()[0];
                a[1] += e.getValue()[1];
            }
        }

        // 补录并进设备电量，分项合计才和全厂/月报对得上
        LocalDate fromDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate();
        for (Map.Entry<String, double[]> e : manualService.byDevice(fromDate, today).entrySet()) {
            double[] a = perDevice.computeIfAbsent(e.getKey(), k -> new double[3]);
            a[0] += e.getValue()[0];
            a[1] += e.getValue()[1];
        }

        // 2. 设备 → 产线 → 车间
        //
        // 计量层级口径：**每台设备在它所属的最细层级（产线 / 未分配组）内判断
        // 是否被总表覆盖**——有表就只算表，没有就全算。
        // 这样：产线级没有表时，机床的电就是产线的电（对）；
        //         产线级有表时，机床的电已含在表里，不重复计（也对）。
        // 不能简单地全局取"只有表"，否则产线级的电会全变成 0。
        Map<Long, com.factory.hub.erp.ErpProdLine> lineById = new HashMap<>();
        for (com.factory.hub.erp.ErpProdLine l : lineRepo.findAll()) {
            lineById.put(l.getId(), l);
        }

        // 先按最细层级把设备分组，再对每组用 scope.pick 决定谁计入
        List<DeviceEntity> allDevices = deviceService.all();
        Map<Long, java.util.List<String>> codesOfLine = new LinkedHashMap<>();
        java.util.List<String> unassignedCodes = new ArrayList<>();
        for (DeviceEntity dev : allDevices) {
            Long lineId = dev.getProdLineId();
            if (lineId == null || !lineById.containsKey(lineId)) {
                unassignedCodes.add(dev.getCode());
            } else {
                codesOfLine.computeIfAbsent(lineId, k -> new ArrayList<>()).add(dev.getCode());
            }
        }
        java.util.Set<String> countedUnassigned = scope.pick(unassignedCodes);
        Map<Long, java.util.Set<String>> countedOfLine = new LinkedHashMap<>();
        for (Map.Entry<Long, java.util.List<String>> e : codesOfLine.entrySet()) {
            countedOfLine.put(e.getKey(), scope.pick(e.getValue()));
        }

        Map<Long, double[]> perLine = new LinkedHashMap<>();          // [kwh, cost, backfill, deviceCount]
        Map<String, double[]> perWorkshop = new LinkedHashMap<>();    // [kwh, cost, backfill, deviceCount, lineCount]
        double[] unassigned = new double[4];
        for (DeviceEntity dev : allDevices) {
            double[] v = perDevice.get(dev.getCode());
            double kwh = v == null ? 0d : v[0];
            double cost = v == null ? 0d : v[1];
            double bf = v == null ? 0d : v[2];
            Long lineId = dev.getProdLineId();
            com.factory.hub.erp.ErpProdLine line = lineId == null ? null : lineById.get(lineId);
            if (line == null) {
                // 未分配组：组内有总表就只算总表
                if (!countedUnassigned.contains(dev.getCode())) {
                    continue;
                }
                unassigned[0] += kwh;
                unassigned[1] += cost;
                unassigned[2] += bf;
                unassigned[3] += 1;
                continue;
            }
            // 产线级：产线内有总表就只算总表
            if (!countedOfLine.getOrDefault(line.getId(), java.util.Set.of()).contains(dev.getCode())) {
                continue;
            }
            double[] pl = perLine.computeIfAbsent(line.getId(), k -> new double[4]);
            pl[0] += kwh;
            pl[1] += cost;
            pl[2] += bf;
            pl[3] += 1;

            String ws = (line.getWorkshop() == null || line.getWorkshop().isBlank())
                    ? "未标注车间" : line.getWorkshop().trim();
            double[] pw = perWorkshop.computeIfAbsent(ws, k -> new double[5]);
            pw[0] += kwh;
            pw[1] += cost;
            pw[2] += bf;
            pw[3] += 1;
        }
        // 车间下的产线数量
        for (com.factory.hub.erp.ErpProdLine l : lineById.values()) {
            String ws = (l.getWorkshop() == null || l.getWorkshop().isBlank())
                    ? "未标注车间" : l.getWorkshop().trim();
            double[] pw = perWorkshop.get(ws);
            if (pw != null) {
                pw[4] += 1;
            }
        }

        // 合计 = 各层级计入的量之和（与"分项合计 = 全厂"的自洽要求一致）
        double totalKwh = 0d, totalCost = 0d, totalBf = 0d;
        for (double[] a : perLine.values()) {
            totalKwh += a[0];
            totalCost += a[1];
            totalBf += a[2];
        }
        totalKwh += unassigned[0];
        totalCost += unassigned[1];
        totalBf += unassigned[2];

        // 3. 组装：车间（含其下产线）
        List<Map<String, Object>> workshops = new ArrayList<>();
        for (Map.Entry<String, double[]> we : perWorkshop.entrySet()) {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map.Entry<Long, double[]> le : perLine.entrySet()) {
                com.factory.hub.erp.ErpProdLine l = lineById.get(le.getKey());
                if (l == null) {
                    continue;
                }
                String ws = (l.getWorkshop() == null || l.getWorkshop().isBlank())
                        ? "未标注车间" : l.getWorkshop().trim();
                if (!ws.equals(we.getKey())) {
                    continue;
                }
                double[] la = le.getValue();
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("lineId", l.getId());
                lm.put("lineName", l.getName());
                lm.put("kwh", round(la[0], 2));
                lm.put("cost", round(la[1], 2));
                lm.put("backfillKwh", round(la[2], 2));
                lm.put("deviceCount", (int) la[3]);
                lm.put("share", totalKwh > 0 ? round(la[0] / totalKwh * 100, 1) : 0d);
                lines.add(lm);
            }
            lines.sort((x, y) -> Double.compare((Double) y.get("kwh"), (Double) x.get("kwh")));

            double[] wa = we.getValue();
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("workshop", we.getKey());
            wm.put("kwh", round(wa[0], 2));
            wm.put("cost", round(wa[1], 2));
            wm.put("backfillKwh", round(wa[2], 2));
            wm.put("deviceCount", (int) wa[3]);
            wm.put("lineCount", (int) wa[4]);
            wm.put("share", totalKwh > 0 ? round(wa[0] / totalKwh * 100, 1) : 0d);
            wm.put("lines", lines);
            workshops.add(wm);
        }
        workshops.sort((x, y) -> Double.compare((Double) y.get("kwh"), (Double) x.get("kwh")));

        // 4. 合计自证：各车间 + 未分配 必须等于全厂
        double wsSum = 0d;
        for (Map<String, Object> wm : workshops) {
            wsSum += (Double) wm.get("kwh");
        }
        double unKwh = round(unassigned[0], 2);
        double gap = round(totalKwh - wsSum - unKwh, 2);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", range == null ? "today" : range);
        out.put("total", Map.of(
                "kwh", round(totalKwh, 2),
                "cost", round(totalCost, 2),
                "backfillKwh", round(totalBf, 2)));
        out.put("workshops", workshops);
        out.put("unassigned", Map.of(
                "kwh", unKwh,
                "cost", round(unassigned[1], 2),
                "backfillKwh", round(unassigned[2], 2),
                "deviceCount", (int) unassigned[3]));
        out.put("check", Map.of(
                "workshopSum", round(wsSum, 2),
                "matchesTotal", Math.abs(gap) < 0.01,
                "gap", gap));
        return out;
    }

    /**
     * 月度电费（DH-16）：整月电量 + 分时电度电费 + 电价方案。
     * <p>
     * 基本电费在 {@code DemandService} 里（需量/容量两种算法），导出时合并，
     * 这里只负责「电度电费」这一半，避免两个服务各算一套、对不上账。
     * <p>
     * 当月统计到当前小时（含实时值），历史月份统计整月。
     */
    public Map<String, Object> monthBill(YearMonth month) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        boolean current = YearMonth.from(today).equals(month);
        long to = current
                ? upperBound()
                : month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();

        // 电度电费按计量口径算：全厂只算总表，避免机床的电与总表重复计费
        java.util.Set<String> counted = plantScope();
        Map<String, double[]> segMap = new LinkedHashMap<>();
        for (Object[] r : repo.sumBySegmentOf(counted, from, to)) {
            segMap.put(String.valueOf(r[0]), new double[]{d(r[1]), d(r[2])});
        }
        // 当前小时还没结算进库，补进来才对得上「到今天为止」的账单
        long liveHour = rollup.currentHourStart();
        if (current && liveHour >= from) {
            for (Map.Entry<String, double[]> e : rollup.liveBySegmentOf(counted).entrySet()) {
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
        double totalKwh = 0d;
        double totalCost = 0d;
        for (String type : TariffPlan.TYPES) {
            double[] a = segMap.getOrDefault(type, new double[2]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("name", TariffPlan.typeName(type));
            m.put("price", priceOf.getOrDefault(type, 0d));
            m.put("kwh", round(a[0], 2));
            m.put("cost", round(a[1], 2));
            segments.add(m);
            totalKwh += a[0];
            totalCost += a[1];
        }
        double[] un = segMap.get(EnergyRollup.SEG_UNPRICED);
        if (un != null && un[0] > 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", EnergyRollup.SEG_UNPRICED);
            m.put("name", "未计价");
            m.put("price", 0d);
            m.put("kwh", round(un[0], 2));
            m.put("cost", round(un[1], 2));
            segments.add(m);
            totalKwh += un[0];
            totalCost += un[1];
        }

        LocalDate monthEnd = current ? today : month.atEndOfMonth();
        double[] manual = manualService.totals(month.atDay(1), monthEnd);
        if (manual[0] > 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "MANUAL");
            m.put("name", "补录");
            m.put("price", 0d);
            m.put("kwh", round(manual[0], 2));
            m.put("cost", round(manual[1], 2));
            segments.add(m);
            totalKwh += manual[0];
            totalCost += manual[1];
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month.toString());
        out.put("fromText", month.atDay(1).toString());
        out.put("toText", (current ? today : month.atEndOfMonth()).toString());
        out.put("complete", !current);
        out.put("tariffName", plan.getName());
        out.put("kwh", round(totalKwh, 2));
        out.put("cost", round(totalCost, 2));
        out.put("segments", segments);
        out.put("unpricedKwh", un == null ? 0d : round(un[0], 2));
        out.put("manualKwh", round(manual[0], 2));
        out.put("manualCost", round(manual[1], 2));
        return out;
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

        // 补录（DH-13）：只补了电量、没有采集行的设备也要出现在排行里
        LocalDate fromDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate();
        for (Map.Entry<String, double[]> e : manualService.byDevice(fromDate, today).entrySet()) {
            double[] a = map.computeIfAbsent(e.getKey(), k -> new double[3]);
            a[0] += e.getValue()[0];
            a[1] += e.getValue()[1];
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

        double[] manual = manualService.totals(day, day);
        total += manual[0];
        cost += manual[1];

        DeviceEntity dev = deviceService.byCode(code);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("name", dev != null ? dev.getName() : code);
        out.put("date", day.toString());
        out.put("kwh", round(total, 2));
        out.put("cost", round(cost, 2));
        out.put("hourly", hourlyList(hourly, hourlySeg));
        out.put("segments", deviceSegments(code, from, cut, isToday));
        out.put("manualKwh", round(manual[0], 2));
        out.put("manualCost", round(manual[1], 2));
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
    /** 区间总量与总电费（**只算 counted 里的设备**——计量层级口径） */
    private double[] total(java.util.Set<String> counted, long from, long to) {
        if (counted.isEmpty()) {
            return new double[2];
        }
        List<Object[]> rows = repo.totalBetweenOf(counted, from, to);
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
