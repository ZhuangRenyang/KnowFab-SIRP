package com.factory.hub.demand;

import com.factory.hub.model.DeviceEntity;
import com.factory.hub.service.DeviceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需量与基本电费查询。
 * <p>
 * 只算三件事：本月最大需量是多少、按哪种方式交基本电费更划算、现在离合同需量还有多少余量。
 * <p>
 * <b>为什么不做「本月预计需量」：</b>
 * 需量是取最大值，不是累加。这个月已经出现过的峰值不会被后面的数据拉低，
 * 所以「预计」等于「当前峰值」，做一条平滑的预测曲线纯属糊弄人。
 * 客户真正需要的是「现在还剩多少余量」，那是减法，不是预测。
 */
@Service
public class DemandService {

    /** 全厂口径 */
    public static final String ALL = "ALL";

    private static final long WINDOW_MS = 900_000L;

    private final DemandWindowRepository repo;
    private final DemandStore store;
    private final DemandTracker tracker;
    private final DeviceService deviceService;

    public DemandService(DemandWindowRepository repo, DemandStore store,
                         DemandTracker tracker, DeviceService deviceService) {
        this.repo = repo;
        this.store = store;
        this.tracker = tracker;
        this.deviceService = deviceService;
    }

    /* ==================== 概览 ==================== */

    public Map<String, Object> overview(String monthParam, String device) {
        YearMonth month = parseMonth(monthParam);
        String dev = (device == null || device.isBlank()) ? ALL : device;

        // ---- 进线（总表）口径 ----
        // 全厂需量只该累加**总表**：机床的电已经包含在总表里了，一起加就是重复计算。
        // 演示库实测（2026-11-04）：总表峰值 235.8kW、机床之和 671kW，
        // 相加后是真实值的 3.85 倍 —— 需量虚高、基本电费虚高、合同需量告警误报。
        //
        // 客户还没勾选任何总表时**退回旧口径**（不偷偷改客户现有数字），
        // 但返回 meterMode=LEGACY，界面必须明确告知口径问题 —— 不说就等于让客户
        // 拿着一个虚高的数去跟供电局对账。
        List<DeviceEntity> meters = deviceService.meters();
        List<String> meterCodes = meters.stream().map(DeviceEntity::getCode).toList();
        boolean legacy = meterCodes.isEmpty();

        // 配置：全厂用全局那份；指定某块总表就用它自己那份（没配过则回退全局）
        DemandConfig cfg = ALL.equals(dev) ? currentConfig() : store.forMeter(dev);

        ZoneId zone = ZoneId.systemDefault();
        long from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long now = System.currentTimeMillis();

        List<DemandWindow> rows;
        if (ALL.equals(dev)) {
            rows = legacy
                    ? repo.findByWindowStartBetween(from, Math.min(to, now))
                    : repo.findByDeviceCodeInAndWindowStartBetween(meterCodes, from, Math.min(to, now));
        } else {
            rows = repo.findByDeviceCodeAndWindowStartBetween(dev, from, Math.min(to, now));
        }

        // 全厂口径要把同一窗口的几块表加起来，单表口径直接取自己的值
        Map<Long, Double> byWindow = new LinkedHashMap<>();
        double monthKwh = 0;
        for (DemandWindow w : rows) {
            byWindow.merge(w.getWindowStart(), ALL.equals(dev) ? w.getDemandKw() : w.getDemandKw(),
                    Double::sum);
            monthKwh += w.getKwh();
        }

        // 当前窗口还没落库，补进来才看得到实时值
        long curWin = tracker.currentWindowStart(now);
        double liveKw = tracker.currentWindowKw(ALL.equals(dev) ? null : dev, now,
                legacy ? null : new java.util.HashSet<>(meterCodes));
        if (liveKw > 0) {
            byWindow.merge(curWin, liveKw, (a, b) -> Math.max(a, b));
        }

        long peakWin = 0;
        double peakKw = 0;
        for (Map.Entry<Long, Double> e : byWindow.entrySet()) {
            if (e.getValue() > peakKw) {
                peakKw = e.getValue();
                peakWin = e.getKey();
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("month", month.toString());
        out.put("device", dev);
        out.put("deviceName", ALL.equals(dev) ? "全厂" : nameOf(dev));
        out.put("config", cfg);
        // 口径标记：LEGACY = 没标总表，全厂含所有设备（可能重复计算），界面要提示
        out.put("meterMode", legacy ? "LEGACY" : "METER");
        out.put("meters", metersOf(meters));
        out.put("monthMax", Map.of(
                "kw", round1(peakKw),
                "at", peakWin,
                "atText", peakWin > 0 ? fmt(peakWin, "MM-dd HH:mm") : "--"));
        out.put("current", Map.of(
                "windowStart", curWin,
                "kw", round1(liveKw),
                "elapsedSec", (now - curWin) / 1000,
                "windowSec", WINDOW_MS / 1000));
        out.put("monthKwh", round1(monthKwh));
        out.put("billing", billing(cfg, peakKw));
        out.put("status", status(cfg, peakKw));

        // 今天才刚开始采（或者刚装好），今日曲线几乎是空的。
        // 空图配一条合同线，客户会以为功能坏了，所以退回昨天给他看形状。
        // 判据不是「峰值为 0」——刚重启后可能已经攒了一两个窗口，
        // 图上只有一两根柱子比空白更让人犯嘀咕，所以少于 8 个窗口（2 小时）就退回昨天。
        Map<String, Object> series = daySeries(rows, dev, LocalDate.now(zone), now);
        if (nonEmptyWindows(series) < 8) {
            LocalDate y = LocalDate.now(zone).minusDays(1);
            long ys = y.atStartOfDay(zone).toInstant().toEpochMilli();
            long ye = y.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            List<DemandWindow> yRows = ALL.equals(dev)
                    ? repo.findByWindowStartBetween(ys, ye)
                    : repo.findByDeviceCodeAndWindowStartBetween(dev, ys, ye);
            Map<String, Object> yb = daySeries(yRows, dev, y, now);
            if (nonEmptyWindows(yb) >= 8) {
                yb.put("fallback", true);
                series = yb;
            }
        }
        out.put("today", series);
        return out;
    }

    /** 基本电费两种算法都摆出来，客户自己看哪种划算 */
    /**
     * 试算：用一组「还不想保存」的参数，按当前月峰值算基本电费。
     * <p>
     * <b>为什么要有这个接口</b>：客户现场常见「我这个月要是把合同需量报成 400 会怎样」
     * 「换成按容量计费划不划算」这类问题。以前只能改配置→保存→看结果→再改回来，
     * 改一次就写一次审计、还影响别人看到的数。
     * <p>
     * <b>试算不落库、不写审计、不影响任何人的配置</b>——
     * 它只是把 {@link #billing} 和 {@link #status} 这两段纯计算
     * 喂给一组临时参数跑一遍。口径只有一处（就是那两个私有方法），
     * 前端不自己算，避免"同一笔电费两个地方算"。
     * <p>
     * 峰值来自真实数据（不可改），可改的是合同需量、两个单价、变压器容量与计费方式。
     */
    public Map<String, Object> preview(DemandConfig probe, String monthParam, String device) {
        // 峰值取真实数据：想要"如果峰值是 X"这种假设，那是另一个功能，不在试算里
        Map<String, Object> real = overview(monthParam, device);
        double peakKw = 0;
        Object mm = real.get("monthMax");
        if (mm instanceof Map<?, ?> m && m.get("kw") instanceof Number n) {
            peakKw = n.doubleValue();
        }

        DemandConfig saved = currentConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("peakKw", round1(peakKw));
        out.put("billing", billing(probe, peakKw));
        out.put("status", status(probe, peakKw));
        out.put("saved", configMap(saved));
        out.put("probe", configMap(probe));
        // 与已保存配置是否有差异：前端据此显示「未保存」标记与「保存为默认」按钮
        out.put("changed", !sameNumbers(saved, probe));
        out.put("warnings", probe.validate());
        return out;
    }

    /** 配置的对外形状（试算结果里要回显"已保存的是多少"，前端好做对比） */
    private Map<String, Object> configMap(DemandConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contractDemandKw", c.getContractDemandKw());
        m.put("demandPrice", c.getDemandPrice());
        m.put("transformerKva", c.getTransformerKva());
        m.put("capacityPrice", c.getCapacityPrice());
        m.put("billingMode", c.getBillingMode());
        m.put("warnRatio", c.getWarnRatio());
        m.put("enabled", c.isEnabled());
        return m;
    }

    /**
     * 五个**参与计算**的数字是否有变。
     * <p>
     * 只比这几个：warnRatio 影响预警文案不影响钱，enabled 只是开关，
     * 它们变了不算「试算参数变了」，不该让「保存」按钮亮起来。
     */
    private boolean sameNumbers(DemandConfig a, DemandConfig b) {
        return a.getContractDemandKw() == b.getContractDemandKw()
                && a.getDemandPrice() == b.getDemandPrice()
                && a.getTransformerKva() == b.getTransformerKva()
                && a.getCapacityPrice() == b.getCapacityPrice()
                && String.valueOf(a.getBillingMode()).equals(String.valueOf(b.getBillingMode()));
    }

    private Map<String, Object> billing(DemandConfig cfg, double peakKw) {
        double demandCost = cfg.demandCost(peakKw);
        double capacityCost = cfg.capacityCost();
        String mode = cfg.effectiveMode(peakKw);
        double cost = DemandConfig.MODE_CAPACITY.equals(mode) ? capacityCost : demandCost;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("modeText", DemandConfig.MODE_DEMAND.equals(mode) ? "按需量" : "按容量");
        m.put("demandCost", round2(demandCost));
        m.put("capacityCost", round2(capacityCost));
        m.put("cost", round2(cost));
        m.put("saving", round2(Math.abs(demandCost - capacityCost)));
        m.put("formula", DemandConfig.MODE_DEMAND.equals(mode)
                ? String.format("%s kW × %s 元/kW·月", round1(peakKw), trim(cfg.getDemandPrice()))
                : String.format("%s kVA × %s 元/kVA·月", trim(cfg.getTransformerKva()), trim(cfg.getCapacityPrice())));
        return m;
    }

    /** 总表（进线）列表：给前端做选择器用 */
    private static List<Map<String, Object>> metersOf(List<DeviceEntity> meters) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DeviceEntity d : meters) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", d.getCode());
            m.put("name", d.getName());
            m.put("location", d.getLocation());
            list.add(m);
        }
        return list;
    }

    /** 距离合同需量还有多少余量 */
    private Map<String, Object> status(DemandConfig cfg, double peakKw) {
        double contract = cfg.getContractDemandKw();
        double ratio = contract > 0 ? peakKw / contract : 0;
        double headroom = Math.max(0, contract - peakKw);
        String level;
        String message;
        if (!cfg.isEnabled()) {
            level = "OFF";
            message = "基本电费预警已关闭，只在页面上显示数据。";
        } else if (ratio > 1.0) {
            level = "OVER";
            message = String.format(
                    "本月最大需量 %s kW，已超合同 %s kW（%.0f%%）。这个月的最大需量已经定死，"
                            + "基本电费按 %s kW 计；超容部分有的地区按 2 倍加收，去核一下电费单。",
                    round1(peakKw), trim(contract), (ratio - 1) * 100, round1(peakKw));
        } else if (ratio >= cfg.getWarnRatio()) {
            level = "WARN";
            message = String.format(
                    "本月最大需量 %s kW，已到合同需量的 %.0f%%，还剩 %s kW 余量。"
                            + "再冲上去峰值就改不回来了，高峰时段把非关键设备错开。",
                    round1(peakKw), ratio * 100, round1(headroom));
        } else {
            level = "OK";
            message = String.format("本月最大需量 %s kW，距合同需量 %s kW 还有 %s kW 余量。",
                    round1(peakKw), trim(contract), round1(headroom));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("ratio", round1(ratio * 100));
        m.put("headroomKw", round1(headroom));
        m.put("contractKw", contract);
        m.put("message", message);
        return m;
    }

    /** 某一天 96 个窗口的需量曲线，缺的窗口补 0（停机就是 0，不是没数据） */
    private Map<String, Object> daySeries(List<DemandWindow> rows, String dev,
                                          LocalDate date, long now) {
        ZoneId zone = ZoneId.systemDefault();
        long dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();

        Map<Long, Double> byWindow = new LinkedHashMap<>();
        for (DemandWindow w : rows) {
            if (w.getWindowStart() >= dayStart && w.getWindowStart() < dayEnd) {
                byWindow.merge(w.getWindowStart(), w.getDemandKw(), Double::sum);
            }
        }
        long curWin = tracker.currentWindowStart(now);
        if (curWin >= dayStart && curWin < dayEnd) {
            double liveKw = tracker.currentWindowKw(ALL.equals(dev) ? null : dev, now);
            if (liveKw > 0) {
                byWindow.merge(curWin, liveKw, Math::max);
            }
        }

        List<Map<String, Object>> series = new ArrayList<>();
        double dayPeak = 0;
        long dayPeakWin = 0;
        for (int i = 0; i < 96; i++) {
            long win = dayStart + i * WINDOW_MS;
            if (win > now) {
                break;
            }
            double kw = byWindow.getOrDefault(win, 0d);
            if (kw > dayPeak) {
                dayPeak = kw;
                dayPeakWin = win;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", fmt(win, "HH:mm"));
            p.put("kw", round1(kw));
            series.add(p);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date.toString());
        m.put("isToday", date.equals(LocalDate.now(zone)));
        m.put("series", series);
        m.put("peakKw", round1(dayPeak));
        m.put("peakAt", dayPeakWin > 0 ? fmt(dayPeakWin, "HH:mm") : "--");
        return m;
    }

    /* ==================== 给预警任务用 ==================== */

    public DemandConfig currentConfig() {
        return store.current();
    }

    /** 当月峰值（全厂），预警任务每 30 秒问一次 */
    public double monthPeakKw(YearMonth month) {
        ZoneId zone = ZoneId.systemDefault();
        long from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long to = Math.min(month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                System.currentTimeMillis());
        Map<Long, Double> byWindow = new LinkedHashMap<>();
        for (DemandWindow w : repo.findByWindowStartBetween(from, to)) {
            byWindow.merge(w.getWindowStart(), w.getDemandKw(), Double::sum);
        }
        double live = tracker.currentWindowKw(null, System.currentTimeMillis());
        if (live > 0) {
            byWindow.merge(tracker.currentWindowStart(System.currentTimeMillis()), live, Math::max);
        }
        return byWindow.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    /* ==================== 工具 ==================== */

    /** 这一天有多少个窗口真的采到了数据（kw > 0） */
    @SuppressWarnings("unchecked")
    private int nonEmptyWindows(Map<String, Object> day) {
        Object s = day.get("series");
        if (!(s instanceof List<?> list)) {
            return 0;
        }
        int n = 0;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("kw") instanceof Number num && num.doubleValue() > 0) {
                n++;
            }
        }
        return n;
    }

    private YearMonth parseMonth(String s) {
        try {
            if (s != null && !s.isBlank()) {
                return YearMonth.parse(s.trim());
            }
        } catch (Exception ignored) {
        }
        return YearMonth.now();
    }

    private String nameOf(String code) {
        try {
            var d = deviceService.byCode(code);
            return d == null ? code : d.getName();
        } catch (Exception e) {
            return code;
        }
    }

    private static String fmt(long ts, String pattern) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(pattern));
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }

    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
