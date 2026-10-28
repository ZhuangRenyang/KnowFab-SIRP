package com.factory.hub.erp;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经营报表（ROADMAP DH-57）。
 * <p>
 * 四条铁律，决定了这里为什么这么写：
 * <ol>
 *   <li><b>报表永远现算，不落地快照</b>——单据一作废，报表必须立刻跟着变；
 *       报表快照表是"账实不符"的头号来源。</li>
 *   <li><b>口径与业务页一致</b>——库存状况的库存金额按商品采购价估值，
 *       和「库存管理 → 库存余额」用同一个算法，两个页面对不上客户只会信没人。</li>
 *   <li><b>数量不能跨商品相加</b>——kg、件、套加在一起是没有意义的数字。
 *       合计只对金额求和，数量合计仅作"行数/品种数"参考，界面上必须写清楚。</li>
 *   <li><b>退货是负数，净额自然正确</b>——采购额 = PO_IN − PO_RETURN，
 *       销售额 = SO_OUT − SO_RETURN，不做特殊判断。</li>
 * </ol>
 */
@Service
public class ErpReportService {

    private static final BigDecimal EPS = new BigDecimal("0.0001");
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ErpStockBillRepository billRepo;
    private final ErpSkuRepository skuRepo;
    private final ErpWarehouseRepository whRepo;
    private final ErpPurchaseOrderRepository poRepo;
    private final ErpPurchaseOrderItemRepository poItemRepo;
    private final ErpSalesOrderRepository soRepo;
    private final ErpSalesOrderItemRepository soItemRepo;
    private final ErpFinanceService finance;

    public ErpReportService(ErpStockBillRepository billRepo, ErpSkuRepository skuRepo,
                            ErpWarehouseRepository whRepo, ErpPurchaseOrderRepository poRepo,
                            ErpPurchaseOrderItemRepository poItemRepo,
                            ErpSalesOrderRepository soRepo,
                            ErpSalesOrderItemRepository soItemRepo,
                            ErpFinanceService finance) {
        this.billRepo = billRepo;
        this.skuRepo = skuRepo;
        this.whRepo = whRepo;
        this.poRepo = poRepo;
        this.poItemRepo = poItemRepo;
        this.soRepo = soRepo;
        this.soItemRepo = soItemRepo;
        this.finance = finance;
    }

    /* ==================== 经营概况 ==================== */

    @Transactional(readOnly = true)
    public Map<String, Object> overview(long fromTs, long toTs) {
        Map<Long, ErpSku> skus = skuMap();
        Map<Long, ErpWarehouse> whs = whMap();

        List<Map<String, Object>> trend = new ArrayList<>();
        BigDecimal purchase = BigDecimal.ZERO;
        BigDecimal sales = BigDecimal.ZERO;
        for (Object[] a : billRepo.tradeDaily(fromTs, toTs)) {
            BigDecimal p = dec(a[1]);
            BigDecimal s = dec(a[2]);
            purchase = purchase.add(p);
            sales = sales.add(s);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", str(a[0]));
            m.put("purchase", scale(p));
            m.put("sales", scale(s));
            trend.add(m);
        }

        BigDecimal stockValue = BigDecimal.ZERO;
        int stockRows = 0;
        for (Object[] a : billRepo.aggregateStock()) {
            ErpSku sku = skus.get(((Number) a[0]).longValue());
            BigDecimal q = dec(a[2]);
            if (sku == null || q.abs().compareTo(EPS) <= 0) {
                continue;
            }
            stockValue = stockValue.add(q.multiply(cost(sku)));
            stockRows++;
        }

        Map<String, Object> fin = finance.summary();

        List<ErpPurchaseOrder> pos = poRepo.inPeriod(fromTs, toTs);
        List<ErpSalesOrder> sos = soRepo.inPeriod(fromTs, toTs);
        BigDecimal poOrderAmount = BigDecimal.ZERO;
        for (ErpPurchaseOrder o : pos) {
            poOrderAmount = poOrderAmount.add(nz(o.getTotalAmount()));
        }
        BigDecimal soOrderAmount = BigDecimal.ZERO;
        for (ErpSalesOrder o : sos) {
            soOrderAmount = soOrderAmount.add(nz(o.getTotalAmount()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("from", fromTs);
        out.put("to", toTs);
        out.put("fromDate", DAY.format(Instant.ofEpochMilli(fromTs).atZone(ZONE)));
        out.put("toDate", DAY.format(Instant.ofEpochMilli(toTs - 1).atZone(ZONE)));
        out.put("purchaseAmount", scale(purchase));
        out.put("salesAmount", scale(sales));
        out.put("poOrderAmount", scale(poOrderAmount));
        out.put("soOrderAmount", scale(soOrderAmount));
        out.put("poCount", pos.size());
        out.put("soCount", sos.size());
        out.put("prodInQty", scale(billRepo.sumQtyByTypeInRange(ErpStockBill.TYPE_PROD_IN, fromTs, toTs)));
        // 库存是"此刻"的，不随筛选区间变化，界面上要标注清楚
        out.put("stockValue", scale(stockValue));
        out.put("stockRows", stockRows);
        out.put("skuCount", skus.size());
        out.put("whCount", whs.size());
        out.put("arOutstanding", fin.get("arOutstanding"));
        out.put("arOverdue", fin.get("arOverdue"));
        out.put("apOutstanding", fin.get("apOutstanding"));
        out.put("apOverdue", fin.get("apOverdue"));
        out.put("trend", trend);
        out.put("topCustomers", partnerRank(billRepo.salesByPartner(fromTs, toTs, 8)));
        out.put("topSuppliers", partnerRank(billRepo.purchaseByPartner(fromTs, toTs, 8)));
        out.put("topSkus", topSkus(fromTs, toTs, 0L, 8, skus));
        return out;
    }

    /* ==================== 库存状况（进销存） ==================== */

    /**
     * 期初 + 本期入 − 本期出 = 期末。
     * 期初取"区间开始前的全部有效流水累计"，所以任意区间都能对上总账。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stock(long fromTs, long toTs, Long whId) {
        Map<Long, ErpSku> skus = skuMap();
        Map<Long, ErpWarehouse> whs = whMap();
        long w = whId == null ? 0L : whId;

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal openingAmt = BigDecimal.ZERO;
        BigDecimal inAmt = BigDecimal.ZERO;
        BigDecimal outAmt = BigDecimal.ZERO;
        BigDecimal closingAmt = BigDecimal.ZERO;
        BigDecimal closingQty = BigDecimal.ZERO;
        for (Object[] a : billRepo.stockLedger(fromTs, toTs, w)) {
            Long skuId = ((Number) a[0]).longValue();
            Long warehouseId = ((Number) a[1]).longValue();
            BigDecimal opening = dec(a[2]);
            BigDecimal in = dec(a[3]);
            BigDecimal out = dec(a[4]);
            BigDecimal closing = opening.add(in).subtract(out);
            if (opening.abs().compareTo(EPS) <= 0 && in.abs().compareTo(EPS) <= 0
                    && out.abs().compareTo(EPS) <= 0) {
                continue;                       // 区间内毫无动静、期初期末都为 0 的行不占版面
            }
            ErpSku sku = skus.get(skuId);
            if (sku == null) {
                continue;
            }
            ErpWarehouse wh = whs.get(warehouseId);
            BigDecimal cost = cost(sku);
            BigDecimal rowOpeningAmt = scale(opening.multiply(cost));
            BigDecimal rowInAmt = scale(in.multiply(cost));
            BigDecimal rowOutAmt = scale(out.multiply(cost));
            BigDecimal rowClosingAmt = scale(closing.multiply(cost));

            openingAmt = openingAmt.add(rowOpeningAmt);
            inAmt = inAmt.add(rowInAmt);
            outAmt = outAmt.add(rowOutAmt);
            closingAmt = closingAmt.add(rowClosingAmt);
            closingQty = closingQty.add(closing);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", skuId);
            m.put("code", sku.getCode());
            m.put("name", sku.getName());
            m.put("spec", sku.getSpec());
            m.put("unit", sku.getUnit());
            m.put("category", sku.getCategory());
            m.put("warehouseId", warehouseId);
            m.put("warehouseName", wh == null ? "仓库#" + warehouseId : wh.getName());
            m.put("opening", scale(opening));
            m.put("inQty", scale(in));
            m.put("outQty", scale(out));
            m.put("closing", scale(closing));
            m.put("unitCost", scale(cost));
            m.put("openingAmount", rowOpeningAmt);
            m.put("inAmount", rowInAmt);
            m.put("outAmount", rowOutAmt);
            m.put("amount", rowClosingAmt);
            rows.add(m);
        }
        rows.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("code")))
                .thenComparing(m -> String.valueOf(m.get("warehouseName"))));

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("skuCount", rows.size());
        totals.put("openingAmount", scale(openingAmt));
        totals.put("inAmount", scale(inAmt));
        totals.put("outAmount", scale(outAmt));
        totals.put("amount", scale(closingAmt));
        totals.put("closingQty", scale(closingQty));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("from", fromTs);
        out.put("to", toTs);
        out.put("fromDate", DAY.format(Instant.ofEpochMilli(fromTs).atZone(ZONE)));
        out.put("toDate", DAY.format(Instant.ofEpochMilli(toTs - 1).atZone(ZONE)));
        out.put("warehouseId", whId);
        out.put("warehouseName", whId == null ? "全部仓库"
                : whs.containsKey(whId) ? whs.get(whId).getName() : "仓库#" + whId);
        out.put("rows", rows);
        out.put("totals", totals);
        out.put("note", "库存金额按商品采购价估值；数量合计跨计量单位无意义，只作参考。");
        return out;
    }

    /* ==================== 出入库统计 ==================== */

    @Transactional(readOnly = true)
    public Map<String, Object> flows(long fromTs, long toTs, Long whId) {
        Map<Long, ErpSku> skus = skuMap();
        Map<Long, ErpWarehouse> whs = whMap();
        long w = whId == null ? 0L : whId;

        List<Map<String, Object>> byType = new ArrayList<>();
        BigDecimal inQty = BigDecimal.ZERO;
        BigDecimal outQty = BigDecimal.ZERO;
        BigDecimal inAmt = BigDecimal.ZERO;
        BigDecimal outAmt = BigDecimal.ZERO;
        long bills = 0;
        for (Object[] a : billRepo.flowByType(fromTs, toTs, w)) {
            String type = str(a[0]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("typeLabel", ErpStockService.TYPE_LABELS.getOrDefault(type, type));
            m.put("bills", ((Number) a[1]).longValue());
            m.put("inQty", scale(dec(a[2])));
            m.put("outQty", scale(dec(a[3])));
            m.put("inAmount", scale(dec(a[4])));
            m.put("outAmount", scale(dec(a[5])));
            m.put("netAmount", scale(dec(a[4]).subtract(dec(a[5]))));
            bills += ((Number) a[1]).longValue();
            inQty = inQty.add(dec(a[2]));
            outQty = outQty.add(dec(a[3]));
            inAmt = inAmt.add(dec(a[4]));
            outAmt = outAmt.add(dec(a[5]));
            byType.add(m);
        }
        byType.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("type"))));

        List<Map<String, Object>> byWarehouse = new ArrayList<>();
        for (Object[] a : billRepo.flowByWarehouse(fromTs, toTs)) {
            Long id = ((Number) a[0]).longValue();
            if (whId != null && !whId.equals(id)) {
                continue;
            }
            ErpWarehouse wh = whs.get(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("warehouseId", id);
            m.put("warehouseName", wh == null ? "仓库#" + id : wh.getName());
            m.put("bills", ((Number) a[1]).longValue());
            m.put("inQty", scale(dec(a[2])));
            m.put("outQty", scale(dec(a[3])));
            m.put("inAmount", scale(dec(a[4])));
            m.put("outAmount", scale(dec(a[5])));
            byWarehouse.add(m);
        }

        List<Map<String, Object>> daily = new ArrayList<>();
        for (Object[] a : billRepo.flowDaily(fromTs, toTs, w)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", str(a[0]));
            m.put("inQty", scale(dec(a[1])));
            m.put("outQty", scale(dec(a[2])));
            m.put("inAmount", scale(dec(a[3])));
            m.put("outAmount", scale(dec(a[4])));
            daily.add(m);
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("bills", bills);
        totals.put("inQty", scale(inQty));
        totals.put("outQty", scale(outQty));
        totals.put("inAmount", scale(inAmt));
        totals.put("outAmount", scale(outAmt));
        totals.put("netAmount", scale(inAmt.subtract(outAmt)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("from", fromTs);
        out.put("to", toTs);
        out.put("fromDate", DAY.format(Instant.ofEpochMilli(fromTs).atZone(ZONE)));
        out.put("toDate", DAY.format(Instant.ofEpochMilli(toTs - 1).atZone(ZONE)));
        out.put("warehouseId", whId);
        out.put("byType", byType);
        out.put("byWarehouse", byWarehouse);
        out.put("daily", daily);
        out.put("topSkus", topSkus(fromTs, toTs, w, 15, skus));
        out.put("totals", totals);
        return out;
    }

    /* ==================== 采购 / 销售执行 ==================== */

    /**
     * 订单执行表：下单金额 / 已执行（已入库或已发货）/ 未执行。
     * 按订单日期落区间，执行额取"当前进度"，所以跨月订单会带着最新进度出现——
     * 这正是催货/催款要看的东西，报表不需要假装是时点数据。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> trade(String side, long fromTs, long toTs) {
        boolean sales = "sales".equalsIgnoreCase(side);
        List<Map<String, Object>> orders = new ArrayList<>();
        Map<String, BigDecimal[]> byPartner = new LinkedHashMap<>();
        Map<String, String> partnerNames = new HashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalExecuted = BigDecimal.ZERO;

        if (sales) {
            List<ErpSalesOrder> list = soRepo.inPeriod(fromTs, toTs);
            List<Long> ids = list.stream().map(ErpSalesOrder::getId).toList();
            Map<Long, BigDecimal> executed = new HashMap<>();
            if (!ids.isEmpty()) {
                for (Object[] a : soItemRepo.sumShippedAmountByOrderIds(ids)) {
                    executed.put(((Number) a[0]).longValue(), dec(a[1]));
                }
            }
            for (ErpSalesOrder o : list) {
                BigDecimal amt = nz(o.getTotalAmount());
                BigDecimal done = executed.getOrDefault(o.getId(), BigDecimal.ZERO);
                orders.add(orderRow(o.getOrderNo(), o.getOrderDate(), o.getCustomerName(),
                        o.getStatus(), ErpSalesService.STATUS_LABELS, amt, done));
                accumulate(byPartner, partnerNames, o.getCustomerId(), o.getCustomerName(), amt, done);
                totalAmount = totalAmount.add(amt);
                totalExecuted = totalExecuted.add(done);
            }
        } else {
            List<ErpPurchaseOrder> list = poRepo.inPeriod(fromTs, toTs);
            List<Long> ids = list.stream().map(ErpPurchaseOrder::getId).toList();
            Map<Long, BigDecimal> executed = new HashMap<>();
            if (!ids.isEmpty()) {
                for (Object[] a : poItemRepo.sumReceivedAmountByOrderIds(ids)) {
                    executed.put(((Number) a[0]).longValue(), dec(a[1]));
                }
            }
            for (ErpPurchaseOrder o : list) {
                BigDecimal amt = nz(o.getTotalAmount());
                BigDecimal done = executed.getOrDefault(o.getId(), BigDecimal.ZERO);
                orders.add(orderRow(o.getOrderNo(), o.getOrderDate(), o.getSupplierName(),
                        o.getStatus(), ErpPurchaseService.STATUS_LABELS, amt, done));
                accumulate(byPartner, partnerNames, o.getSupplierId(), o.getSupplierName(), amt, done);
                totalAmount = totalAmount.add(amt);
                totalExecuted = totalExecuted.add(done);
            }
        }

        List<Map<String, Object>> partners = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : byPartner.entrySet()) {
            BigDecimal[] v = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partnerId", Long.valueOf(e.getKey()));
            m.put("partnerName", partnerNames.get(e.getKey()));
            m.put("orders", v[3].intValue());
            m.put("orderAmount", scale(v[0]));
            m.put("executedAmount", scale(v[1]));
            m.put("openAmount", scale(v[0].subtract(v[1]).max(BigDecimal.ZERO)));
            m.put("rate", v[0].signum() <= 0 ? BigDecimal.ZERO
                    : v[1].multiply(BigDecimal.valueOf(100))
                        .divide(v[0], 1, RoundingMode.HALF_UP));
            partners.add(m);
        }
        partners.sort((x, y) -> dec(y.get("orderAmount")).compareTo(dec(x.get("orderAmount"))));

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("orders", orders.size());
        totals.put("partners", partners.size());
        totals.put("orderAmount", scale(totalAmount));
        totals.put("executedAmount", scale(totalExecuted));
        totals.put("openAmount", scale(totalAmount.subtract(totalExecuted).max(BigDecimal.ZERO)));
        totals.put("rate", totalAmount.signum() <= 0 ? BigDecimal.ZERO
                : totalExecuted.multiply(BigDecimal.valueOf(100))
                    .divide(totalAmount, 1, RoundingMode.HALF_UP));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("side", sales ? "sales" : "purchase");
        out.put("sideLabel", sales ? "销售" : "采购");
        out.put("partnerLabel", sales ? "客户" : "供应商");
        out.put("from", fromTs);
        out.put("to", toTs);
        out.put("fromDate", DAY.format(Instant.ofEpochMilli(fromTs).atZone(ZONE)));
        out.put("toDate", DAY.format(Instant.ofEpochMilli(toTs - 1).atZone(ZONE)));
        out.put("orders", orders);
        out.put("partners", partners);
        out.put("totals", totals);
        return out;
    }

    /* ==================== 内部 ==================== */

    private Map<String, Object> orderRow(String no, long date, String partner, String status,
                                         Map<String, String> labels, BigDecimal amt, BigDecimal done) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", no);
        m.put("orderDate", date);
        m.put("partnerName", partner == null ? "" : partner);
        m.put("status", status);
        m.put("statusLabel", labels.getOrDefault(status, status));
        m.put("orderAmount", scale(amt));
        m.put("executedAmount", scale(done));
        m.put("openAmount", scale(amt.subtract(done).max(BigDecimal.ZERO)));
        m.put("rate", amt.signum() <= 0 ? BigDecimal.ZERO
                : done.multiply(BigDecimal.valueOf(100)).divide(amt, 1, RoundingMode.HALF_UP));
        return m;
    }

    /** 按往来单位累计：金额、已执行、单数 */
    private void accumulate(Map<String, BigDecimal[]> map, Map<String, String> names,
                            Long partnerId, String partnerName,
                            BigDecimal amount, BigDecimal executed) {
        String key = String.valueOf(partnerId == null ? 0L : partnerId);
        BigDecimal[] v = map.computeIfAbsent(key,
                k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        v[0] = v[0].add(amount);
        v[1] = v[1].add(executed);
        v[3] = v[3].add(BigDecimal.ONE);
        names.putIfAbsent(key, partnerName == null ? "（未指定）" : partnerName);
    }

    private List<Map<String, Object>> partnerRank(List<Object[]> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] a : raw) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partnerId", a[0] == null ? null : ((Number) a[0]).longValue());
            m.put("name", str(a[1]));
            m.put("amount", scale(dec(a[2])));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> topSkus(long fromTs, long toTs, long whId, int topN,
                                              Map<Long, ErpSku> skus) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] a : billRepo.flowTopSkus(fromTs, toTs, whId, topN)) {
            Long skuId = ((Number) a[0]).longValue();
            ErpSku s = skus.get(skuId);
            if (s == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", skuId);
            m.put("code", s.getCode());
            m.put("name", s.getName());
            m.put("spec", s.getSpec());
            m.put("unit", s.getUnit());
            m.put("inQty", scale(dec(a[1])));
            m.put("outQty", scale(dec(a[2])));
            m.put("amount", scale(dec(a[3])));
            out.add(m);
        }
        return out;
    }

    private Map<Long, ErpSku> skuMap() {
        Map<Long, ErpSku> map = new HashMap<>();
        for (ErpSku s : skuRepo.findAll()) {
            map.put(s.getId(), s);
        }
        return map;
    }

    private Map<Long, ErpWarehouse> whMap() {
        Map<Long, ErpWarehouse> map = new HashMap<>();
        for (ErpWarehouse w : whRepo.findAll()) {
            map.put(w.getId(), w);
        }
        return map;
    }

    private static BigDecimal cost(ErpSku s) {
        return s.getPurchasePrice() == null ? BigDecimal.ZERO : s.getPurchasePrice();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 区间工具：前端传 yyyy-MM-dd，后端换成 [from, to) 毫秒 */
    public static long startOf(String day, LocalDate fallback) {
        LocalDate d = parseDay(day, fallback);
        return d.atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    public static long endOfExclusive(String day, LocalDate fallback) {
        LocalDate d = parseDay(day, fallback);
        return d.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    private static LocalDate parseDay(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim().substring(0, 10));
        } catch (Exception e) {
            return fallback;
        }
    }
}
