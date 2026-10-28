package com.factory.hub.erp;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.report.ProdHourly;
import com.factory.hub.report.ProdHourlyRepository;
import com.factory.hub.service.DeviceService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机器级产量：配置（绑定成品 SKU / 目标仓库 / 每日清零时刻 / 自动入库）与统计。
 * <p>
 * 口径（用户需求原文：每个机器单独设置每天几点清零产量，记录每天/每月/每年产多少）：
 * <ul>
 *   <li><b>日产量</b>：按设备自己的 prod_reset_hour 切统计日——填 8 就是"早 8 点到次日 8 点"，
 *       夜班产量不会跑到交班那天的前一天去。数据源是 prod_hourly.output（增量已处理计数器清零）</li>
 *   <li><b>月/年产量</b>：按自然月/自然年汇总</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/erp/prod")
public class ErpProdController {

    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;

    private final DeviceService deviceService;
    private final ProdHourlyRepository prodRepo;
    private final ErpSkuRepository skuRepo;
    private final ErpWarehouseRepository whRepo;
    private final SnapshotStore store;
    private final ObjectMapper mapper;

    public ErpProdController(DeviceService deviceService, ProdHourlyRepository prodRepo,
                             ErpSkuRepository skuRepo, ErpWarehouseRepository whRepo,
                             SnapshotStore store, ObjectMapper mapper) {
        this.deviceService = deviceService;
        this.prodRepo = prodRepo;
        this.skuRepo = skuRepo;
        this.whRepo = whRepo;
        this.store = store;
        this.mapper = mapper;
    }

    /** 设备有没有 count 角色的点（决定详情页要不要画产量图） */
    private boolean hasCountPoint(DeviceEntity d) {
        try {
            List<Map<String, Object>> pts = mapper.readValue(
                    d.getPointsJson() == null ? "[]" : d.getPointsJson(),
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            return pts.stream().anyMatch(p -> "count".equals(p.get("role")));
        } catch (Exception e) {
            return false;
        }
    }

    /** 配置 + 统计一次给全：详情页只发这一个请求 */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String code) {
        DeviceEntity d = deviceService.byCode(code);
        Map<String, Object> out = new LinkedHashMap<>();
        if (d == null) {
            out.put("ok", false);
            out.put("message", "设备不存在");
            return out;
        }
        out.put("ok", true);
        out.put("auto", d.isProdAutoStockin());
        out.put("skuId", d.getProdSkuId());
        out.put("warehouseId", d.getProdWhId());
        out.put("resetHour", d.getProdResetHour());
        out.put("hasCount", hasCountPoint(d));
        if (d.getProdSkuId() != null) {
            skuRepo.findById(d.getProdSkuId()).ifPresent(s -> out.put("skuName", s.getName()));
        }
        if (d.getProdWhId() != null) {
            whRepo.findById(d.getProdWhId()).ifPresent(w -> out.put("warehouseName", w.getName()));
        }

        int resetHour = d.getProdResetHour();
        long now = System.currentTimeMillis();

        // 统计日窗口：以清零时刻为界（本地时区），今天 = 从今天（或昨天）的 resetHour 点起
        long todayKey = ProdDay.dayKey(now, resetHour);
        long todayStart = ProdDay.dayStart(now, resetHour);

        // 取今年 1 月 1 日（往前多留一个统计日，处理清零时刻跨年边界）到现在的所有小时行
        long yearStart = LocalDate.now(ZoneId.systemDefault()).withDayOfYear(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        yearStart = Math.min(yearStart, todayStart);

        List<ProdHourly> rows =
                prodRepo.findByDeviceCodeAndHourStartBetween(code, yearStart, now);

        double today = 0, month = 0, year = 0;
        long monthKey = floorMonth(now);
        double[] months = new double[12];
        for (ProdHourly p : rows) {
            double o = p.getOutput();
            year += o;
            long mk = floorMonth(p.getHourStart());
            if (mk == monthKey) {
                month += o;
                months[monthOf(p.getHourStart())] += o;
            }
            if (p.getHourStart() >= todayStart
                    && ProdDay.dayKey(p.getHourStart(), resetHour) == todayKey) {
                today += o;
            }
        }
        out.put("today", round2(today));
        out.put("month", round2(month));
        out.put("year", round2(year));
        List<Map<String, Object>> ml = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("month", i + 1);
            mm.put("qty", round2(months[i]));
            ml.add(mm);
        }
        out.put("months", ml);
        return out;
    }

    /** 近 N 天每日产量折线（按设备清零时刻切日），当前统计日也会带出来并继续涨 */
    @GetMapping("/daily")
    public List<Map<String, Object>> daily(@RequestParam String code,
                                           @RequestParam(defaultValue = "30") int days) {
        DeviceEntity d = deviceService.byCode(code);
        List<Map<String, Object>> out = new ArrayList<>();
        if (d == null) {
            return out;
        }
        int n = Math.max(1, Math.min(400, days));
        long now = System.currentTimeMillis();
        int rh = d.getProdResetHour();
        long todayKey = ProdDay.dayKey(now, rh);
        long from = ProdDay.startOfDayKey(todayKey - n + 1, rh);

        List<ProdHourly> rows = prodRepo.findByDeviceCodeAndHourStartBetween(code, from, now);
        Map<Long, Double> byDay = new LinkedHashMap<>();
        for (long k = todayKey - n + 1; k <= todayKey; k++) {
            byDay.put(k, 0d);
        }
        for (ProdHourly p : rows) {
            byDay.merge(ProdDay.dayKey(p.getHourStart(), rh), p.getOutput(), Double::sum);
        }
        var fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd");
        for (Map.Entry<Long, Double> e : byDay.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", LocalDate.ofEpochDay(e.getKey()).format(fmt));
            m.put("qty", round2(e.getValue()));
            out.add(m);
        }
        return out;
    }

    /** 保存生产入库联动配置 */
    @PostMapping("/config")
    public Map<String, Object> config(@RequestBody Map<String, Object> body) {
        String code = str(body.get("code"));
        DeviceEntity d = deviceService.byCode(code);
        if (d == null) {
            return fail("设备不存在");
        }
        Long skuId = longOf(body.get("skuId"));
        if (skuId != null && skuRepo.findById(skuId).isEmpty()) {
            return fail("绑定的成品不存在");
        }
        Long whId = longOf(body.get("warehouseId"));
        if (whId != null && whRepo.findById(whId).isEmpty()) {
            return fail("目标仓库不存在");
        }
        int resetHour = body.get("resetHour") == null ? 0
                : (int) Math.max(0, Math.min(23, Number(body.get("resetHour"))));
        d.setProdSkuId(skuId);
        d.setProdWhId(whId);
        d.setProdResetHour(resetHour);
        d.setProdAutoStockin(Boolean.parseBoolean(String.valueOf(body.get("auto"))));
        deviceService.save(d);
        return ok("已保存：" + code + (d.isProdAutoStockin() ? " 已启用生产入库联动" : " 已关闭生产入库联动"));
    }

    /* ==================== 工具 ==================== */

    private static long floorMonth(long ts) {
        LocalDate d = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ts),
                ZoneId.systemDefault()).withDayOfMonth(1);
        return d.toEpochDay();
    }

    private static int monthOf(long ts) {
        return LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ts),
                ZoneId.systemDefault()).getMonthValue();
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100d;
    }

    private static double Number(Object o) {
        return Double.parseDouble(String.valueOf(o));
    }

    private static Long longOf(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isBlank() || "null".equals(s)) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static Map<String, Object> ok(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", msg);
        return m;
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", msg);
        return m;
    }
}
