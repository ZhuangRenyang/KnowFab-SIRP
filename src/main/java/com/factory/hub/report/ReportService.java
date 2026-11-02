package com.factory.hub.report;

import com.factory.hub.alarm.AlarmEvent;
import com.factory.hub.alarm.AlarmEventRepository;
import com.factory.hub.alarm.AlarmRule;
import com.factory.hub.manual.ManualEntry;
import com.factory.hub.manual.ManualEntryService;
import com.factory.hub.service.DeviceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报表查询。
 * <p>
 * 所有数字都从 {@link ProdHourly} 现算，不另存一张「日报表」——
 * 多存一份就得管同步，一旦同步失败，客户拿到的报表和明细对不上，
 * 这种事在工厂里是要被叫去对账的。
 * <p>
 * 一天的行数是「设备数 × 24」，500 台也就 1.2 万行，内存里加一遍毫无压力。
 * 长时间的区间报表按天循环查，避免一次把几十万行拉进内存。
 */
@Service
public class ReportService {

    private final ProdHourlyRepository repo;
    private final ShiftStore shiftStore;
    private final AlarmEventRepository alarmRepo;
    private final DeviceService deviceService;
    private final ManualEntryService manualService;

    public ReportService(ProdHourlyRepository repo, ShiftStore shiftStore,
                         AlarmEventRepository alarmRepo, DeviceService deviceService,
                         ManualEntryService manualService) {
        this.repo = repo;
        this.shiftStore = shiftStore;
        this.alarmRepo = alarmRepo;
        this.deviceService = deviceService;
        this.manualService = manualService;
    }

    /* ==================== 单日报表 ==================== */

    public Map<String, Object> daily(LocalDate date) {
        ShiftPlan plan = shiftStore.current();
        List<ShiftPlan.Shift> shifts = plan.getShifts() == null ? List.of() : plan.getShifts();

        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;
        List<Map<String, Object>> shiftRows = new ArrayList<>();

        List<ProdHourly> all;
        if (shifts.isEmpty()) {
            long[] day = dayWindow(date);
            from = day[0];
            to = day[1];
            all = repo.findInWindow(from, to);
        } else {
            // 先算出所有班次的时间窗，一次把这段数据查回来，避免每个班次查一遍库
            List<long[]> windows = new ArrayList<>();
            for (ShiftPlan.Shift s : shifts) {
                long[] w = plan.windowOf(s, date);
                windows.add(w);
                from = Math.min(from, w[0]);
                to = Math.max(to, w[1]);
            }
            all = repo.findInWindow(from, to);

            for (int i = 0; i < shifts.size(); i++) {
                ShiftPlan.Shift s = shifts.get(i);
                long[] w = windows.get(i);
                Sum sum = new Sum();
                for (ProdHourly p : all) {
                    if (p.getHourStart() >= w[0] && p.getHourStart() < w[1]) {
                        sum.add(p);
                    }
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("name", s.getName());
                m.put("start", s.getStart());
                m.put("end", s.getEnd());
                m.put("from", w[0]);
                m.put("to", w[1]);
                m.put("crossMidnight", s.crossMidnight());
                m.putAll(sum.toMap());
                shiftRows.add(m);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("date", date.toString());
        out.put("window", Map.of("from", from == Long.MAX_VALUE ? 0 : from,
                "to", to == Long.MIN_VALUE ? 0 : to));
        out.put("planName", plan.getName());
        out.put("shifts", shiftRows);
        out.put("totals", totals(all));
        out.put("devices", byDevice(all, shifts, plan, date));
        out.put("hours", byHour(all, from, to));
        out.put("stopReasons", stopReasons(from, to));

        // 补录（DH-13）：总量含补录，同时单列「其中补录」，不和采集混淆
        List<ManualEntry> manual = manualService.activeOfDay(date);
        applyManualToTotals((Map<String, Object>) out.get("totals"), manual);
        List<Map<String, Object>> dayDevices = (List<Map<String, Object>>) out.get("devices");
        applyManualToDevices(dayDevices, manual);
        out.put("manual", manualRows(manual));
        // DH-15 OEE：计划生产时间 = 当天的班次总时长
        double planned = plan.plannedSecOf(date);
        attachOee(dayDevices, manual, planned);
        out.put("oee", oeeTotals(dayDevices, manual, planned));
        out.put("warnings", plan.validate());
        return out;
    }

    /**
     * 最近 N 天的每日汇总，给趋势图用（DH-15 加了 OEE 趋势）。
     * <p>
     * 厂级一行一天 + 每台设备一行一天，图上画 OEE 的走势。
     * <p>
     * <b>补录不能走 {@code sumByDay} 那条聚合查询</b>：SUM 会把「没录」和「录了 0」
     * 算成同一个数，而这两件事对 OEE 完全不同（前者是数据缺失，良率算不出来；
     * 后者是已知的零不良）。所以这里取补录实体在内存里归拢。
     */
    public Map<String, Object> range(LocalDate to, int days) {
        int n = Math.max(1, Math.min(days, 90));
        ShiftPlan plan = shiftStore.current();
        LocalDate first = to.minusDays(n - 1);
        List<ManualEntry> manual = manualService.findActive(first, to);

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Object>> devTrend = new LinkedHashMap<>();
        Map<String, String> devNames = new HashMap<>();
        List<String> dates = new ArrayList<>();

        for (int i = n - 1; i >= 0; i--) {
            LocalDate d = to.minusDays(i);
            dates.add(d.toString());
            long[] w = dayWindow(d);
            List<ProdHourly> dayRows = repo.findInWindow(w[0], w[1]);

            Sum sum = new Sum();
            Map<String, Sum> perDev = new LinkedHashMap<>();
            for (ProdHourly p : dayRows) {
                sum.add(p);
                perDev.computeIfAbsent(p.getDeviceCode(), k -> new Sum()).add(p);
                if (p.getDeviceName() != null) {
                    devNames.putIfAbsent(p.getDeviceCode(), p.getDeviceName());
                }
            }

            // 当天的补录（按设备归拢）
            List<ManualEntry> dayManual = new ArrayList<>();
            for (ManualEntry e : manual) {
                if (e.getDay().equals(d)) {
                    dayManual.add(e);
                }
            }

            // 设备行：补录并进去，再算 OEE
            List<Map<String, Object>> devRows = new ArrayList<>();
            for (Map.Entry<String, Sum> en : perDev.entrySet()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("code", en.getKey());
                r.put("name", devNames.getOrDefault(en.getKey(), en.getKey()));
                r.putAll(en.getValue().toMap());
                devRows.add(r);
            }
            applyManualToDevices(devRows, dayManual);
            double planned = plan.plannedSecOf(d);
            attachOee(devRows, dayManual, planned);

            // 厂级一行
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.putAll(sum.toMap());
            double manualOut = 0, manualDefect = 0;
            for (ManualEntry e : dayManual) {
                manualOut += e.getOutput();
                if (e.getDefect() != null) {
                    manualDefect += e.getDefect();
                }
            }
            row.put("output", round1(sum.output + manualOut));
            row.put("defect", round1(sum.defect + manualDefect));
            if (manualOut != 0d) {
                row.put("manualOutput", round1(manualOut));
            }
            row.put("oee", oeeTotals(devRows, dayManual, planned));
            rows.add(row);

            // 单设备趋势：每台设备一条与 dates 对齐的序列，缺的那天留 null
            for (Map<String, Object> dr : devRows) {
                String code = String.valueOf(dr.get("code"));
                @SuppressWarnings("unchecked")
                Map<String, Object> devOee = (Map<String, Object>) dr.get("oee");
                devTrend.computeIfAbsent(code, k -> new LinkedHashMap<>())
                        .put(d.toString(), devOee);
            }
        }

        // 把每台设备的逐日 OEE 摊成跟 dates 对齐的数组（缺的填 null，图上断线）
        List<Map<String, Object>> devices = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : devTrend.entrySet()) {
            List<Map<String, Object>> series = new ArrayList<>();
            for (String ds : dates) {
                Object o = e.getValue().get(ds);
                series.add(o instanceof Map<?, ?> ? (Map<String, Object>) o : null);
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("code", e.getKey());
            d.put("name", devNames.getOrDefault(e.getKey(), e.getKey()));
            d.put("series", series);
            devices.add(d);
        }
        devices.sort(Comparator.comparing((Map<String, Object> x) -> String.valueOf(x.get("code"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("rows", rows);
        out.put("dates", dates);
        out.put("devices", devices);
        return out;
    }

    /* ==================== 月度报表（DH-16 月报导出用） ==================== */

    /**
     * 整月生产汇总：总计 + 逐日 + 按设备。
     * <p>
     * 和日报一样从 {@link ProdHourly} 现算，不另存月表。
     * 当月只统计到今天（含今日），历史月份统计整月；返回 {@code complete}
     * 让导出时能标注「截至 X 日」还是「全月」。
     */
    public Map<String, Object> monthly(YearMonth month) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate start = month.atDay(1);
        LocalDate today = LocalDate.now(zone);
        LocalDate next = month.plusMonths(1).atDay(1);
        boolean current = !next.isBefore(today);          // 本月或未来月
        LocalDate endEx = current ? today.plusDays(1) : next;
        long from = start.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = endEx.atStartOfDay(zone).toInstant().toEpochMilli();

        List<ProdHourly> all = repo.findInWindow(from, to);

        // 逐日：一次遍历分桶，避免「31 天 × 全部行」的嵌套循环
        Map<LocalDate, Sum> perDay = new LinkedHashMap<>();
        for (ProdHourly p : all) {
            LocalDate d = Instant.ofEpochMilli(p.getHourStart()).atZone(zone).toLocalDate();
            perDay.computeIfAbsent(d, k -> new Sum()).add(p);
        }
        List<Map<String, Object>> daily = new ArrayList<>();
        int activeDays = 0;
        for (LocalDate d = start; d.isBefore(endEx); d = d.plusDays(1)) {
            Sum s = perDay.get(d);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.putAll(s == null ? new Sum().toMap() : s.toMap());
            daily.add(m);
            Object counted = m.get("countedSec");
            if (counted instanceof Number n && n.doubleValue() > 0) {
                activeDays++;
            }
        }

        // 按设备：月报里的「设备月产量」sheet
        Map<String, Sum> sums = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (ProdHourly p : all) {
            sums.computeIfAbsent(p.getDeviceCode(), k -> new Sum()).add(p);
            if (p.getDeviceName() != null) {
                names.putIfAbsent(p.getDeviceCode(), p.getDeviceName());
            }
        }
        List<Map<String, Object>> devices = new ArrayList<>();
        for (Map.Entry<String, Sum> e : sums.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", e.getKey());
            m.put("name", names.getOrDefault(e.getKey(), e.getKey()));
            m.put("location", locationOf(e.getKey()));
            m.putAll(e.getValue().toMap());
            devices.add(m);
        }
        devices.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> -((Number) m.get("output")).doubleValue()));

        // 补录：按天 / 按设备 / 总量三处都要并，否则 sheet 之间对不上
        Map<LocalDate, double[]> manualByDay = manualService.byDay(start, endEx.minusDays(1));
        for (Map<String, Object> row : daily) {
            LocalDate d = LocalDate.parse(String.valueOf(row.get("date")));
            double mo = manualByDay.getOrDefault(d, new double[3])[2];
            if (mo != 0d) {
                row.put("manualOutput", round1(mo));
                row.put("output", round1(((Number) row.get("output")).doubleValue() + mo));
            }
        }
        List<ManualEntry> manual = manualService
                .findActive(start, endEx.minusDays(1));
        applyManualToDevices(devices, manual);
        Map<String, Object> monthTotals = totals(all);
        applyManualToTotals(monthTotals, manual);
        devices.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> -((Number) m.get("output")).doubleValue()));

        // DH-15 OEE：计划生产时间 = 本月每天的班次总时长之和
        double planned = 0;
        for (LocalDate d = start; d.isBefore(endEx); d = d.plusDays(1)) {
            planned += shiftStore.current().plannedSecOf(d);
        }
        attachOee(devices, manual, planned);
        Map<String, Object> monthOee = oeeTotals(devices, manual, planned);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("month", month.toString());
        out.put("fromText", start.toString());
        out.put("toText", endEx.minusDays(1).toString());
        out.put("complete", !current);
        out.put("days", (int) java.time.temporal.ChronoUnit.DAYS.between(start, endEx));
        out.put("activeDays", activeDays);
        out.put("totals", monthTotals);
        out.put("oee", monthOee);
        out.put("daily", daily);
        out.put("devices", devices);
        return out;
    }

    /* ==================== 聚合 ==================== */

    /** 一台设备（或全厂）在某段时间内的合计 */
    private static final class Sum {
        double run, idle, stop, alarm, unknown, output, backfill;
        /** DH-15：自动采集的不良数（人工补录的那部分在别处加） */
        double defect;
        Set<String> devices = new LinkedHashSet<>();

        void add(ProdHourly p) {
            run += p.getRunSec();
            idle += p.getIdleSec();
            stop += p.getStopSec();
            alarm += p.getAlarmSec();
            unknown += p.getUnknownSec();
            output += p.getOutput();
            backfill += p.getBackfill();
            defect += p.getDefect();
            if (p.getDeviceCode() != null) {
                devices.add(p.getDeviceCode());
            }
        }

        Map<String, Object> toMap() {
            double counted = run + idle + stop + alarm + unknown;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("output", round1(output));
            m.put("runSec", round1(run));
            m.put("idleSec", round1(idle));
            m.put("stopSec", round1(stop));
            m.put("alarmSec", round1(alarm));
            m.put("unknownSec", round1(unknown));
            m.put("countedSec", round1(counted));
            // 数据不足一分钟不给稼动率，否则刚接上的设备会显示 0%，看着像坏了
            m.put("availability", counted < 60 ? -1 : round1(run / counted * 100));
            m.put("backfill", round1(backfill));
            m.put("defect", round1(defect));
            m.put("deviceCount", devices.size());
            return m;
        }
    }

    /** 总量加上补录，并把「其中补录」单列出来 */
    private void applyManualToTotals(Map<String, Object> totals, List<ManualEntry> manual) {
        if (totals == null) {
            return;
        }
        double manualOut = 0d;
        for (ManualEntry e : manual) {
            manualOut += e.getOutput();
        }
        double base = totals.get("output") instanceof Number n ? n.doubleValue() : 0d;
        totals.put("output", round1(base + manualOut));
        totals.put("manualOutput", round1(manualOut));
    }

    /**
     * 设备行并入补录：有采集行就加进去，只有补录的设备单独补一行。
     * 这样「设备明细」的产量之和仍然等于总量（客户会一行行加）。
     */
    @SuppressWarnings("unchecked")
    private void applyManualToDevices(List<Map<String, Object>> rows, List<ManualEntry> manual) {
        if (rows == null) {
            return;
        }
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            r.put("manualOutput", 0d);
            byCode.put(String.valueOf(r.get("code")), r);
        }
        for (ManualEntry e : manual) {
            Map<String, Object> r = byCode.get(e.getDeviceCode());
            if (r == null) {
                r = new LinkedHashMap<>();
                r.put("code", e.getDeviceCode());
                r.put("name", e.getDeviceName() == null ? e.getDeviceCode() : e.getDeviceName());
                r.put("location", locationOf(e.getDeviceCode()));
                r.put("output", 0d);
                r.put("runSec", 0d);
                r.put("idleSec", 0d);
                r.put("stopSec", 0d);
                r.put("alarmSec", 0d);
                r.put("unknownSec", 0d);
                r.put("countedSec", 0d);
                r.put("availability", -1d);
                r.put("manualOutput", 0d);
                rows.add(r);
                byCode.put(e.getDeviceCode(), r);
            }
            double out = ((Number) r.get("output")).doubleValue() + e.getOutput();
            double mo = ((Number) r.get("manualOutput")).doubleValue() + e.getOutput();
            r.put("output", round1(out));
            r.put("manualOutput", round1(mo));
        }
    }

    /* ==================== DH-15 OEE ==================== */

    private final OeeCalculator oeeCalc = new OeeCalculator();

    /**
     * 理想节拍和 reject 点位都问 {@link DeviceService}——缓存在那边，跟着设备配置走，
     * 客户改完「理想节拍」报表立刻就变（这边自己缓存的话，得等重启才生效，
     * 看着像没保存成功）。
     */

    /**
     * 给设备明细行补上 OEE（DH-15）。
     * <p>
     * 不良数 = 自动采集的（prod_hourly.defect）+ 人工补录的。
     * 「有没有不良数数据」看的是：设备配了 reject 点位，**或者**本期有人补录过不良数——
     * 不是看不良数是不是 0。没采到和真的是零不良，两回事。
     */
    private void attachOee(List<Map<String, Object>> rows, List<ManualEntry> manual, double plannedSec) {
        Map<String, Double> manualDefect = new HashMap<>();
        for (ManualEntry e : manual) {
            if (e.getDefect() != null) {
                manualDefect.merge(e.getDeviceCode(), e.getDefect(), Double::sum);
            }
        }
        for (Map<String, Object> row : rows) {
            String code = String.valueOf(row.get("code"));
            double defect = d(row.get("defect")) + manualDefect.getOrDefault(code, 0d);
            boolean hasDefectData = deviceService.hasRejectPoint(code) || manualDefect.containsKey(code);
            Double cycle = deviceService.idealCycleSecOf(code);
            OeeCalculator.Result r = oeeCalc.compute(OeeCalculator.forDevice(
                    d(row.get("runSec")), d(row.get("output")), defect, hasDefectData,
                    cycle, plannedSec, d(row.get("countedSec"))));
            row.put("oee", oeeMap(r, cycle));
        }
    }

    /**
     * 合计 OEE（DH-15）。
     * <p>
     * <b>合计的性能率不能拿一个节拍算全厂</b>——每台设备节拍不一样，
     * 理论产量得一台一台算完再加起来。同样，良率要求<b>每台设备都有不良数数据</b>，
     * 少一台就不给合计良率和合计 OEE（并说清楚缺哪几台）。
     * 单台设备的 OEE 不受影响，照常给。
     */
    private Map<String, Object> oeeTotals(List<Map<String, Object>> deviceRows,
                                          List<ManualEntry> manual, double plannedSec) {
        int n = deviceRows.size();
        double run = 0, output = 0, defect = 0, counted = 0, theoretical = 0;
        int noCycle = 0, noDefect = 0;
        for (Map<String, Object> row : deviceRows) {
            double rowOutput = d(row.get("output"));
            run += d(row.get("runSec"));
            output += rowOutput;
            defect += d(row.get("defect"));
            counted += d(row.get("countedSec"));
            Object o = row.get("oee");
            if (!(o instanceof Map<?, ?> m)) {
                noCycle++;
                noDefect++;
                continue;
            }
            Object th = m.get("theoreticalOutput");
            if (th instanceof Number num) {
                theoretical += num.doubleValue();
            } else if (rowOutput > 0) {
                // 只有「真的产了东西」的设备才卡性能率：电表这种没有产量点的设备，
                // 它不参与性能计算，也不该把全厂的性能率拖成算不出来
                noCycle++;
            }
            if (!Boolean.TRUE.equals(m.get("hasDefectData"))) {
                noDefect++;
            }
        }
        // 人工补录的不良数也要进合计（逐台那步加的是各台的，这里加总）
        for (ManualEntry e : manual) {
            if (e.getDefect() != null) {
                defect += e.getDefect();
            }
        }
        Double theoreticalAll = (noCycle > 0 || n == 0) ? null : theoretical;
        boolean hasDefectData = noDefect == 0 && n > 0;
        String note;
        if (n == 0) {
            // 这天一台设备的数据都没有：全给 null。
            // 给 0 的话，趋势图上会多出一段贴着底的直线，看着像「这���效率全废了」，
            // 那是编出来的结论——「没数据」和「效率是 0」是两回事
            note = "这天没有任何设备的数据，算不出来";
        } else if (noCycle > 0) {
            note = "有 " + noCycle + " 台设备没配「理想节拍（秒/件）」，合计性能率算不出来";
        } else {
            note = null;
        }
        OeeCalculator.Result r = oeeCalc.compute(OeeCalculator.forAggregate(
                run, output, defect, hasDefectData, theoreticalAll,
                plannedSec * n, counted, note));
        Map<String, Object> m = oeeMap(r, null);
        m.put("deviceCount", n);
        m.put("noCycleDevices", noCycle);
        m.put("noDefectDevices", noDefect);
        if (noDefect > 0 && n > 0) {
            List<String> notes = new ArrayList<>(r.notes());
            notes.add("有 " + noDefect + " 台设备没有不良数数据，合计良率算不出来（单台设备的 OEE 不受影响）");
            m.put("notes", notes);
        }
        return m;
    }

    private Map<String, Object> oeeMap(OeeCalculator.Result r, Double cycle) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("availability", r.availability());
        m.put("performance", r.performance());
        m.put("quality", r.quality());
        m.put("oee", r.oee());
        m.put("plannedSec", round1(r.plannedSec()));
        m.put("countedSec", round1(r.countedSec()));
        m.put("runSec", round1(r.runSec()));
        m.put("output", round1(r.output()));
        m.put("defect", round1(r.defect()));
        m.put("hasDefectData", r.hasDefectData());
        m.put("theoreticalOutput", r.theoreticalOutput());
        m.put("idealCycleSec", cycle);
        // 数据完整度：采到的时长占计划生产时间的比例。趋势图上用它标出
        // 「这天的数别当真」——系统下午才接上的时候，可用率会低得离谱，
        // 不标出来客户会当成设备效率差
        double planned = r.plannedSec();
        m.put("coverage", planned > 0 ? round1(r.countedSec() / planned * 100) : 0d);
        m.put("notes", r.notes());
        return m;
    }

    private static double d(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0d;
    }

    /** 补录明细（日报/月报接口返回，前端展示"这一天补了什么"） */
    private List<Map<String, Object>> manualRows(List<ManualEntry> manual) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ManualEntry e : manual) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("deviceCode", e.getDeviceCode());
            m.put("deviceName", e.getDeviceName());
            m.put("day", e.getDay().toString());
            m.put("kwh", round1(e.getKwh()));
            m.put("kwhCost", round1(e.getKwhCost()));
            m.put("output", round1(e.getOutput()));
            // DH-15：不良数。null = 没录（数据缺失），前端显示「--」
            m.put("defect", e.getDefect());
            m.put("reason", e.getReason());
            m.put("operator", e.getOperator());
            rows.add(m);
        }
        return rows;
    }

    private Map<String, Object> totals(List<ProdHourly> all) {
        Sum s = new Sum();
        all.forEach(s::add);
        return s.toMap();
    }

    /** 每台设备的合计 + 每班产量，报表主表 */
    private List<Map<String, Object>> byDevice(List<ProdHourly> all,
                                               List<ShiftPlan.Shift> shifts,
                                               ShiftPlan plan, LocalDate date) {
        Map<String, Sum> sums = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, Map<String, Double>> shiftOut = new HashMap<>();

        for (ProdHourly p : all) {
            sums.computeIfAbsent(p.getDeviceCode(), k -> new Sum()).add(p);
            if (p.getDeviceName() != null) {
                names.put(p.getDeviceCode(), p.getDeviceName());
            }
        }
        // 每台设备每个班次的产量。客户最常问的就是「这台机早班和夜班差多少」
        for (ShiftPlan.Shift s : shifts) {
            long[] w = plan.windowOf(s, date);
            for (ProdHourly p : all) {
                if (p.getHourStart() >= w[0] && p.getHourStart() < w[1]) {
                    shiftOut.computeIfAbsent(p.getDeviceCode(), k -> new HashMap<>())
                            .merge(s.getId(), p.getOutput(), Double::sum);
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Sum> e : sums.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", e.getKey());
            m.put("name", names.getOrDefault(e.getKey(), e.getKey()));
            m.put("location", locationOf(e.getKey()));
            m.putAll(e.getValue().toMap());
            Map<String, Object> so = new LinkedHashMap<>();
            for (ShiftPlan.Shift s : shifts) {
                so.put(s.getId(), round1(shiftOut
                        .getOrDefault(e.getKey(), Map.of())
                        .getOrDefault(s.getId(), 0d)));
            }
            m.put("shiftOutput", so);
            rows.add(m);
        }
        rows.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> ((Number) m.get("output")).doubleValue()).reversed());
        return rows;
    }

    /** 每小时的产量，用来画当天的柱状图 */
    private List<Map<String, Object>> byHour(List<ProdHourly> all, long from, long to) {
        Map<Long, Double> output = new LinkedHashMap<>();
        Map<Long, Double> run = new LinkedHashMap<>();
        for (ProdHourly p : all) {
            output.merge(p.getHourStart(), p.getOutput(), Double::sum);
            run.merge(p.getHourStart(), p.getRunSec(), Double::sum);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        long hour = from - (from % 3_600_000L);
        while (hour < to) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hourStart", hour);
            m.put("hour", Instant.ofEpochMilli(hour).atZone(ZoneId.systemDefault()).getHour());
            m.put("output", round1(output.getOrDefault(hour, 0d)));
            m.put("runSec", round1(run.getOrDefault(hour, 0d)));
            rows.add(m);
            hour += 3_600_000L;
        }
        return rows;
    }

    /**
     * 停机原因排行。
     * <p>
     * 数据来自停机类告警：每一次「停机超过 N 分钟」就是一次停机事故，
     * 规则名就是原因分类（比如「工作时间停机超 10 分钟」「缺料停机」）。
     * 想细分原因就在报警规则里多建几条，班组长按现场情况选用。
     */
    private List<Map<String, Object>> stopReasons(long from, long to) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (from >= to) {
            return rows;
        }
        List<AlarmEvent> events;
        try {
            events = alarmRepo.findByStartedAtBetween(from, to);
        } catch (Exception e) {
            return rows;                    // 报警表查不了不能把整个报表带崩
        }
        Map<String, Sum2> agg = new LinkedHashMap<>();
        for (AlarmEvent e : events) {
            if (!AlarmRule.TYPE_STOP.equals(e.getRuleType())
                    && !AlarmRule.TYPE_OFFLINE.equals(e.getRuleType())) {
                continue;
            }
            Sum2 s = agg.computeIfAbsent(e.getRuleName() == null ? "未命名" : e.getRuleName(),
                    k -> new Sum2());
            s.count++;
            if (e.getClearedAt() > 0) {
                s.totalMs += e.getClearedAt() - e.getStartedAt();
            } else {
                s.openCount++;
                s.totalMs += System.currentTimeMillis() - e.getStartedAt();
            }
            s.devices.add(e.getDeviceCode());
        }
        for (Map.Entry<String, Sum2> e : agg.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", e.getKey());
            m.put("count", e.getValue().count);
            m.put("openCount", e.getValue().openCount);
            m.put("totalMin", round1(e.getValue().totalMs / 60_000d));
            m.put("devices", new ArrayList<>(e.getValue().devices));
            rows.add(m);
        }
        rows.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> ((Number) m.get("totalMin")).doubleValue()).reversed());
        return rows;
    }

    private static final class Sum2 {
        int count;
        int openCount;
        long totalMs;
        Set<String> devices = new LinkedHashSet<>();
    }

    /* ==================== 工具 ==================== */

    public long[] dayWindow(LocalDate d) {
        ZoneId zone = ZoneId.systemDefault();
        long from = d.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return new long[]{from, to};
    }

    private String locationOf(String code) {
        try {
            var d = deviceService.byCode(code);
            return d == null || d.getLocation() == null ? "" : d.getLocation();
        } catch (Exception e) {
            return "";
        }
    }

    static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
