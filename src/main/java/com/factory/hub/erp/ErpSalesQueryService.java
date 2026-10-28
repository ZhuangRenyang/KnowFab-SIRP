package com.factory.hub.erp;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售查询（DH-45）：给销售角色的只读库存视图。
 * <p>
 * <b>这个接口的边界就是"能卖多少"，所以刻意只给这些：</b>
 * 商品编码/名称/规格/单位/类别、现存量、订单占用量、可承诺量、所在仓库、销售价。
 * <b>不给</b>采购价与库存金额（成本是财务的事，给销售看等于把底价摊开）、
 * 不给设备与车间数据（跟交期无关的都不给）。字段少不是偷懒，是权限设计。
 * <p>
 * 可承诺量 = 现存量 − 已确认/部分发货订单里还没发的数量。
 * 只给现存量的库存页销售看不懂：明明显示 100，答应客户 100 却发不出货，
 * 因为其中 30 已经被别的订单占了。
 */
@Service
public class ErpSalesQueryService {

    private static final BigDecimal EPS = new BigDecimal("0.0001");

    private final ErpStockBillRepository billRepo;
    private final ErpSkuRepository skuRepo;
    private final ErpWarehouseRepository whRepo;
    private final ErpSalesOrderItemRepository itemRepo;

    public ErpSalesQueryService(ErpStockBillRepository billRepo, ErpSkuRepository skuRepo,
                                ErpWarehouseRepository whRepo, ErpSalesOrderItemRepository itemRepo) {
        this.billRepo = billRepo;
        this.skuRepo = skuRepo;
        this.whRepo = whRepo;
        this.itemRepo = itemRepo;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stock(String kw, String category, Long whId, boolean onlyAvailable) {
        Map<Long, ErpSku> skus = new HashMap<>();
        for (ErpSku s : skuRepo.findAll()) {
            if (s.isEnabled()) {
                skus.put(s.getId(), s);
            }
        }
        Map<Long, String> whNames = new HashMap<>();
        for (ErpWarehouse w : whRepo.findAll()) {
            whNames.put(w.getId(), w.getName());
        }
        Map<Long, BigDecimal> allocated = new HashMap<>();
        for (Object[] a : itemRepo.openOrderQtyBySku()) {
            allocated.put(((Number) a[0]).longValue(), dec(a[1]));
        }

        // 按 SKU 汇总（多仓合并），同时留一份分仓明细
        Map<Long, BigDecimal> total = new LinkedHashMap<>();
        Map<Long, List<Map<String, Object>>> byWh = new HashMap<>();
        for (Object[] a : billRepo.aggregateStock()) {
            Long skuId = ((Number) a[0]).longValue();
            Long warehouseId = ((Number) a[1]).longValue();
            BigDecimal qty = dec(a[2]);
            if (qty.abs().compareTo(EPS) <= 0) {
                continue;
            }
            if (whId != null && !whId.equals(warehouseId)) {
                continue;
            }
            if (!skus.containsKey(skuId)) {
                continue;
            }
            total.merge(skuId, qty, BigDecimal::add);
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("id", warehouseId);
            w.put("name", whNames.getOrDefault(warehouseId, "仓库#" + warehouseId));
            w.put("qty", scale(qty));
            byWh.computeIfAbsent(skuId, k -> new ArrayList<>()).add(w);
        }

        String k = kw == null ? "" : kw.trim().toLowerCase();
        String cat = category == null ? "" : category.trim();
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal sumOnHand = BigDecimal.ZERO;
        BigDecimal sumAllocated = BigDecimal.ZERO;
        BigDecimal sumAvailable = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> e : total.entrySet()) {
            Long skuId = e.getKey();
            ErpSku s = skus.get(skuId);
            if (s == null) {
                continue;
            }
            if (!k.isEmpty()) {
                String hay = (s(s.getCode()) + " " + s(s.getName()) + " " + s(s.getSpec())
                        + " " + s(s.getCategory()) + " " + s(s.getBarcode())).toLowerCase();
                if (!hay.contains(k)) {
                    continue;
                }
            }
            if (!cat.isEmpty() && !cat.equals(s(s.getCategory()))) {
                continue;
            }
            BigDecimal onHand = e.getValue();
            BigDecimal alloc = allocated.getOrDefault(skuId, BigDecimal.ZERO);
            // 超卖时"可承诺"截到 0（不给负数吓销售），但欠交要单独说清楚：
            // 现 16、占用 25 的情况销售必须知道"欠 9 件"，否则会继续接单
            BigDecimal raw = onHand.subtract(alloc);
            BigDecimal shortage = raw.signum() < 0 ? raw.negate() : BigDecimal.ZERO;
            BigDecimal avail = raw.signum() < 0 ? BigDecimal.ZERO : raw;
            if (onlyAvailable && avail.compareTo(EPS) <= 0) {
                continue;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", skuId);
            m.put("code", s.getCode());
            m.put("name", s.getName());
            m.put("spec", s.getSpec());
            m.put("unit", s.getUnit());
            m.put("category", s.getCategory());
            m.put("salePrice", s.getSalePrice());
            m.put("onHand", scale(onHand));
            m.put("allocated", scale(alloc));
            m.put("available", scale(avail));
            m.put("shortage", scale(shortage));
            m.put("state", state(avail, s.getMinStock()));
            List<Map<String, Object>> ws = byWh.getOrDefault(skuId, List.of());
            m.put("warehouses", ws);
            m.put("warehouseText", ws.stream()
                    .map(w -> w.get("name") + " " + plain((BigDecimal) w.get("qty")))
                    .reduce((x, y) -> x + " / " + y).orElse("--"));
            rows.add(m);

            sumOnHand = sumOnHand.add(onHand);
            sumAllocated = sumAllocated.add(alloc);
            sumAvailable = sumAvailable.add(avail);
        }
        rows.sort((x, y) -> dec(y.get("available")).compareTo(dec(x.get("available"))));

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("skuCount", rows.size());
        totals.put("onHand", scale(sumOnHand));
        totals.put("allocated", scale(sumAllocated));
        totals.put("available", scale(sumAvailable));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("rows", rows);
        out.put("totals", totals);
        out.put("warehouses", whRepo.findAll().stream().map(w -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.getId());
            m.put("name", w.getName());
            return m;
        }).toList());
        out.put("note", "可承诺量 = 现存量 − 已确认订单未发货数量；数量单位见每行「单位」列。");
        return out;
    }

    /** 库存状态：给销售一个"能不能接单"的直觉，不暴露成本 */
    private static String state(BigDecimal available, BigDecimal minStock) {
        if (available.signum() <= 0) {
            return "OUT";
        }
        if (minStock != null && minStock.signum() > 0 && available.compareTo(minStock) < 0) {
            return "LOW";
        }
        return "OK";
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

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(4, RoundingMode.HALF_UP);
    }

    /** 数量显示去掉无意义的尾零：25806.0000 → 25806 */
    private static String plain(BigDecimal v) {
        if (v == null) {
            return "0";
        }
        BigDecimal t = v.stripTrailingZeros();
        return t.scale() < 0 ? t.setScale(0, RoundingMode.UNNECESSARY).toPlainString() : t.toPlainString();
    }

    private static String s(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
