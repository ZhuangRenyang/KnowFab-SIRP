package com.factory.hub.erp.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>DH-80：未成单生产增量（pendingStock）的生命周期与「重启即丢」行为。</b>
 *
 * <p><b>pendingStock 是什么</b>：生产入库按 10 秒批量成单（单据整洁），
 * 但产量是每按一下就 +1 的。若只等单据，库存数字要等 10 秒才动。
 * 于是把「已产生、还没成单」的增量先叠加到余额上，让库存秒级可见，单据落库后再扣掉。
 * <pre>
 *   余额页看到的数 = 单据汇总 + pendingStock
 * </pre>
 *
 * <p><b>它带来两个新问题</b>：
 * <ol>
 *   <li><b>口径分裂</b>：只有余额页算它，预警页/流水页不算（同屏两个数，DH-80 本体）；</li>
 *   <li><b>它是纯内存的</b>：进程一重启，pending 里的量就没了 ——
 *       库里的单据还没落（10 秒批次没到），账上的增量却已经没了。</li>
 * </ol>
 *
 * <p><b>本类把「重启后到底该怎样」留给架构师裁决</b>：
 * <ul>
 *   <li>{@link RestartPolicy#DROP_PENDING} —— 回落：重启后余额 = 单据汇总
 *       （口径 A：库存只认单据，pending 只是「界面上的实时提示」，丢了就丢了）；</li>
 *   <li>{@link RestartPolicy#PERSIST_PENDING} —— 保留：重启后余额 = 单据汇总 + 未成单增量
 *       （口径 B：pending 是真实库存，必须落库持久化）。</li>
 * </ul>
 * <b>裁决前</b>：本类只跑与策略无关的<b>不变量</b>（不漂移、各出口仍自洽），
 * 这两条现在就是绿的，修复方案落地后也必须是绿的。
 * <b>裁决后</b>：把 {@link #DECIDED_RESTART_POLICY} 改成对应值，
 * {@link #restartMustFollowDecidedPolicy} 就会断言具体数字。
 *
 * <p><b>不变量为什么重要</b>：不管选哪个策略，
 * 「重启后余额落在 [单据汇总, 单据汇总+未成单增量] 之间」都必须成立 ——
 * 小于下限说明<b>已落单的库存被弄丢了</b>（比 pending 丢失严重得多），
 * 大于上限说明<b>凭空造出了库存</b>（货没来，账上有）。
 */
class ErpStockPendingLifecycleTest {

    /** 重启后未成单增量的处置策略 */
    enum RestartPolicy {
        /** 回落：重启后余额 = 单据汇总 */
        DROP_PENDING,
        /** 保留：重启后余额 = 单据汇总 + 未成单增量（需要落库持久化） */
        PERSIST_PENDING
    }

    /**
     * <b>架构师裁决后改这里</b>（并同步改实现）。
     * <p>当前实现表现为「回落」：{@code pendingStock} 是 {@code ConcurrentHashMap}，
     * 进程结束就没了，没有任何持久化。
     */
    private static final RestartPolicy DECIDED_RESTART_POLICY = RestartPolicy.DROP_PENDING;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private ErpStockTestKit fx() {
        ErpStockTestKit kit = new ErpStockTestKit();
        kit.sku(1L, "SKU-A", "成品A", bd("100"), null);
        kit.wh(1L, "WH1", "主仓");
        return kit;
    }

    /* ================================================================
     * 1. 生命周期：加 → 看得见 → 成单 → 扣掉（不能重复计）
     * ================================================================ */

    @Test
    @DisplayName("未过账增量在单据落库前必须可见 —— 但要出现在 pendingQty，不能混进 qty（DH-80 裁决后）")
    void pendingIsVisibleBeforeBillExists() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.addPendingStock(1L, 1L, bd("3"));

        // 「机器在产出」这件事必须看得见 —— 但看的是 pendingQty
        assertEquals(0, bd("3").compareTo(kit.pendingQty(svc, 1L, 1L)),
                "刚产出还没成单，pendingQty 必须立刻显示 3（否则用户要等 10 秒才知道产出了）");
        // 而账面数纹丝不动：pending 记的是原始总产出（含不良），
        // 混进 qty 会在成单瞬间掉一批不良数（DH-80 §2.1）
        assertEquals(0, BigDecimal.ZERO.compareTo(kit.balanceQty(svc, 1L, 1L)),
                "未过账增量不得混进 qty —— 它不是库存，只是「还没过账」的提示");
    }

    @Test
    @DisplayName("单据落库并扣掉增量后，余额不能重复计算（3 不是 6）")
    void pendingMustNotBeCountedTwice() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.addPendingStock(1L, 1L, bd("3"));
        svc.createProdInbound("DEV1", 1L, 1L, bd("3"), false);   // 单据落库
        svc.clearPendingStock(1L, 1L, bd("3"));                  // 增量交给单据汇总接管

        assertEquals(0, bd("3").compareTo(kit.balanceQty(svc, 1L, 1L)),
                "余额 = 单据汇总 3 + 未成单增量 0 = 3；写成 6 就是重复计算了");
    }

    @Test
    @DisplayName("有未成单增量时，四个出口仍然必须同数（口径契约）")
    void pendingLifecycleMustStayConsistentAcrossExits() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);
        svc.addPendingStock(1L, 1L, bd("2"));

        BigDecimal balance = kit.balanceQty(svc, 1L, 1L);
        BigDecimal alert = kit.alertTotal(svc, 1L);
        BigDecimal flowBal = kit.flowBalance(svc, 1L, 1L);
        BigDecimal totalQty = (BigDecimal) svc.summary().get("totalQty");

        assertEquals(0, balance.compareTo(alert),
                "余额页 " + balance.toPlainString() + " vs 预警页 " + alert.toPlainString()
                        + " —— 未成单增量在两个出口的表现必须一致");
        assertEquals(0, balance.compareTo(flowBal), "流水结存也必须同数");
        assertEquals(0, balance.compareTo(totalQty), "KPI 汇总也必须同数");
    }

    @Test
    @DisplayName("空参数是安全的空操作（不抛异常、不写脏数据）")
    void nullArgumentsAreNoOp() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.addPendingStock(null, 1L, bd("3"));
        svc.addPendingStock(1L, null, bd("3"));
        svc.addPendingStock(1L, 1L, null);
        svc.clearPendingStock(null, 1L, bd("3"));

        assertEquals(0, BigDecimal.ZERO.compareTo(kit.balanceTotal(svc)),
                "空参数不能凭空造出库存");
    }

    /* ================================================================
     * 2. 重启：pending 是纯内存的
     * ================================================================ */

    @Test
    @DisplayName("重启后余额必须落在 [单据汇总, 单据汇总+未成单增量] 之间（不漂移）")
    void restartMustNotDriftInventory() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);
        svc.addPendingStock(1L, 1L, bd("2"));

        BigDecimal bill = kit.ledgerSum(1L, 1L);              // 单据汇总 = 8
        BigDecimal pendingTotal = bd("2");                     // 未成单增量 = 2

        // 重启 = 同一套库 + 一个新的服务实例（pending 是实例字段，随进程一起没了）
        ErpStockService restarted = kit.stockService();
        BigDecimal after = kit.balanceQty(restarted, 1L, 1L);

        assertTrue(after.compareTo(bill) >= 0,
                "重启后余额 " + after.toPlainString() + " 小于单据汇总 " + bill.toPlainString()
                        + " —— 已落单的库存被弄丢了，这比 pending 丢失严重得多");
        assertTrue(after.compareTo(bill.add(pendingTotal)) <= 0,
                "重启后余额 " + after.toPlainString() + " 大于「单据汇总 + 未成单增量」 "
                        + bill.add(pendingTotal).toPlainString()
                        + " —— 凭空造出了库存（货没来，账上有）");
    }

    @Test
    @DisplayName("重启后各出口仍然必须自洽（无论裁决是回落还是保留）")
    void afterRestartAllExitsMustStillAgree() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);
        svc.addPendingStock(1L, 1L, bd("2"));

        ErpStockService restarted = kit.stockService();

        BigDecimal balance = kit.balanceQty(restarted, 1L, 1L);
        BigDecimal alert = kit.alertTotal(restarted, 1L);
        BigDecimal flowBal = kit.flowBalance(restarted, 1L, 1L);
        Map<String, Object> summary = restarted.summary();
        BigDecimal totalQty = (BigDecimal) summary.get("totalQty");

        assertEquals(0, balance.compareTo(alert),
                "重启后余额页 " + balance.toPlainString() + " 与预警页 " + alert.toPlainString()
                        + " 必须一致 —— 重启不是收口方案，不能让双口径复活");
        assertEquals(0, balance.compareTo(flowBal), "重启后流水结存也必须同数");
        assertEquals(0, balance.compareTo(totalQty), "重启后 KPI 汇总也必须同数");
    }

    @Test
    @DisplayName("重启后未成单增量的处置：按已裁决的策略断言（口径变更必须有人签字）")
    void restartMustFollowDecidedPolicy() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);
        svc.addPendingStock(1L, 1L, bd("2"));

        BigDecimal bill = kit.ledgerSum(1L, 1L);        // 8
        BigDecimal pendingTotal = bd("2");               // 2

        ErpStockService restarted = kit.stockService();
        BigDecimal after = kit.balanceQty(restarted, 1L, 1L);

        switch (DECIDED_RESTART_POLICY) {
            case DROP_PENDING -> assertEquals(0, bill.compareTo(after),
                    "裁决=回落：重启后余额应等于单据汇总 " + bill.toPlainString()
                            + "，实际是 " + after.toPlainString()
                            + "。若架构师改判为「保留」，请改 DECIDED_RESTART_POLICY 与实现；"
                            + "若实现先改了而这里没改，本用例会红 —— 这是有意的：口径变更必须有人签字。");
            case PERSIST_PENDING -> assertEquals(0, bill.add(pendingTotal).compareTo(after),
                    "裁决=保留：重启后余额应等于单据汇总 + 未成单增量 = "
                            + bill.add(pendingTotal).toPlainString()
                            + "，实际是 " + after.toPlainString()
                            + "。实现需要把 pending 落库持久化。");
        }
    }

    @Test
    @DisplayName("DH-80 裁决后：重启不影响账面数，只有 pendingQty 归零（可证明的保证）")
    void restartDoesNotChangeBookBalance() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        svc.createProdInbound("DEV1", 1L, 1L, bd("8"), false);
        svc.addPendingStock(1L, 1L, bd("2"));

        // 账面 = 单据汇总 8；未过账 2 单列 —— 两个数分家，不再相加
        BigDecimal beforeQty = kit.balanceQty(svc, 1L, 1L);
        BigDecimal beforePending = kit.pendingQty(svc, 1L, 1L);

        ErpStockService restarted = kit.stockService();
        BigDecimal afterQty = kit.balanceQty(restarted, 1L, 1L);
        BigDecimal afterPending = kit.pendingQty(restarted, 1L, 1L);

        assertEquals(0, bd("8").compareTo(beforeQty), "重启前账面 = 单据汇总 8");
        // 这条是 DH-80 的核心保证：qty 是 DB 的纯函数，进程生死不影响它
        assertEquals(0, beforeQty.compareTo(afterQty),
                "重启后账面数必须完全不变 —— qty 只读单据汇总，pending 从来没进过账");
        assertEquals(0, bd("2").compareTo(beforePending), "重启前 pendingQty = 2");
        assertEquals(0, BigDecimal.ZERO.compareTo(afterPending),
                "pendingQty 是内存值，重启归零 —— 这是提示字段，归零不影响账面");
    }
}
