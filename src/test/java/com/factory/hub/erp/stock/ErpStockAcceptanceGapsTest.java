package com.factory.hub.erp.stock;

import com.factory.hub.model.DeviceEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.factory.hub.erp.analysis.ErpFinanceService;
import com.factory.hub.erp.analysis.ErpReportService;
import com.factory.hub.erp.purchase.ErpPurchaseOrderItemRepository;
import com.factory.hub.erp.purchase.ErpPurchaseOrderRepository;
import com.factory.hub.erp.sales.ErpSalesOrderItemRepository;
import com.factory.hub.erp.sales.ErpSalesOrderRepository;

/**
 * <b>DH-80 独立验收补充用例（QA 复核「裁决 12 条 AC 有没有被覆盖」时补的缺口）。</b>
 *
 * <p><b>为什么单独建一个类</b>：复核既有 5 个测试类后发现三条裁决标准<b>没有被任何断言钉住</b>，
 * 而它们恰好是「裁决真的落地了没」的关键证据：
 * <ul>
 *   <li><b>AC-9 跨服务一致</b>：{@code ErpReportService.overview().stockValue} 必须等于
 *       {@code summary().totalValue}。原来一个 {@code overview}/{@code stockValue} 都没测——
 *       经营报表是另一个服务，各写各的聚合，最容易悄悄跑出第二个口径。</li>
 *   <li><b>AC-10 新 SKU 的 pending-only 行</b>：新 SKU 只有未过账、没有任何单据时，
 *       {@code balances} 必须<b>返回这一行</b>（{@code qty=0}、{@code pendingQty>0}）。
 *       原来的 {@code pendingOnlySkuMustAgreeAcrossExits} 只比「两出口相等」，
 *       而当两边都是 0（行被整行跳过）时它<b>照样绿</b>——测不出「行没返回」。</li>
 *   <li><b>AC-12 契约不破</b>：{@code balances} 仍返回数组（不是包一层的对象），
 *       且<b>每行都含 {@code pendingQty}</b>（无 pending 时为 {@code 0}，不是 {@code null}、不是缺失）。
 *       原来的 {@code pendingQty()} 助手在字段缺失时返回 0，把「缺失」伪装成「0」，
 *       所以没有任何用例真正断言「字段存在」。</li>
 * </ul>
 *
 * <p><b>为什么这些断言能杀缺陷（不是刷绿）</b>：
 * 三条都用「行/字段是否真的存在」+「值对不对」双重断言，
 * 任何一条回退（把 pending 混回 qty、把 pending-only 行跳过、把字段删掉）都会让本类变红。
 * 见 {@code docs/design/DH-80-库存口径收口.md} §6 与 §12 变异表。
 */
class ErpStockAcceptanceGapsTest {

    private static final long TS = 1_700_000_000_000L;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 在 balances 里精确取某 SKU+仓那一行；不存在返回 null（用来证明「行必须出现」） */
    private static Map<String, Object> findRow(ErpStockService svc, Long skuId, Long whId) {
        for (Map<String, Object> m : svc.balances(null, null)) {
            if (skuId.equals(m.get("skuId")) && whId.equals(m.get("warehouseId"))) {
                return m;
            }
        }
        return null;
    }

    private static ErpStockTestKit baseKit() {
        ErpStockTestKit kit = new ErpStockTestKit();
        kit.sku(1L, "SKU-A", "成品A", null, null);   // purchasePrice = 10（夹具默认）
        kit.sku(2L, "SKU-NEW", "全新成品", null, null);
        kit.wh(1L, "WH1", "主仓");
        return kit;
    }

    /* ================================================================
     * AC-10：新 SKU 只有 pending、没有任何单据 —— 该行必须返回
     * ================================================================ */

    @Test
    @DisplayName("AC-10 新 SKU 只有 pending：balances 必须返回该行（qty=0、pendingQty>0）；成单后 qty=良品、pending=0")
    void ac10_pendingOnlyNewSkuRowMustAppear() {
        ErpStockTestKit kit = baseKit();
        ErpStockService svc = kit.stockService();
        ErpProdInboundService prod =
                kit.prodInboundService("DEV1", 2L, 1L, DeviceEntity.COUNTER_TOTAL, svc);

        // 全新 SKU：产出 10，全部还在 pending 里，一件单据都没有
        prod.onProduced("DEV1", 10, TS, false);

        Map<String, Object> row = findRow(svc, 2L, 1L);
        assertNotNull(row,
                "AC-10 未满足：全新 SKU 刚投产（只有未过账、没有任何单据）时，"
                        + "balances 必须出现该 SKU 行 —— 否则现场完全看不到「它已经开始产出」。"
                        + "（这是把跳过条件从 qty<=EPS 改成 qty<=EPS && pend<=EPS 的那道防线）");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get("qty")),
                "AC-10：pending-only 行的账面 qty 必须是 0（未过账不是库存）");
        assertEquals(0, bd("10").compareTo((BigDecimal) row.get("pendingQty")),
                "AC-10：pendingQty 必须是 10（已产出未过账 10 件，看得见）");

        // 不良 2 ⇒ TOTAL 口径良品 8；成单
        prod.onRejected("DEV1", 2, TS);
        prod.flush();

        Map<String, Object> after = findRow(svc, 2L, 1L);
        assertNotNull(after, "AC-10：成单后该行必须仍存在（账面 8）");
        assertEquals(0, bd("8").compareTo((BigDecimal) after.get("qty")),
                "AC-10：成单后 qty 变成良品数 8（产出 10 − 不良 2）");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) after.get("pendingQty")),
                "AC-10：成单后 pendingQty 归 0");
    }

    /* ================================================================
     * AC-9：经营报表 stockValue == 库存 KPI totalValue（跨服务同口径）
     * ================================================================ */

    @Test
    @DisplayName("AC-9 跨服务一致：reports/overview 的 stockValue 必须等于 stock/summary 的 totalValue")
    void ac9_reportStockValueMustEqualSummaryTotalValue() {
        ErpStockTestKit kit = baseKit();
        ErpStockService svc = kit.stockService();
        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);  // 单据 8 × 采购价 10 = 80
        svc.addPendingStock(1L, 1L, bd("5"));                   // 未过账 5，绝不许进任何金额

        ErpFinanceService fin = mock(ErpFinanceService.class);
        when(fin.summary()).thenReturn(new LinkedHashMap<>());
        ErpPurchaseOrderRepository poRepo = mock(ErpPurchaseOrderRepository.class);
        ErpSalesOrderRepository soRepo = mock(ErpSalesOrderRepository.class);
        when(poRepo.inPeriod(anyLong(), anyLong())).thenReturn(List.of());
        when(soRepo.inPeriod(anyLong(), anyLong())).thenReturn(List.of());
        ErpReportService report = new ErpReportService(kit.billRepo, kit.skuRepo, kit.whRepo,
                poRepo, mock(ErpPurchaseOrderItemRepository.class),
                soRepo, mock(ErpSalesOrderItemRepository.class), fin);

        long from = 0L;
        long to = 2_000_000_000_000L;
        BigDecimal stockValue = (BigDecimal) report.overview(from, to).get("stockValue");
        BigDecimal totalValue = (BigDecimal) svc.summary().get("totalValue");

        assertEquals(0, bd("80").compareTo(stockValue),
                "夹具前提：单据 8 × 采购价 10 = 80；未过账 5 件不得计入金额，实际 stockValue="
                        + stockValue.toPlainString());
        assertEquals(0, stockValue.compareTo(totalValue),
                "AC-9 未满足：经营报表 stockValue=" + stockValue.toPlainString()
                        + " 与库存 summary.totalValue=" + totalValue.toPlainString()
                        + " 必须相等（都是单据口径）。两者各写各的聚合会悄悄跑出第二个口径。");
    }

    /* ================================================================
     * AC-12：契约不破（返回数组 + 每行都有 pendingQty）
     * ================================================================ */

    @Test
    @DisplayName("AC-12 契约不破：balances 仍是数组（List）；每行都含 pendingQty（无 pending 时为 0，不是 null/缺失）")
    void ac12_balancesIsArrayAndPendingQtyAlwaysPresent() {
        ErpStockTestKit kit = baseKit();
        ErpStockService svc = kit.stockService();
        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);
        svc.createProdInbound("DEV1", 2L, 1L, bd("3"), false);
        svc.addPendingStock(1L, 1L, bd("5"));    // 仅 SKU-A 有未过账；SKU-NEW 没有

        // 编译期即证明返回类型仍是 List（若被改成 Map/包一层的对象，这行编译不过）
        List<Map<String, Object>> balances = svc.balances(null, null);
        assertTrue(balances instanceof List, "AC-12：balances 必须返回数组，不得包一层对象");
        assertFalse(balances.isEmpty(), "夹具前提：至少要有 2 行");

        for (Map<String, Object> row : balances) {
            assertTrue(row.containsKey("pendingQty"),
                    "AC-12 未满足：每一行都必须有 pendingQty 字段，缺字段的行 code=" + row.get("code"));
            Object p = row.get("pendingQty");
            assertNotNull(p,
                    "AC-12 未满足：pendingQty 不得为 null（无未过账时应为 0），行 code=" + row.get("code"));
            assertTrue(p instanceof BigDecimal, "pendingQty 必须是数字，行 code=" + row.get("code"));
        }

        Map<String, Object> withPending = findRow(svc, 1L, 1L);
        Map<String, Object> withoutPending = findRow(svc, 2L, 1L);
        assertNotNull(withPending);
        assertNotNull(withoutPending);
        assertEquals(0, bd("5").compareTo((BigDecimal) withPending.get("pendingQty")),
                "SKU-A 有未过账 5，pendingQty 应为 5");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) withoutPending.get("pendingQty")),
                "AC-12：无未过账的行 pendingQty 必须是 0（不是 null、不是缺失）");
    }

    /* ================================================================
     * AC-7（补齐）：summary 必须正向声明 stockMode="BILL" + 顶层 pendingTotal
     * ================================================================ */

    @Test
    @DisplayName("AC-7 口径可见：summary 必须含 stockMode==\"BILL\" 与 pendingTotal（=各行 pendingQty 之和）")
    void ac7_summaryDeclaresBillModeAndPendingTotal() {
        ErpStockTestKit kit = baseKit();
        ErpStockService svc = kit.stockService();
        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);
        svc.addPendingStock(1L, 1L, bd("5"));
        svc.addPendingStock(2L, 1L, bd("4"));    // 第二个 SKU 只有 pending

        Map<String, Object> summary = svc.summary();

        assertEquals("BILL", summary.get("stockMode"),
                "AC-7：summary 顶层必须含 stockMode==\"BILL\"，正向声明「余额 = 单据汇总」");
        Object pendingTotal = summary.get("pendingTotal");
        assertNotNull(pendingTotal, "AC-7：summary 顶层必须含 pendingTotal");
        assertEquals(0, bd("9").compareTo((BigDecimal) pendingTotal),
                "AC-7：pendingTotal 必须等于各行 pendingQty 之和（5 + 4 = 9）");
        // 账面口径不受 pending 影响：totalQty 只等于单据数量 8
        assertEquals(0, bd("8").compareTo((BigDecimal) summary.get("totalQty")),
                "AC-7：totalQty 是纯单据口径（8），pending 不得混入");
    }
}
