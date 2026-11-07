package com.factory.hub.erp.analysis;

import com.factory.hub.energy.EnergyRepository;
import com.factory.hub.energy.EnergyRollup;
import com.factory.hub.report.ProdHourlyRepository;
import com.factory.hub.report.ProdRollup;
import com.factory.hub.system.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.factory.hub.erp.ErpSku;
import com.factory.hub.erp.ErpSkuRepository;
import com.factory.hub.erp.production.ErpWorkOrder;
import com.factory.hub.erp.production.ErpWorkOrderRepository;
import com.factory.hub.erp.stock.ErpStockBill;
import com.factory.hub.erp.stock.ErpStockBillRepository;

/**
 * 成本核算（DH-74）：一张工单做下来，料、工、费各花了多少，单位成本是多少。
 * <p>
 * <b>口径写死在这里，不留给调用方猜</b>——「料工费」三个字每家公司理解都不一样，
 * 系统必须先把话说清楚，客户才好判断这个数能不能用：
 *
 * <pre>
 *   直接材料 = 该工单所有有效 MATERIAL_OUT 行的 |qty| × unit_price
 *   直接人工 = 工时 × 人工单价（系统设置 → 成本核算）
 *   制造费用 = 电费 + 工时 × 制造费用率
 *   总成本   = 材料 + 人工 + 制造费用
 *   单位成本 = 总成本 ÷ 完成量
 * </pre>
 *
 * <b>三个必须解释清楚的选择</b>：
 * <ol>
 *   <li><b>材料按实际领料算，不按 BOM 标准用量算</b>：标准成本适合报价，
 *       实际成本才是月底对账要的那个数。超领、试模浪费、料废重做全都进成本——
 *       这些本来就是真金白银花出去的。同时给出「标准用量成本」做对比，
 *       差额就是材料超耗，这是成本管控最该盯的一个数。</li>
 *   <li><b>工时取设备运行时长，不取「开工到完工」的墙钟时间</b>：
 *       墙钟时间包含停机、待料、换班，拿它乘人工单价等于把停机也算成人工，
 *       单位成本必然偏高。设备运行时长（{@code prod_hourly.run_sec}）才是人在干活的时间。
 *       设备没有数据时退化为墙钟，并在返回里标明用的是哪个（{@code hoursSource}），
 *       让人知道这个数有多可信。</li>
 *   <li><b>算不出来就返回 null，不返回 0</b>：没配工价、没开工、完成量为 0，
 *       这三种情况下单位成本是"算不出来"而不是"零成本"。返回 0 会让毛利虚高，
 *       客户拿着这份数去报价，赔了都不知道为什么。</li>
 * </ol>
 *
 * <b>不落表</b>：所有数字都从单据、产量、能耗实时算。存一份"成本快照"确实更快，
 * 但那样就有两个来源——领料单改了、电价换了之后，快照和实算必然对不上，
 * 而这种对不上没人能解释。工单量本来就不大，实时算一次是毫秒级的事。
 */
@Service
public class ErpCostService {

    private static final Logger log = LoggerFactory.getLogger(ErpCostService.class);

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final double SEC_PER_HOUR = 3600d;
    private static final long HOUR_MS = 3_600_000L;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ErpWorkOrderRepository woRepo;
    private final ErpStockBillRepository billRepo;
    private final ProdHourlyRepository prodRepo;
    private final ProdRollup prodRollup;
    private final EnergyRepository energyRepo;
    private final EnergyRollup energyRollup;
    private final ErpSkuRepository skuRepo;
    private final ErpBomService bomService;
    private final SettingStore settings;

    public ErpCostService(ErpWorkOrderRepository woRepo,
                          ErpStockBillRepository billRepo,
                          ProdHourlyRepository prodRepo,
                          ProdRollup prodRollup,
                          EnergyRepository energyRepo,
                          EnergyRollup energyRollup,
                          ErpSkuRepository skuRepo,
                          ErpBomService bomService,
                          SettingStore settings) {
        this.woRepo = woRepo;
        this.billRepo = billRepo;
        this.prodRepo = prodRepo;
        this.prodRollup = prodRollup;
        this.energyRepo = energyRepo;
        this.energyRollup = energyRollup;
        this.skuRepo = skuRepo;
        this.bomService = bomService;
        this.settings = settings;
    }

    /* ==================== 单张工单 ==================== */

    public Map<String, Object> orderCost(String orderNo) {
        ErpWorkOrder o = woRepo.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + orderNo));
        return costOf(o);
    }

    private Map<String, Object> costOf(ErpWorkOrder o) {
        long now = System.currentTimeMillis();
        boolean started = o.getActualStart() != null;
        long start = started ? o.getActualStart() : 0L;
        long end = o.getActualEnd() != null ? o.getActualEnd() : now;
        boolean inProgress = started && o.getActualEnd() == null;

        /* ---- 直接材料：实际领料 ---- */
        BigDecimal material = nz(billRepo.amountByOrderAndType(o.getOrderNo(), ErpStockBill.TYPE_MATERIAL_OUT));

        /* ---- 工时 ---- */
        // 产量与能耗表都是**按小时落库**的：直接查 [10:13, 10:14] 会一个都查不到——
        // 那个小时的行 hourStart 是 10:00，落在区间外。实测就是这样静默退化成墙钟的，
        // 表面上没报错，但人工成本其实是按"开工到完工"算的，含停机、偏高。
        // 所以必须先把区间扩到整小时边界。
        long fromHour = started ? start - (start % HOUR_MS) : 0L;
        long toHour = started ? ((end + HOUR_MS - 1) / HOUR_MS) * HOUR_MS : 0L;
        double wallSec = started ? (end - start) / 1000d : 0;
        double deviceSecRaw = 0;         // 该小时段设备的原始运行秒数
        double runSec = 0;
        String hoursSource = "none";
        if (started) {
            if (o.getDeviceCode() != null) {
                try {
                    long nowHour = now - (now % HOUR_MS);
                    // 已落库的小时：只查到「当前小时之前」
                    long dbTo = Math.min(toHour, nowHour);
                    if (dbTo > fromHour) {
                        deviceSecRaw = prodRepo.runSecOf(o.getDeviceCode(), fromHour, dbTo);
                    }
                    // 当前小时还没落库（整点才写），从内存桶补——不补的话，
                    // 上午 10:13 开的工单永远查不到 10 点那一小时，人工成本会退化成墙钟
                    if (toHour > nowHour) {
                        double live = prodRollup.liveRunSec(o.getDeviceCode(), nowHour);
                        if (!Double.isNaN(live) && live > 0) {
                            deviceSecRaw += live;
                        }
                    }
                    // 不能超过工单存在的时长：那一小时里设备可能还在干别的活，
                    // 直接拿去乘工价，等于把别的工单的人工算到这张单上
                    runSec = Math.min(deviceSecRaw, wallSec);
                    if (runSec > 0) {
                        hoursSource = "device";
                    }
                } catch (Exception e) {
                    log.debug("取设备运行时长失败（退化为墙钟）：{}", e.getMessage());
                }
            }
            if (runSec <= 0) {
                // 设备没有产量数据（手工工单、刚开工还没落小时表、设备未接）
                runSec = wallSec;
                hoursSource = "wallclock";
            }
        }
        double hours = runSec / SEC_PER_HOUR;

        /* ---- 人工与制造费用率 ---- */
        double laborRate = num(settings.get(SettingStore.COST_LABOR_RATE), 0);
        double overheadRate = num(settings.get(SettingStore.COST_OVERHEAD_RATE), 0);
        // 工价填 0 表示"不计人工"，返回 null 而不是 0——0 会让人以为算过了
        BigDecimal labor = (hours > 0 && laborRate > 0)
                ? round4(BigDecimal.valueOf(hours).multiply(BigDecimal.valueOf(laborRate))) : null;
        BigDecimal overheadRatePart = (hours > 0 && overheadRate > 0)
                ? round4(BigDecimal.valueOf(hours).multiply(BigDecimal.valueOf(overheadRate)))
                : BigDecimal.ZERO;

        /* ---- 电费：按设备实际电量，用落库时的电价（不拿今天的价重算历史）---- */
        BigDecimal electricity = BigDecimal.ZERO;
        double kwh = 0;
        if (started && o.getDeviceCode() != null) {
            try {
                // 与工时同样的道理：能耗也是整点落库的，区间要扩到整小时，
                // 当前小时还得从内存桶补（否则正在做的工单电费永远是 0）
                long nowHour = now - (now % HOUR_MS);
                long dbTo = Math.min(toHour, nowHour);
                double rawKwh = 0;
                double rawCost = 0;
                if (dbTo > fromHour) {
                    List<Object[]> r = energyRepo.ofDevice(o.getDeviceCode(), fromHour, dbTo);
                    if (r != null && !r.isEmpty() && r.get(0) != null) {
                        Object[] row = r.get(0);
                        rawKwh = row[0] == null ? 0 : ((Number) row[0]).doubleValue();
                        rawCost = row[1] == null ? 0 : ((Number) row[1]).doubleValue();
                    }
                }
                if (toHour > nowHour) {
                    Map<String, double[]> live = energyRollup.liveByDevice();
                    double[] v = live.get(o.getDeviceCode());
                    if (v != null) {
                        rawKwh += v[0];
                        rawCost += v[1];
                    }
                }
                // 整小时的电费不能全算给一张只开了 5 分钟的工单：
                // 按「工单工时 ÷ 该小时段设备运行工时」分摊。设备没运行就没电费可分。
                double share = deviceSecRaw > 0 ? Math.min(1d, runSec / deviceSecRaw) : 0d;
                kwh = rawKwh * share;
                electricity = round4(BigDecimal.valueOf(rawCost * share));
            } catch (Exception e) {
                log.debug("取设备电费失败：{}", e.getMessage());
            }
        }
        BigDecimal overhead = round4(overheadRatePart.add(electricity));

        /* ---- 合计 ---- */
        BigDecimal total = round4(material.add(labor == null ? BigDecimal.ZERO : labor).add(overhead));

        /* ---- 标准用量成本：与实际对比，差额就是材料超耗 ---- */
        BigDecimal standardMaterial = null;
        try {
            BigDecimal unit = bomService.unitMaterialCost(o.getProductSkuId());
            if (unit != null && o.getDoneQty().signum() > 0) {
                standardMaterial = round4(unit.multiply(o.getDoneQty()));
            }
        } catch (Exception e) {
            log.debug("取 BOM 单件成本失败：{}", e.getMessage());
        }
        BigDecimal materialVariance = standardMaterial == null ? null
                : round4(material.subtract(standardMaterial));

        /* ---- 单位成本 ---- */
        BigDecimal unitCost = o.getDoneQty().signum() > 0
                ? round4(total.divide(o.getDoneQty(), 6, RoundingMode.HALF_UP)) : null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("orderNo", o.getOrderNo());
        m.put("productSkuId", o.getProductSkuId());
        m.put("productName", o.getProductName());
        m.put("status", o.getStatus());
        m.put("doneQty", o.getDoneQty());
        m.put("defectQty", o.getDefectQty());

        m.put("materialCost", material);
        m.put("laborCost", labor);
        m.put("overheadCost", overhead);
        m.put("electricityCost", electricity);
        m.put("overheadRateCost", overheadRatePart);
        m.put("totalCost", total);
        m.put("unitCost", unitCost);

        m.put("standardMaterialCost", standardMaterial);
        m.put("materialVariance", materialVariance);

        m.put("hours", Math.round(hours * 10000d) / 10000d);
        m.put("runSec", Math.round(runSec * 10d) / 10d);
        m.put("hoursSource", hoursSource);
        m.put("kwh", Math.round(kwh * 100d) / 100d);
        m.put("laborRate", laborRate);
        m.put("overheadRate", overheadRate);

        m.put("inProgress", inProgress);
        // 成本是否完整：人工算不出来时，总成本里少了这一块，必须标出来
        m.put("partial", labor == null);
        m.put("hoursSourceLabel", hoursLabel(hoursSource));
        m.put("note", noteOf(o, labor, hoursSource, inProgress));
        return m;
    }

    private static String hoursLabel(String src) {
        return switch (src) {
            case "device" -> "设备运行工时";
            case "wallclock" -> "开工到完工时长（设备无产量数据）";
            default -> "未开工，无工时";
        };
    }

    private static String noteOf(ErpWorkOrder o, BigDecimal labor, String hoursSource, boolean inProgress) {
        List<String> notes = new ArrayList<>();
        if (inProgress) {
            notes.add("工单在产中，工时与成本会随时间继续增加");
        }
        if (!"device".equals(hoursSource)) {
            notes.add("工时取自" + hoursLabel(hoursSource) + "，含停机时间，人工成本偏高");
        }
        notes.add("工时与电费按小时粒度统计，跨小时的工单会有少量误差");
        if (labor == null) {
            notes.add("没有人工单价（系统设置 → 成本核算），人工成本未计入");
        }
        if (o.getDoneQty().signum() <= 0) {
            notes.add("完成量为 0，单位成本算不出来");
        }
        return notes.isEmpty() ? null : String.join("；", notes);
    }

    /* ==================== 产品成本（跨工单） ==================== */

    /**
     * 按产品汇总成本。
     * <p>
     * <b>只统计已完工工单</b>：在制工单的成本还在涨，混进来会把单位成本拉低——
     * 今天算 80 元，明天算 95 元，客户以为成本降了，其实只是有一批还没做完。
     * 在制金额单独给一个字段，让人看得到"还有多少没结算"。
     *
     * @param fromTs 按<b>完工时间</b>归期（不是下单、不是开工——钱是完工时才结清的）
     */
    public Map<String, Object> productCosts(long fromTs, long toTs) {
        List<ErpWorkOrder> done = woRepo.findByStatus(ErpWorkOrder.ST_DONE);

        Map<Long, Acc> acc = new LinkedHashMap<>();
        BigDecimal openCost = BigDecimal.ZERO;
        int openCount = 0;

        for (ErpWorkOrder o : done) {
            long finish = o.getActualEnd() == null ? o.getUpdatedAt() : o.getActualEnd();
            if (finish < fromTs || finish >= toTs) {
                continue;
            }
            Acc a = acc.computeIfAbsent(o.getProductSkuId(), k -> new Acc());
            a.add(o, costOf(o));
        }
        // 在制/待开工的：只统计金额，不进单位成本
        for (ErpWorkOrder o : woRepo.findByStatusIn(
                List.of(ErpWorkOrder.ST_RELEASED, ErpWorkOrder.ST_RUNNING))) {
            Map<String, Object> c = costOf(o);
            openCost = openCost.add((BigDecimal) c.get("totalCost"));
            openCount++;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, Acc> e : acc.entrySet()) {
            rows.add(e.getValue().toRow(e.getKey(), skuRepo));
        }
        // 单位成本高的排前面：成本管控先看最贵的那几个
        rows.sort((x, y) -> {
            BigDecimal cx = (BigDecimal) x.get("unitCost");
            BigDecimal cy = (BigDecimal) y.get("unitCost");
            if (cx == null && cy == null) {
                return 0;
            }
            if (cx == null) {
                return 1;
            }
            if (cy == null) {
                return -1;
            }
            return cy.compareTo(cx);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("from", DAY.format(java.time.Instant.ofEpochMilli(fromTs).atZone(ZONE)));
        out.put("to", DAY.format(java.time.Instant.ofEpochMilli(toTs - 1).atZone(ZONE)));
        out.put("rows", rows);
        out.put("total", rows.size());
        out.put("openCost", round4(openCost));
        out.put("openCount", openCount);
        out.put("note", "只统计已完工工单；在制工单的金额单列在 openCost，不进单位成本");
        return out;
    }

    /* ==================== 概览 ==================== */

    public Map<String, Object> overview(long fromTs, long toTs) {
        BigDecimal material = BigDecimal.ZERO;
        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal overhead = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal output = BigDecimal.ZERO;
        int orders = 0;
        boolean partial = false;

        for (ErpWorkOrder o : woRepo.findByStatus(ErpWorkOrder.ST_DONE)) {
            long finish = o.getActualEnd() == null ? o.getUpdatedAt() : o.getActualEnd();
            if (finish < fromTs || finish >= toTs) {
                continue;
            }
            Map<String, Object> c = costOf(o);
            material = material.add((BigDecimal) c.get("materialCost"));
            Object lb = c.get("laborCost");
            if (lb == null) {
                partial = true;
            } else {
                labor = labor.add((BigDecimal) lb);
            }
            overhead = overhead.add((BigDecimal) c.get("overheadCost"));
            total = total.add((BigDecimal) c.get("totalCost"));
            output = output.add(o.getDoneQty());
            orders++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("orders", orders);
        out.put("outputQty", round4(output));
        out.put("materialCost", round4(material));
        out.put("laborCost", round4(labor));
        out.put("overheadCost", round4(overhead));
        out.put("totalCost", round4(total));
        out.put("unitCost", output.signum() > 0 ? round4(total.divide(output, 6, RoundingMode.HALF_UP)) : null);
        out.put("materialPct", pct(material, total));
        out.put("laborPct", pct(labor, total));
        out.put("overheadPct", pct(overhead, total));
        out.put("partial", partial);
        out.put("note", partial
                ? "有工单没配人工单价，人工成本未计入，合计只含材料与制造费用"
                : "口径：材料=实际领料金额，人工=设备运行工时×工价，制造费用=电费+工时×费率");
        return out;
    }

    /* ==================== 内部 ==================== */

    /** 单品累加器：把同一产品的多张工单并起来，单位成本按总成本÷总产量算 */
    private static final class Acc {
        int orders;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal material = BigDecimal.ZERO;
        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal overhead = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        boolean partial;

        void add(ErpWorkOrder o, Map<String, Object> c) {
            orders++;
            qty = qty.add(o.getDoneQty());
            material = material.add((BigDecimal) c.get("materialCost"));
            Object lb = c.get("laborCost");
            if (lb == null) {
                partial = true;
            } else {
                labor = labor.add((BigDecimal) lb);
            }
            overhead = overhead.add((BigDecimal) c.get("overheadCost"));
            total = total.add((BigDecimal) c.get("totalCost"));
        }

        Map<String, Object> toRow(Long skuId, ErpSkuRepository skuRepo) {
            ErpSku sku = skuRepo.findById(skuId).orElse(null);
            BigDecimal unit = qty.signum() > 0
                    ? total.divide(qty, 6, RoundingMode.HALF_UP).setScale(4, RoundingMode.HALF_UP)
                    : null;
            BigDecimal salePrice = sku == null ? null : sku.getSalePrice();
            BigDecimal gross = (unit != null && salePrice != null) ? salePrice.subtract(unit) : null;
            BigDecimal grossPct = (gross != null && salePrice.signum() > 0)
                    ? gross.multiply(HUNDRED).divide(salePrice, 1, RoundingMode.HALF_UP) : null;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", skuId);
            m.put("code", sku == null ? null : sku.getCode());
            m.put("name", sku == null ? ("已删除的商品#" + skuId) : sku.getName());
            m.put("unit", sku == null ? null : sku.getUnit());
            m.put("orders", orders);
            m.put("qty", qty);
            m.put("materialCost", material);
            m.put("materialUnit", unitOf(material, qty));
            m.put("laborCost", labor);
            m.put("laborUnit", unitOf(labor, qty));
            m.put("overheadCost", overhead);
            m.put("overheadUnit", unitOf(overhead, qty));
            m.put("totalCost", total);
            m.put("unitCost", unit);
            m.put("salePrice", salePrice);
            m.put("grossProfit", gross);
            m.put("grossPct", grossPct);
            m.put("partial", partial);
            return m;
        }

        private static BigDecimal unitOf(BigDecimal sum, BigDecimal qty) {
            return qty.signum() > 0 ? sum.divide(qty, 6, RoundingMode.HALF_UP).setScale(4, RoundingMode.HALF_UP) : null;
        }
    }

    private static BigDecimal unitOf(BigDecimal sum, BigDecimal qty) {
        return qty.signum() > 0 ? sum.divide(qty, 6, RoundingMode.HALF_UP).setScale(4, RoundingMode.HALF_UP) : null;
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() <= 0 || part == null) {
            return null;
        }
        return part.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal round4(BigDecimal v) {
        return v == null ? null : v.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }

    private static double num(String s, double def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
