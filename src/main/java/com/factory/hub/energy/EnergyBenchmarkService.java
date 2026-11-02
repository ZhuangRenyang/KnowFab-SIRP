package com.factory.hub.energy;

import com.factory.hub.erp.ErpProdLine;
import com.factory.hub.manual.ManualEntryService;
import com.factory.hub.erp.ErpProdLineRepository;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.report.ProdHourly;
import com.factory.hub.report.ProdHourlyRepository;
import com.factory.hub.report.ProdRollup;
import com.factory.hub.service.DeviceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 能效对标（DH-12）：实测算「单位产品综合能耗」，和国标限额值比。
 * <p>
 * <b>口径：</b>单位产品能耗 = 区间内该范围的总电量 ÷ 同一区间内的总产量。
 * 电量取 {@code energy_hourly}（含当前小时内存实时值），产量取 {@code prod_hourly}
 * （含当前小时内存实时值）——和能耗页、产量报表完全同源，不另算一套。
 * <p>
 * <b>为什么分母没产量时不下结论：</b>没有产量还算「每件耗多少电」，
 * 要么除零，要么拿电费除以 0 件得出天文数字。这种情况直接标 {@code NO_OUTPUT}，
 * 让客户先补产量或补录，不给假结论。
 */
@Service
public class EnergyBenchmarkService {

    /** 判定结果：越小越好，超过上一档就落到下一档 */
    public static final String ST_ADVANCED = "ADVANCED";   // 优于先进值
    public static final String ST_ACCESS = "ACCESS";       // 达准入值
    public static final String ST_LIMIT = "LIMIT";         // 限值内，未超标
    public static final String ST_EXCEED = "EXCEED";       // 超限额
    public static final String ST_NO_OUTPUT = "NO_OUTPUT"; // 没有产量，算不出单耗
    public static final String ST_SCOPE_EMPTY = "SCOPE_EMPTY"; // 对标对象下没有设备

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final EnergyRepository energyRepo;
    private final ProdHourlyRepository prodRepo;
    private final EnergyRollup energyRollup;
    private final ProdRollup prodRollup;
    private final DeviceService deviceService;
    private final ErpProdLineRepository lineRepo;
    private final BenchmarkStore store;
    private final ManualEntryService manualService;

    public EnergyBenchmarkService(EnergyRepository energyRepo,
                                  ProdHourlyRepository prodRepo,
                                  EnergyRollup energyRollup,
                                  ProdRollup prodRollup,
                                  DeviceService deviceService,
                                  ErpProdLineRepository lineRepo,
                                  BenchmarkStore store,
                                  ManualEntryService manualService) {
        this.energyRepo = energyRepo;
        this.prodRepo = prodRepo;
        this.energyRollup = energyRollup;
        this.prodRollup = prodRollup;
        this.deviceService = deviceService;
        this.lineRepo = lineRepo;
        this.store = store;
        this.manualService = manualService;
    }

    /**
     * 生成对标报表。
     *
     * @param range today / 7d / month
     */
    public Map<String, Object> report(String range) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        String r = range == null ? "month" : range;
        long from;
        switch (r) {
            case "today" -> from = startOf(today, zone);
            case "7d" -> from = startOf(today.minusDays(6), zone);
            default -> {
                r = "month";
                from = startOf(today.withDayOfMonth(1), zone);
            }
        }
        long curHour = energyRollup.currentHourStart();
        long to = curHour > 0 ? curHour : System.currentTimeMillis();

        // 1) 每台设备电量：DB 已结算小时 + 当前小时实时（与能耗页同口径）
        Map<String, Double> kwhByDevice = new HashMap<>();
        for (Object[] row : energyRepo.sumByDevice(from, to)) {
            if (row[0] != null) {
                kwhByDevice.merge(String.valueOf(row[0]), d(row[1]), Double::sum);
            }
        }
        if (curHour >= from) {
            for (Map.Entry<String, double[]> e : energyRollup.liveByDevice().entrySet()) {
                kwhByDevice.merge(e.getKey(), e.getValue()[0], Double::sum);
            }
        }

        // 2) 每台设备产量：DB 已落库小时 + 当前小时实时
        //    注意 sumByDevice / findInWindow 的上界都是 curHour（不含），
        //    所以这里加实时值不会和历史行重复计算。
        Map<String, Double> outByDevice = new HashMap<>();
        for (ProdHourly p : prodRepo.findInWindow(from, to)) {
            outByDevice.merge(p.getDeviceCode(), p.getOutput(), Double::sum);
        }
        if (curHour >= from) {
            for (DeviceEntity dev : deviceService.all()) {
                double live = prodRollup.liveOutput(dev.getCode(), curHour);
                if (!Double.isNaN(live) && live != 0d) {
                    outByDevice.merge(dev.getCode(), live, Double::sum);
                }
            }
        }

        // 补录（DH-13）并进分子/分母：月报和单耗要能和报表对上
        LocalDate fromDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate();
        for (Map.Entry<String, double[]> e : manualService.byDevice(fromDate, today).entrySet()) {
            kwhByDevice.merge(e.getKey(), e.getValue()[0], Double::sum);
            outByDevice.merge(e.getKey(), e.getValue()[2], Double::sum);
        }

        double totalKwh = 0d;
        for (double v : kwhByDevice.values()) {
            totalKwh += v;
        }
        double totalOut = 0d;
        for (double v : outByDevice.values()) {
            totalOut += v;
        }

        // 3) 设备 / 产线索引，用来解析「车间 / 产线 / 设备」范围
        List<DeviceEntity> devices = deviceService.all();
        Map<Long, ErpProdLine> lineById = new HashMap<>();
        for (ErpProdLine l : lineRepo.findAll()) {
            lineById.put(l.getId(), l);
        }
        Map<String, List<DeviceEntity>> byWorkshop = new HashMap<>();
        for (DeviceEntity dev : devices) {
            ErpProdLine line = dev.getProdLineId() == null ? null : lineById.get(dev.getProdLineId());
            String ws = workshopOf(line);
            byWorkshop.computeIfAbsent(ws, k -> new ArrayList<>()).add(dev);
        }

        // 4) 逐条对标
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int advanced = 0, access = 0, limit = 0, exceed = 0, noOutput = 0, scopeEmpty = 0;
        for (EnergyBenchmark bm : store.list()) {
            if (!bm.isEnabled()) {
                continue;
            }
            for (String w : bm.validate()) {
                warnings.add(bm.displayName() + "：" + w);
            }
            Set<String> codes = resolveCodes(bm, devices, lineById, byWorkshop);
            boolean plant = EnergyBenchmark.SCOPE_PLANT.equals(bm.normScope());
            double kwh;
            double output;
            if (plant) {
                kwh = totalKwh;
                output = totalOut;
            } else if (codes.isEmpty()) {
                kwh = 0d;
                output = 0d;
            } else {
                kwh = 0d;
                output = 0d;
                for (String code : codes) {
                    kwh += kwhByDevice.getOrDefault(code, 0d);
                    output += outByDevice.getOrDefault(code, 0d);
                }
            }

            Double unitKwh = null;
            String status;
            if (!plant && codes.isEmpty()) {
                status = ST_SCOPE_EMPTY;
                scopeEmpty++;
            } else if (output <= 0d) {
                status = ST_NO_OUTPUT;
                noOutput++;
            } else {
                unitKwh = kwh / output;
                status = judge(unitKwh, bm);
                switch (status) {
                    case ST_ADVANCED -> advanced++;
                    case ST_ACCESS -> access++;
                    case ST_EXCEED -> exceed++;
                    default -> limit++;
                }
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", bm.getId());
            m.put("name", bm.displayName());
            m.put("standardNo", bm.getStandardNo());
            m.put("scopeType", bm.normScope());
            m.put("scopeValue", bm.getScopeValue());
            m.put("scopeLabel", scopeLabel(bm, lineById, devices));
            m.put("unit", bm.getUnit());
            m.put("kwh", round1(kwh));
            m.put("output", round1(output));
            m.put("unitKwh", unitKwh == null ? null : round3(unitKwh));
            m.put("advancedValue", bm.getAdvancedValue());
            m.put("accessValue", bm.getAccessValue());
            m.put("limitValue", bm.getLimitValue());
            m.put("vsAdvanced", ratio(unitKwh, bm.getAdvancedValue()));
            m.put("vsAccess", ratio(unitKwh, bm.getAccessValue()));
            m.put("vsLimit", ratio(unitKwh, bm.getLimitValue()));
            m.put("status", status);
            m.put("note", bm.getNote());
            rows.add(m);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", rows.size());
        summary.put("advanced", advanced);
        summary.put("access", access);
        summary.put("limit", limit);
        summary.put("exceed", exceed);
        summary.put("noOutput", noOutput);
        summary.put("scopeEmpty", scopeEmpty);
        // 一句话结论：有超标就红，否则看有没有数据缺口
        summary.put("verdict", exceed > 0 ? "EXCEED" : (noOutput > 0 || scopeEmpty > 0) ? "INCOMPLETE" : "OK");

        Map<String, Object> plant = new LinkedHashMap<>();
        plant.put("kwh", round1(totalKwh));
        plant.put("output", round1(totalOut));
        plant.put("unitKwh", totalOut > 0 ? round3(totalKwh / totalOut) : null);
        plant.put("unit", "kWh/件");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", r);
        out.put("fromText", DF.format(Instant.ofEpochMilli(from).atZone(zone).toLocalDate()));
        out.put("toText", DF.format(today));
        out.put("plant", plant);
        out.put("rows", rows);
        out.put("summary", summary);
        out.put("warnings", warnings);
        out.put("hasBenchmark", !rows.isEmpty());
        return out;
    }

    /** 按配置的三级值判定。能耗越低越好，从最严的先进值开始比 */
    private static String judge(double actual, EnergyBenchmark bm) {
        if (bm.getAdvancedValue() != null && actual <= bm.getAdvancedValue()) {
            return ST_ADVANCED;
        }
        if (bm.getAccessValue() != null && actual <= bm.getAccessValue()) {
            return ST_ACCESS;
        }
        if (bm.getLimitValue() != null && actual <= bm.getLimitValue()) {
            return ST_LIMIT;
        }
        // 只配了先进值/准入值、实测又超过它，同样算超标——没有限定值不代表没有红线
        return ST_EXCEED;
    }

    /** 解析范围对应的设备编码集合；全厂返回空集（调用方按全厂合计处理） */
    private Set<String> resolveCodes(EnergyBenchmark bm,
                                     List<DeviceEntity> devices,
                                     Map<Long, ErpProdLine> lineById,
                                     Map<String, List<DeviceEntity>> byWorkshop) {
        Set<String> codes = new HashSet<>();
        switch (bm.normScope()) {
            case EnergyBenchmark.SCOPE_WORKSHOP -> {
                List<DeviceEntity> list = byWorkshop.get(trim(bm.getScopeValue()));
                if (list != null) {
                    for (DeviceEntity d : list) {
                        codes.add(d.getCode());
                    }
                }
            }
            case EnergyBenchmark.SCOPE_LINE -> {
                Long lid = parseLong(bm.getScopeValue());
                if (lid != null) {
                    for (DeviceEntity d : devices) {
                        if (lid.equals(d.getProdLineId())) {
                            codes.add(d.getCode());
                        }
                    }
                }
            }
            case EnergyBenchmark.SCOPE_DEVICE -> {
                String code = trim(bm.getScopeValue());
                for (DeviceEntity d : devices) {
                    if (code.equals(d.getCode())) {
                        codes.add(d.getCode());
                    }
                }
            }
            default -> {
                // 全厂：不按设备集合过滤
            }
        }
        return codes;
    }

    private String scopeLabel(EnergyBenchmark bm, Map<Long, ErpProdLine> lineById, List<DeviceEntity> devices) {
        return switch (bm.normScope()) {
            case EnergyBenchmark.SCOPE_WORKSHOP -> trim(bm.getScopeValue());
            case EnergyBenchmark.SCOPE_LINE -> {
                Long lid = parseLong(bm.getScopeValue());
                ErpProdLine l = lid == null ? null : lineById.get(lid);
                yield l == null ? trim(bm.getScopeValue()) : l.getName();
            }
            case EnergyBenchmark.SCOPE_DEVICE -> {
                String code = trim(bm.getScopeValue());
                for (DeviceEntity d : devices) {
                    if (code.equals(d.getCode())) {
                        yield d.getName() == null ? code : d.getName();
                    }
                }
                yield code;
            }
            default -> "";
        };
    }

    private static String workshopOf(ErpProdLine line) {
        if (line == null || line.getWorkshop() == null || line.getWorkshop().isBlank()) {
            return "未标注车间";
        }
        return line.getWorkshop().trim();
    }

    /** 和全厂/对标值的偏差百分比。正数 = 比标杆更费电 */
    private static Double ratio(Double actual, Double bench) {
        if (actual == null || bench == null || bench <= 0d) {
            return null;
        }
        return Math.round((actual - bench) / bench * 1000d) / 10d;
    }

    private static long startOf(LocalDate d, ZoneId zone) {
        return d.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(trim(s));
        } catch (Exception e) {
            return null;
        }
    }

    private static double d(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0d;
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }

    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
