package com.factory.hub.report;

import com.factory.hub.alarm.AlarmEvent;
import com.factory.hub.alarm.AlarmEventRepository;
import com.factory.hub.alarm.AlarmRule;
import com.factory.hub.service.DeviceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
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

    public ReportService(ProdHourlyRepository repo, ShiftStore shiftStore,
                         AlarmEventRepository alarmRepo, DeviceService deviceService) {
        this.repo = repo;
        this.shiftStore = shiftStore;
        this.alarmRepo = alarmRepo;
        this.deviceService = deviceService;
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
            all = repo.findByHourStartBetween(from, to);
        } else {
            // 先算出所有班次的时间窗，一次把这段数据查回来，避免每个班次查一遍库
            List<long[]> windows = new ArrayList<>();
            for (ShiftPlan.Shift s : shifts) {
                long[] w = plan.windowOf(s, date);
                windows.add(w);
                from = Math.min(from, w[0]);
                to = Math.max(to, w[1]);
            }
            all = repo.findByHourStartBetween(from, to);

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
        out.put("warnings", plan.validate());
        return out;
    }

    /** 最近 N 天的每日汇总，给趋势图用 */
    public Map<String, Object> range(LocalDate to, int days) {
        int n = Math.max(1, Math.min(days, 90));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            LocalDate d = to.minusDays(i);
            long[] w = dayWindow(d);
            Sum sum = new Sum();
            for (ProdHourly p : repo.findByHourStartBetween(w[0], w[1])) {
                sum.add(p);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.putAll(sum.toMap());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("rows", rows);
        return out;
    }

    /* ==================== 聚合 ==================== */

    /** 一台设备（或全厂）在某段时间内的合计 */
    private static final class Sum {
        double run, idle, stop, alarm, unknown, output, backfill;
        Set<String> devices = new LinkedHashSet<>();

        void add(ProdHourly p) {
            run += p.getRunSec();
            idle += p.getIdleSec();
            stop += p.getStopSec();
            alarm += p.getAlarmSec();
            unknown += p.getUnknownSec();
            output += p.getOutput();
            backfill += p.getBackfill();
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
            m.put("deviceCount", devices.size());
            return m;
        }
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
