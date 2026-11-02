package com.factory.hub.manual;

import com.factory.hub.energy.TariffPlan;
import com.factory.hub.energy.TariffSegment;
import com.factory.hub.energy.TariffStore;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据补录（DH-13）。
 * <p>
 * 断网、抄表漏了、设备接入前那几天——不补，月报就永远缺几天，
 * 客户会拿这个说"系统不准"。补录单独存表，报表里单列「其中补录」，
 * 撤销后立即从统计中移除，采集数据一个字节都不动。
 */
@Service
public class ManualEntryService {

    private static final Logger log = LoggerFactory.getLogger(ManualEntryService.class);

    /** 一次最多补 92 天：再长就不是"补录"，是该核对接入方案了 */
    private static final int MAX_DAYS = 92;
    private static final double MAX_VALUE = 1_000_000_000d;

    private final ManualEntryRepository repo;
    private final DeviceService deviceService;
    private final TariffStore tariffStore;

    public ManualEntryService(ManualEntryRepository repo, DeviceService deviceService,
                              TariffStore tariffStore) {
        this.repo = repo;
        this.deviceService = deviceService;
        this.tariffStore = tariffStore;
    }

    /**
     * 新增补录。日期范围按天平均拆开，最后一天吃掉四舍五入的零头，
     * 保证「总量」和「逐日之和」完全相等——月报对账时这两个数必须一样。
     */
    @Transactional
    public Map<String, Object> create(ManualEntryRequest req, String operator) {
        Map<String, Object> out = new LinkedHashMap<>();
        String code = req == null || req.deviceCode() == null ? "" : req.deviceCode().trim();
        if (code.isEmpty()) {
            return fail(out, "请选择设备");
        }
        DeviceEntity dev = deviceService.byCode(code);
        if (dev == null) {
            return fail(out, "找不到设备 " + code);
        }
        LocalDate from = req.fromDay();
        LocalDate to = req.toDay() == null ? from : req.toDay();
        if (from == null) {
            return fail(out, "请选择补录日期");
        }
        if (to.isBefore(from)) {
            return fail(out, "结束日期不能早于开始日期");
        }
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (from.isAfter(today)) {
            return fail(out, "不能补录未来的日期");
        }
        if (to.isAfter(today)) {
            to = today;
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            return fail(out, "一次最多补 " + MAX_DAYS + " 天，当前选了 " + days + " 天");
        }

        double kwh = nz(req.kwh());
        double output = nz(req.output());
        if (kwh < 0 || output < 0) {
            return fail(out, "电量和产量不能是负数");
        }
        if (kwh <= 0 && output <= 0) {
            return fail(out, "补录电量或产量至少要填一个");
        }
        if (kwh > MAX_VALUE || output > MAX_VALUE) {
            return fail(out, "数值过大，请检查是否多输了几个 0");
        }
        // DH-15 不良数：null = 没录（数据缺失），0 = 录了「零不良」。两者不能混
        Double defect = req.defect();
        if (defect != null) {
            if (defect < 0) {
                return fail(out, "不良数不能是负数");
            }
            if (defect > MAX_VALUE) {
                return fail(out, "不良数过大，请检查是否多输了几个 0");
            }
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            return fail(out, "请填写补录原因（审计要查）");
        }
        if (reason.length() > 200) {
            reason = reason.substring(0, 200);
        }

        String batchId = "mb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        double perKwh = round2(kwh / days);
        double perOut = round2(output / days);
        long now = System.currentTimeMillis();
        List<ManualEntry> rows = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate day = from.plusDays(i);
            boolean last = i == days - 1;
            double dk = last ? round2(kwh - perKwh * (days - 1)) : perKwh;
            double dop = last ? round2(output - perOut * (days - 1)) : perOut;
            double cost = kwhCost(day, dk);
            rows.add(new ManualEntry(batchId, code, dev.getName(), day,
                    dk, cost, dop, defect, reason, operator, now));
        }
        repo.saveAll(rows);

        out.put("ok", true);
        out.put("message", "已补录 " + days + " 天");
        out.put("batchId", batchId);
        out.put("days", days);
        out.put("kwh", round2(kwh));
        out.put("output", round2(output));
        out.put("entries", rows.size());
        log.info("补录：{} {}~{} 共 {} 天，电量 {} kWh，产量 {} 件，原因：{}，操作人：{}",
                code, from, to, days, round2(kwh), round2(output), reason, operator);
        return out;
    }

    /** 撤销：只改标志位，记录保留可查。重复撤销返回 false */
    @Transactional
    public Map<String, Object> revoke(Long id, String operator) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (id == null) {
            return fail(out, "缺少记录 id");
        }
        ManualEntry e = repo.findById(id).orElse(null);
        if (e == null) {
            return fail(out, "没有找到这条补录记录");
        }
        if (e.isRevoked()) {
            out.put("ok", false);
            out.put("message", "这条补录已经撤销过了");
            return out;
        }
        e.setRevoked(true);
        e.setRevokedBy(operator);
        e.setRevokedAt(System.currentTimeMillis());
        repo.save(e);
        out.put("ok", true);
        out.put("message", "已撤销，报表不再计入这条补录");
        log.info("撤销补录 {}：{} {}，操作人：{}", id, e.getDeviceCode(), e.getDay(), operator);
        return out;
    }

    /** 补录记录列表（含已撤销），默认当月 */
    public Map<String, Object> list(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate f = from == null ? today.withDayOfMonth(1) : from;
        LocalDate t = to == null ? today : to;
        if (t.isBefore(f)) {
            LocalDate tmp = f;
            f = t;
            t = tmp;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ManualEntry e : repo.findByDayBetweenOrderByDayDescIdDesc(f, t)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("batchId", e.getBatchId());
            m.put("deviceCode", e.getDeviceCode());
            m.put("deviceName", e.getDeviceName());
            m.put("day", e.getDay().toString());
            m.put("kwh", round2(e.getKwh()));
            m.put("kwhCost", round2(e.getKwhCost()));
            m.put("output", round2(e.getOutput()));
            // DH-15：不良数。null = 没录（数据缺失），前端要显示「--」而不是 0
            m.put("defect", e.getDefect());
            m.put("reason", e.getReason());
            m.put("operator", e.getOperator());
            m.put("createdAt", e.getCreatedAt());
            m.put("revoked", e.isRevoked());
            m.put("revokedBy", e.getRevokedBy());
            m.put("revokedAt", e.getRevokedAt());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("rows", rows);
        return out;
    }

    /* ==================== 报表聚合（只算生效中的） ==================== */

    /** 某天生效的补录明细，日报和日报导出用 */
    public List<ManualEntry> activeOfDay(LocalDate day) {
        return repo.findByRevokedFalseAndDayBetweenOrderByDayAscIdAsc(day, day);
    }

    /** 区间内生效中的补录明细（升序），月报合并设备行用 */
    public List<ManualEntry> findActive(LocalDate from, LocalDate to) {
        return repo.findByRevokedFalseAndDayBetweenOrderByDayAscIdAsc(from, to);
    }

    /** 区间内按设备汇总：[code] → [kwh, kwhCost, output] */
    public Map<String, double[]> byDevice(LocalDate from, LocalDate to) {
        Map<String, double[]> out = new HashMap<>();
        for (Object[] r : repo.sumByDevice(from, to)) {
            out.put(String.valueOf(r[0]), new double[]{d(r[1]), d(r[2]), d(r[3])});
        }
        return out;
    }

    /** 区间内按天汇总：day → [kwh, kwhCost, output] */
    public Map<LocalDate, double[]> byDay(LocalDate from, LocalDate to) {
        Map<LocalDate, double[]> out = new HashMap<>();
        for (Object[] r : repo.sumByDay(from, to)) {
            LocalDate day = (LocalDate) r[0];
            out.put(day, new double[]{d(r[1]), d(r[2]), d(r[3])});
        }
        return out;
    }

    /** 区间合计 [kwh, kwhCost, output] */
    public double[] totals(LocalDate from, LocalDate to) {
        double[] sum = new double[3];
        for (double[] v : byDevice(from, to).values()) {
            sum[0] += v[0];
            sum[1] += v[1];
            sum[2] += v[2];
        }
        return sum;
    }

    /* ==================== 内部 ==================== */

    /**
     * 补录电量的电费：按当天 24 小时平均分摊，每小时用当时的分时电价。
     * 不按补录那一刻的电价算——欠的是白天的电，不能按夜里便宜的价格记。
     */
    private double kwhCost(LocalDate day, double kwh) {
        if (kwh <= 0) {
            return 0d;
        }
        ZoneId zone = ZoneId.systemDefault();
        TariffPlan plan = tariffStore.current();
        double perHour = kwh / 24d;
        double cost = 0d;
        for (int h = 0; h < 24; h++) {
            long ts = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli();
            TariffSegment seg = plan.segmentAt(ts);
            cost += perHour * (seg == null ? 0d : seg.getPrice());
        }
        return round2(cost);
    }

    private static Map<String, Object> fail(Map<String, Object> out, String msg) {
        out.put("ok", false);
        out.put("message", msg);
        return out;
    }

    private static double nz(Double v) {
        return v == null ? 0d : v;
    }

    private static double d(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0d;
    }

    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
