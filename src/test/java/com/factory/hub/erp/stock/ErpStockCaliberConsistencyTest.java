package com.factory.hub.erp.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * <b>DH-80 库存双口径收口 —— 契约测试（修复后必须全绿）。</b>
 *
 * <p><b>这条缺陷长什么样</b>：同一个 SKU、同一时刻，库存有<b>两个口径</b>：
 * <ul>
 *   <li>{@code balances()}（GET /api/erp/stock/balances，库存余额页）
 *       = 单据汇总 <b>+</b> 内存里的「未成单生产增量」({@code pendingStock})；</li>
 *   <li>{@code alerts()}（GET /api/erp/stock/alerts，库存预警）
 *       = <b>只算</b> {@code billRepo.aggregateStock()}，不含 pending；</li>
 *   <li>{@code flow()}（GET /api/erp/stock/flow，流水结存）= 同样不含 pending；</li>
 *   <li>{@code summary()} 的 {@code totalQty} 走 balances（含 pending），
 *       同一张卡上的 {@code alertCount} 走 alerts（不含 pending）。</li>
 * </ul>
 * 结果：<b>同一屏出现两个库存数</b> —— 余额页显示 10 件，预警按 8 件判「低于下限」。
 *
 * <p><b>为什么断言的是「出口之间自洽」而不是某个具体数</b>：
 * 收口方案有两个候选（A：余额页去掉 pending，只认单据汇总；B：pending 为准，
 * 所有出口统一到它），还有别的可能。<b>本测试不替架构师拍板</b>，
 * 它只钉死一条与方案无关的硬契约：
 * <blockquote>
 * <b>同一时刻、同一 SKU，库存余额页、库存预警、库存流水、KPI 汇总这四个出口，
 * 必须给出同一个数。</b>
 * </blockquote>
 * 方案 A 修完它是绿的，方案 B 修完它也是绿的；不修它一定是红的。
 *
 * <p><b>当前状态（写这份测试时）：本类是红的，红的就是缺陷本身。</b>
 * 每一处失败信息都写清了「余额页说 X、预警页说 Y」。后端修完必须重跑本类，全绿才算收口。
 *
 * <p><b>断言不许退化</b>：{@code noPendingAlreadyAgrees} 这条<b>现在是绿的</b>，
 * 它证明本文件里的断言不是恒红（夹具有效、能区分对错）。删掉它，
 * 剩下的红就分不清是真缺陷还是断言写错了。
 */
class ErpStockCaliberConsistencyTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 常规夹具：1 个商品（下限 100，保证它一定出现在预警列表里）、2 个仓 */
    private ErpStockTestKit fx() {
        ErpStockTestKit kit = new ErpStockTestKit();
        kit.sku(1L, "SKU-A", "成品A", bd("100"), null);
        kit.wh(1L, "WH1", "主仓");
        kit.wh(2L, "WH2", "次仓");
        return kit;
    }

    /* ================================================================
     * 1. 核心：同一 SKU、同一仓，余额页与预警页必须同数
     * ================================================================ */

    @Test
    @DisplayName("余额页与预警页对同一 SKU 必须给出同一个数（有未成单增量时）")
    void balancesAndAlertsMustAgreeOnSameSku() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        // 单据 8 件（已落库）+ 未成单增量 2 件（已产生、还没成单）
        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);
        svc.addPendingStock(1L, 1L, bd("2"));

        BigDecimal balance = kit.balanceQty(svc, 1L, 1L);
        BigDecimal alert = kit.alertTotal(svc, 1L);

        assertEquals(0, balance.compareTo(alert),
                "同一个 SKU 在同一时刻，库存余额页与库存预警必须是同一个数。"
                        + "余额页说 " + balance.toPlainString() + "，预警页说 " + alert.toPlainString()
                        + " —— 这就是 DH-80 的双口径：余额页把「未成单生产增量」算进去了，"
                        + "预警页只算单据汇总。收口后两者必须相等。");
    }

    @Test
    @DisplayName("预警页 byWarehouse 各项之和 == 余额页该 SKU 各仓之和")
    void alertByWarehouseSumMustEqualBalanceRows() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);   // 主仓有单据
        svc.addPendingStock(1L, 2L, bd("3"));                    // 次仓只有未成单增量

        BigDecimal byWh = kit.alertWarehouseSum(svc, 1L);
        BigDecimal balanceSum = kit.balanceQtyForSku(svc, 1L);

        assertEquals(0, balanceSum.compareTo(byWh),
                "预警页按仓拆开的各项之和，必须等于余额页该 SKU 各仓之和。"
                        + "余额页合计 " + balanceSum.toPlainString()
                        + "，预警页分仓合计 " + byWh.toPlainString()
                        + " —— 说明两边看的不是同一份库存。");
    }

    @Test
    @DisplayName("KPI 汇总的 totalQty 必须等于余额页的数量之和（汇总不能走另一套口径）")
    void summaryTotalQtyMustFollowBalances() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);
        svc.addPendingStock(1L, 1L, bd("2"));

        Map<String, Object> summary = svc.summary();
        BigDecimal totalQty = (BigDecimal) summary.get("totalQty");
        BigDecimal balanceSum = kit.balanceTotal(svc);

        assertNotNull(totalQty, "summary 必须返回 totalQty");
        assertEquals(0, balanceSum.compareTo(totalQty),
                "summary.totalQty 必须等于余额页的数量之和 —— 现在它走的是 balances，"
                        + "收口时如果 balances 改了口径，summary 必须跟着改，"
                        + "否则同一屏又会出现两个数（这张卡上的 alertCount 就是前科）。");
    }

    @Test
    @DisplayName("流水页的结存必须等于余额页该 SKU 该仓的数量")
    void flowRunningBalanceMustEqualBalanceRow() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);
        svc.addPendingStock(1L, 1L, bd("2"));

        BigDecimal flowBal = kit.flowBalance(svc, 1L, 1L);
        BigDecimal balance = kit.balanceQty(svc, 1L, 1L);

        assertNotNull(flowBal, "夹具前提：有流水才能取结存");
        assertEquals(0, balance.compareTo(flowBal),
                "流水页的结存与余额页必须同数。余额页说 " + balance.toPlainString()
                        + "，流水结存说 " + flowBal.toPlainString()
                        + " —— 用户点进流水看到「结存 8」、回库存页看到「10」，"
                        + "第一反应就是系统坏了。");
    }

    @Test
    @DisplayName("只有未成单增量、还没有任何单据的 SKU：余额页与预警页也必须同数")
    void pendingOnlySkuMustAgreeAcrossExits() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        // 新 SKU 刚投产：一件都还没成单
        svc.addPendingStock(1L, 1L, bd("5"));

        BigDecimal balance = kit.balanceQty(svc, 1L, 1L);
        BigDecimal alert = kit.alertTotal(svc, 1L);

        assertEquals(0, balance.compareTo(alert),
                "只有未成单增量时，余额页显示 " + balance.toPlainString()
                        + "，预警页按 " + alert.toPlainString() + " 判低于下限 —— "
                        + "同一个 SKU 两屏两个数。这正是新产线刚开始生产时最常被看到的现场。");
    }

    /* ================================================================
     * 2. 有效性守卫：没有 pending 时，这些断言本来就该全绿
     *    （证明上面的红是真缺陷，不是断言写错或夹具坏了）
     * ================================================================ */

    @Test
    @DisplayName("没有未成单增量时，四个出口本来就一致（本测试保证断言不是恒红）")
    void noPendingAlreadyAgrees() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);

        BigDecimal balance = kit.balanceQty(svc, 1L, 1L);
        BigDecimal alert = kit.alertTotal(svc, 1L);
        BigDecimal flowBal = kit.flowBalance(svc, 1L, 1L);
        BigDecimal totalQty = (BigDecimal) svc.summary().get("totalQty");

        assertEquals(0, bd("8").compareTo(balance), "夹具前提：单据 8 件，余额页应显示 8");
        assertEquals(0, balance.compareTo(alert), "无 pending 时余额页与预警页必须一致");
        assertEquals(0, balance.compareTo(flowBal), "无 pending 时余额页与流水结存必须一致");
        assertEquals(0, balance.compareTo(totalQty), "无 pending 时余额页与汇总 totalQty 必须一致");
    }

    @Test
    @DisplayName("夹具有效性：单据汇总确实是 8，预警页确实按这个数判定下限")
    void fixtureIsMeaningful() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();
        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);

        // 账本口径 = 8
        assertEquals(0, bd("8").compareTo(kit.ledgerSum(1L, 1L)),
                "夹具前提：账本里必须有 8 件，否则下面的断言测不出任何东西");
        // 预警页用的是 8（不是 0，也不是别的数），并且确实判成「低于下限」
        assertEquals(0, bd("8").compareTo(kit.alertTotal(svc, 1L)));
        boolean low = false;
        for (Map<String, Object> m : svc.alerts()) {
            if (Long.valueOf(1L).equals(m.get("skuId")) && "LOW".equals(m.get("status"))) {
                low = true;
            }
        }
        assertEquals(true, low, "夹具前提：下限设成 100、库存 8，这个 SKU 必须出现在预警列表里");
    }

    /* ================================================================
     * 5. AC-10：新 SKU 刚投产、还没任何单据 —— 这一行必须看得见
     *
     * 这条是「别名式防护」：收口时最容易顺手把「qty == 0 就跳过」当成清理，
     * 结果新 SKU 首单的 10 秒窗口里库存页一行都没有，工人以为系统没记。
     * ⚠️ 变异验证记录：把跳过条件改回「只看 qty」时，本类其余 7 条**全绿**，
     *    只有这一条变红 —— 所以它必须留着。
     * ================================================================ */

    @Test
    @DisplayName("AC-10：账面为 0 但未过账 > 0 的行必须出现（新 SKU 刚投产时唯一可见的信号）")
    void pendingOnlyRowMustBeVisible() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        // 全新 SKU：没有任何单据，只有未过账增量
        svc.addPendingStock(1L, 1L, bd("5"));

        boolean rowFound = false;
        BigDecimal qtyOfRow = null;
        BigDecimal pendingOfRow = null;
        for (Map<String, Object> m : svc.balances(null, null)) {
            if (Long.valueOf(1L).equals(m.get("skuId"))
                    && Long.valueOf(1L).equals(m.get("warehouseId"))) {
                rowFound = true;
                qtyOfRow = (BigDecimal) m.get("qty");
                pendingOfRow = (BigDecimal) m.get("pendingQty");
            }
        }

        assertEquals(true, rowFound,
                "账面 0 件、未过账 5 件的行必须出现在余额表里 —— "
                        + "否则新 SKU 刚投产的 10 秒窗口内库存页一行都没有，"
                        + "现场会以为系统没记（AC-10）");
        assertNotNull(qtyOfRow, "这一行必须带 qty 字段（值应为 0）");
        assertEquals(0, BigDecimal.ZERO.compareTo(qtyOfRow),
                "这一行的账面数必须是 0 —— 还没有单据，未过账量不得混进 qty");
        assertNotNull(pendingOfRow, "这一行必须带 pendingQty 字段");
        assertEquals(0, bd("5").compareTo(pendingOfRow),
                "这一行的 pendingQty 必须是 5 —— 「机器在产出」靠它才看得见");
    }

    @Test
    @DisplayName("AC-12：balances 必须是数组（List），且每行都带 pendingQty 字段（无 pending 时为 0 不是 null）")
    void balancesContractUnchanged() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();
        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);

        var rows = svc.balances(null, null);
        assertEquals(true, rows instanceof java.util.List,
                "balances 必须仍返回 List —— 前端 stockApi.balances 直接当数组用，"
                        + "改成包一层对象会破契约（AC-12）");
        assertEquals(true, !rows.isEmpty(), "夹具有单据，余额表不该为空");
        for (Map<String, Object> m : rows) {
            assertEquals(true, m.containsKey("pendingQty"),
                    "每行都必须有 pendingQty 字段（无未过账量时为 0，不能缺字段也不能是 null）"
                            + " —— 前端 v-if=\"row.pendingQty > 0\" 依赖它");
            assertEquals(true, m.get("qty") instanceof BigDecimal, "qty 必须是数值");
            assertEquals(true, m.get("pendingQty") instanceof BigDecimal,
                    "pendingQty 必须是数值（BigDecimal），不能是 null 或字符串");
        }
    }

    @Test
    @DisplayName("AC-7：summary 必须带 stockMode == \"BILL\" 与 pendingTotal")
    void summaryDeclaresCaliber() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();
        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);
        svc.addPendingStock(1L, 1L, bd("2"));

        Map<String, Object> sum = svc.summary();
        assertEquals("BILL", sum.get("stockMode"),
                "summary 必须正向声明口径 stockMode=\"BILL\"（先例 meterMode）");
        assertNotNull(sum.get("pendingTotal"), "summary 必须带 pendingTotal");
        assertEquals(0, bd("2").compareTo((BigDecimal) sum.get("pendingTotal")),
                "pendingTotal 必须是全厂未过账合计 2");
        assertEquals(0, bd("8").compareTo((BigDecimal) sum.get("totalQty")),
                "totalQty 必须是账面 8 —— 不是 8+2=10（那正是本次收口前的缺陷）");
    }
}
