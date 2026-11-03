package com.factory.hub.quality;

import com.factory.hub.erp.ErpStockBill;
import com.factory.hub.erp.ErpStockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 出库方向判定测试（QC-SPEC §7 F3 / AC 说明里的"最要紧的一条"）。
 *
 * <p><b>为什么值得专门写一个反射测试</b>：出库白名单原来散在两处
 * （{@code createManual} 与 {@code checkStockForOut}），新增一个出库类型漏了其中一处，
 * 后果是 qty 变正数 —— <b>报废把库存加了一笔，而且全程不报错</b>。
 * 本项目已经在采购退货和生产领料上各踩过一次，两次都是靠人肉对账才发现的。
 *
 * <p>所以这里不逐个写死"某某类型是出库"，而是<b>遍历 {@code ErpStockBill} 里所有
 * {@code TYPE_*} 常量</b>，要求每一个都必须在下面的两张单子里被明确分类。
 * 以后新增类型而忘了登记 —— 不管忘的是 isOutbound 还是这张测试 —— 都会立刻红，
 * 报错信息直接告诉你"新增的 X 没有分类"。
 */
class ErpStockOutboundTest {

    /** 出库（qty 为负，必须查可用量） */
    private static final Set<String> OUTBOUND = new LinkedHashSet<>(java.util.List.of(
            "TYPE_SO_OUT", "TYPE_OTHER_OUT", "TYPE_PO_RETURN", "TYPE_MATERIAL_OUT", "TYPE_SCRAP_OUT"));

    /**
     * 非出库：入库方向（qty 为正）、以及"方向由行自己带"的调拨与盘点。
     * <p>
     * 调拨和盘点不能算成"出库"：调拨是源仓负 + 目标仓正，盘点是按差异正负落行。
     * 把它们当整单出库，调拨的目标仓行会被记成负数，全厂净额就不是零了。
     */
    private static final Set<String> NOT_OUTBOUND = new LinkedHashSet<>(java.util.List.of(
            "TYPE_PROD_IN", "TYPE_PO_IN", "TYPE_SO_RETURN", "TYPE_OTHER_IN",
            "TYPE_TRANSFER", "TYPE_STOCKTAKE"));

    /** 反射取出所有 TYPE_* 常量（常量名 → 值） */
    private static Map<String, String> typeConstants() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Field f : ErpStockBill.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isPublic(f.getModifiers())) {
                continue;
            }
            if (f.getType() != String.class || !f.getName().startsWith("TYPE_")) {
                continue;
            }
            try {
                out.put(f.getName(), (String) f.get(null));
            } catch (IllegalAccessException e) {
                fail("读不到常量 " + f.getName() + "：" + e.getMessage());
            }
        }
        return out;
    }

    @Test
    @DisplayName("每个 TYPE_* 常量都必须被明确分类（新增类型漏登记立刻红）")
    void everyTypeIsClassified() {
        Map<String, String> all = typeConstants();
        assertFalse(all.isEmpty(), "没反射到任何 TYPE_* 常量，测试本身失效了");

        Set<String> unclassified = new LinkedHashSet<>(all.keySet());
        unclassified.removeAll(OUTBOUND);
        unclassified.removeAll(NOT_OUTBOUND);
        if (!unclassified.isEmpty()) {
            fail("新增了单据类型但没在这里分类：" + unclassified
                    + "。请先确认它是不是出库，再把它加进 ErpStockService.isOutbound() 与本测试的"
                    + " OUTBOUND / NOT_OUTBOUND 之一——漏登记会让它的方向反掉且不报错");
        }

        // 反向：测试里写的常量必须真实存在（防止改名后测试变成空转）
        for (String name : OUTBOUND) {
            assertTrue(all.containsKey(name), "测试引用了不存在的常量：" + name);
        }
        for (String name : NOT_OUTBOUND) {
            assertTrue(all.containsKey(name), "测试引用了不存在的常量：" + name);
        }
    }

    @Test
    @DisplayName("5 个出库类型返回 true：销售出库/其他出库/采购退货/生产领料/报废出库")
    void outboundTypes() {
        Map<String, String> all = typeConstants();
        for (String name : OUTBOUND) {
            String value = all.get(name);
            assertTrue(ErpStockService.isOutbound(value),
                    name + "（" + value + "）必须判为出库，否则它的 qty 会变成正数（库存凭空涨）");
        }
    }

    @Test
    @DisplayName("入库方向与调拨/盘点返回 false")
    void inboundTypes() {
        Map<String, String> all = typeConstants();
        for (String name : NOT_OUTBOUND) {
            String value = all.get(name);
            assertFalse(ErpStockService.isOutbound(value),
                    name + "（" + value + "）不能判为出库：调拨/盘点方向由行自己带，"
                            + "判成出库会让目标仓那一行变成负数");
        }
    }

    @Test
    @DisplayName("未知/空类型一律按非出库处理，不猜")
    void unknownTypeIsNotOutbound() {
        assertFalse(ErpStockService.isOutbound(null));
        assertFalse(ErpStockService.isOutbound(""));
        assertFalse(ErpStockService.isOutbound("WHATEVER"));
        // 大小写必须精确：类型是枚举式常量，不做模糊匹配
        assertFalse(ErpStockService.isOutbound("scrap_out"));
    }

    @Test
    @DisplayName("每种单据都有中文名和单号前缀，且没有重名/重前缀（前端下拉与单号生成直接用）")
    @SuppressWarnings("unchecked")
    void everyTypeHasLabelAndPrefix() throws Exception {
        Map<String, String> all = typeConstants();
        Set<String> labels = new LinkedHashSet<>();
        for (String value : all.values()) {
            String label = ErpStockService.TYPE_LABELS.get(value);
            assertTrue(label != null && !label.isBlank(), "单据类型缺中文名：" + value);
            assertTrue(labels.add(label), "中文名重复：" + label + "（下拉框里会分不清）");

            // 前缀是单号生成的必需品：nextBillNo 现在缺前缀直接抛异常（不再兜底 "BILL"），
            // 所以这里必须保证每个类型都登记了前缀，否则那张单根本开不出来
            String prefix = PREFIXES.get(value);
            assertTrue(prefix != null && !prefix.isBlank(), "单据类型缺单号前缀：" + value);
        }
        // 前缀重复会在同一天撞号：两种类型共用 XSCK 时，各自 count+1 会算出同一个单号
        Set<String> prefixes = new LinkedHashSet<>();
        for (String value : all.values()) {
            String prefix = PREFIXES.get(value);
            assertTrue(prefixes.add(prefix),
                    "单号前缀重复：" + prefix + "（两种类型会在同一天撞号）");
        }
        assertEquals("报废出库", ErpStockService.TYPE_LABELS.get(ErpStockBill.TYPE_SCRAP_OUT));
        assertEquals("BFCK", PREFIXES.get(ErpStockBill.TYPE_SCRAP_OUT));
    }

    /** TYPE_PREFIX 是私有静态表，反射取出来做兜底断言 */
    private static final Map<String, String> PREFIXES = loadPrefixes();

    @SuppressWarnings("unchecked")
    private static Map<String, String> loadPrefixes() {
        try {
            Field f = ErpStockService.class.getDeclaredField("TYPE_PREFIX");
            f.setAccessible(true);
            return (Map<String, String>) f.get(null);
        } catch (Exception e) {
            fail("读不到 ErpStockService.TYPE_PREFIX：" + e.getMessage());
            return Map.of();
        }
    }

    @Test
    @DisplayName("报废出库可从服务内部生成，但不能在库存页手搓")
    void scrapOutIsServiceOnly() {
        assertTrue(ErpStockService.MANUAL_TYPES.contains(ErpStockBill.TYPE_SCRAP_OUT),
                "报废出库要能被处置服务生成，所以必须在 MANUAL_TYPES 里");
        assertFalse(ErpStockService.MANUAL_UI_TYPES.contains(ErpStockBill.TYPE_SCRAP_OUT),
                "报废出库不能出现在库存页的手工下拉里：允许手搓会脱离不良单，"
                        + "处置状态永远结不了案");
    }
}
