package com.factory.hub.erp.quality.defect;

import com.factory.hub.erp.stock.ErpStockBill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 处置 × 库存状态 六分支测试（QC-SPEC §3.4 / AC-01）。
 *
 * <p><b>这是整个模块唯一"错了不会报错"的地方</b>：报废方向反了库存会凭空涨、
 * 让步重复入库、返工让不良品继续挂在成品仓当良品卖。这些都不会抛异常，
 * 只在月底盘账时炸，那时货已经发出去了。
 *
 * <p>所以每一支都断言四件事：<b>建了几张库存单、什么类型、qty 的符号、源仓/目标仓对不对</b>。
 * 数量这里恒为正——方向由单据类型决定（{@code ErpStockService.isOutbound} 统一取负）。
 * 如果哪天有人让这里也传负数，出库单会变成"负出库"＝入库，这条断言会立刻红。
 */
class ErpQualityStockAdapterTest {

    private static final Long WH_STOCK = 1L;        // 货当前所在仓（成品仓）
    private static final Long WH_BLOCKED = 9L;      // 待处理仓（禁发仓）

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static ErpQualityStockAdapter.Ctx ctx(boolean inStock, String action, String qty) {
        return new ErpQualityStockAdapter.Ctx(inStock, action, bd(qty),
                inStock ? WH_STOCK : null, WH_BLOCKED, "BLP20261103-0001");
    }

    /* ==================== 分支①：报废 × 未入账 ==================== */

    @Test
    @DisplayName("分支① 报废 + 未入账 → 一张单都不建，只改状态（去扣库存等于扣别人的货）")
    void scrapNotInStockCreatesNothing() {
        ErpQualityStockAdapter.Plan p = ErpQualityStockAdapter.plan(
                ctx(false, ErpQcDisposition.ACT_SCRAP, "8"));
        assertEquals(0, p.stockBillCount(), "从未入账的货不能动库存");
        assertEquals(0, p.reworkOrderCount());
        assertTrue(p.isEmpty());
        assertNull(p.warnCode(), "这一支没有需要人额外注意的风险");
    }

    /* ==================== 分支②：报废 × 已入账 ==================== */

    @Test
    @DisplayName("分支② 报废 + 已入账 → 一张 SCRAP_OUT 出库单，从所在仓出、qty 为正（方向由类型定）")
    void scrapInStockCreatesScrapOut() {
        ErpQualityStockAdapter.Plan p = ErpQualityStockAdapter.plan(
                ctx(true, ErpQcDisposition.ACT_SCRAP, "8"));
        assertEquals(1, p.stockBillCount());
        assertEquals(0, p.reworkOrderCount());
        ErpQualityStockAdapter.Intent i = p.intents().get(0);
        assertEquals(ErpQualityStockAdapter.Op.STOCK_BILL, i.op());
        assertEquals(ErpStockBill.TYPE_SCRAP_OUT, i.billType());
        assertEquals(0, i.qty().signum() > 0 ? 0 : 1,
                "qty 必须是正数：方向由 billType=SCRAP_OUT 决定，"
                        + "这里再传负号会变成「负出库」＝入库，库存凭空涨");
        assertEquals(WH_STOCK, i.whId(), "报废要扣货所在的仓");
        assertNull(i.srcWhId(), "报废不是调拨，没有源仓");
        assertEquals("BLP20261103-0001", i.refNo(), "不良单号要落到单据的 src_order_no，追溯链靠它");
        assertTrue(i.remark().contains("BLP20261103-0001"), "备注里写清是哪张不良单来的");
    }

    /* ==================== 分支③：返工 × 未入账 ==================== */

    @Test
    @DisplayName("分支③ 返工 + 未入账 → 只建 REWORK 工单，不动库存")
    void reworkNotInStockCreatesOrderOnly() {
        ErpQualityStockAdapter.Plan p = ErpQualityStockAdapter.plan(
                ctx(false, ErpQcDisposition.ACT_REWORK, "6"));
        assertEquals(0, p.stockBillCount(), "货不在仓里，没有库存可动");
        assertEquals(1, p.reworkOrderCount());
        assertEquals(ErpQualityStockAdapter.Op.REWORK_ORDER, p.intents().get(0).op());
        assertEquals(0, p.intents().get(0).qty().compareTo(bd("6")));
        assertNull(p.warnCode(), "未入账的返工没有额外风险提示");
    }

    /* ==================== 分支④：返工 × 已入账 ==================== */

    @Test
    @DisplayName("分支④ 返工 + 已入账 → 只建工单、不动库存，并提示人工先领料出库")
    void reworkInStockDoesNotTouchStock() {
        ErpQualityStockAdapter.Plan p = ErpQualityStockAdapter.plan(
                new ErpQualityStockAdapter.Ctx(true, ErpQcDisposition.ACT_REWORK, bd("6"),
                        WH_STOCK, WH_BLOCKED, "BLP20261103-0001", "成品仓"));
        assertEquals(0, p.stockBillCount(),
                "返工不自动出库：领料要去车间，而车间是不是一个仓由客户定义，系统不该替它选");
        assertEquals(1, p.reworkOrderCount());
        assertEquals(ErpQualityStockAdapter.WARN_REWORK_NEED_ISSUE, p.warnCode(),
                "提示只给代号，文案由前端 i18n 写");
        assertEquals("成品仓", p.warnParams().get("warehouseName"),
                "提示参数里要有仓库名，前端才知道货在哪");
    }

    @Test
    @DisplayName("分支④ 的边界：没给仓名时提示里也要有可读的东西，不能是空白")
    void reworkWarningAlwaysHasWarehouse() {
        ErpQualityStockAdapter.Plan p = ErpQualityStockAdapter.plan(
                ctx(true, ErpQcDisposition.ACT_REWORK, "6"));
        assertEquals(ErpQualityStockAdapter.WARN_REWORK_NEED_ISSUE, p.warnCode());
        assertEquals("仓库#1", p.warnParams().get("warehouseName"),
                "没给仓名时也要有可读的兜底");
    }

    /* ==================== 分支⑤：让步 × 未入账 ==================== */

    @Test
    @DisplayName("分支⑤ 让步 + 未入账 → 一张 OTHER_IN 入待处理仓（不新增 CONCESSION_IN 类型）")
    void concessionNotInStockCreatesOtherIn() {
        ErpQualityStockAdapter.Plan p = ErpQualityStockAdapter.plan(
                ctx(false, ErpQcDisposition.ACT_CONCESSION, "2"));
        assertEquals(1, p.stockBillCount());
        ErpQualityStockAdapter.Intent i = p.intents().get(0);
        assertEquals(ErpStockBill.TYPE_OTHER_IN, i.billType(),
                "让步入库复用 OTHER_IN：让价损失记在处置明细的 concession_amount 上，"
                        + "发货风险由待处理仓这个仓维度硬拦");
        assertEquals(WH_BLOCKED, i.whId(), "必须入待处理仓，不能入良品仓");
        assertNull(i.srcWhId());
        assertEquals(0, i.qty().signum() > 0 ? 0 : 1);
        assertEquals(ErpQualityStockAdapter.WARN_CONCESSION_NEED_ACK, p.warnCode());
    }

    /* ==================== 分支⑥：让步 × 已入账 ==================== */

    @Test
    @DisplayName("分支⑥ 让步 + 已入账 → TRANSFER 所在仓 → 待处理仓")
    void concessionInStockTransfers() {
        ErpQualityStockAdapter.Plan p = ErpQualityStockAdapter.plan(
                ctx(true, ErpQcDisposition.ACT_CONCESSION, "2"));
        assertEquals(1, p.stockBillCount());
        ErpQualityStockAdapter.Intent i = p.intents().get(0);
        assertEquals(ErpStockBill.TYPE_TRANSFER, i.billType());
        assertEquals(WH_STOCK, i.srcWhId());
        assertEquals(WH_BLOCKED, i.whId());
        assertEquals(0, i.qty().signum() > 0 ? 0 : 1);
    }

    @Test
    @DisplayName("分支⑥ 的边界：让步但货已在待处理仓 → 无库存动作，仅提示")
    void concessionAlreadyInBlockedWarehouse() {
        ErpQualityStockAdapter.Plan p = ErpQualityStockAdapter.plan(
                new ErpQualityStockAdapter.Ctx(true, ErpQcDisposition.ACT_CONCESSION, bd("2"),
                        WH_BLOCKED, WH_BLOCKED, "BLP20261103-0003"));
        assertEquals(0, p.stockBillCount());
        assertTrue(p.isEmpty());
    }

    /* ==================== 六分支的整体不变量 ==================== */

    @Test
    @DisplayName("六分支全覆盖：每支的库存单据数与类型都能对上 Spec §3.4 的表格")
    void allSixBranchesCovered() {
        // 未入账侧：报废不动、返工不建单、让步建 OTHER_IN
        assertEquals(0, ErpQualityStockAdapter.plan(
                ctx(false, ErpQcDisposition.ACT_SCRAP, "1")).stockBillCount());
        assertEquals(0, ErpQualityStockAdapter.plan(
                ctx(false, ErpQcDisposition.ACT_REWORK, "1")).stockBillCount());
        assertEquals(1, ErpQualityStockAdapter.plan(
                ctx(false, ErpQcDisposition.ACT_CONCESSION, "1")).stockBillCount());

        // 已入账侧：报废 SCRAP_OUT、返工只建工单（不自动出库）、让步 TRANSFER
        assertEquals(java.util.List.of(ErpStockBill.TYPE_SCRAP_OUT), ErpQualityStockAdapter.plan(
                ctx(true, ErpQcDisposition.ACT_SCRAP, "1")).billTypes());
        assertEquals(0, ErpQualityStockAdapter.plan(
                ctx(true, ErpQcDisposition.ACT_REWORK, "1")).stockBillCount(),
                "返工已入账也不自动出库");
        assertEquals(java.util.List.of(ErpStockBill.TYPE_TRANSFER), ErpQualityStockAdapter.plan(
                ctx(true, ErpQcDisposition.ACT_CONCESSION, "1")).billTypes());
    }

    @Test
    @DisplayName("所有分支的 qty 都必须是正数：方向只由单据类型决定")
    void everyQtyIsPositive() {
        for (boolean inStock : new boolean[]{true, false}) {
            for (String action : ErpQcDisposition.ACTIONS) {
                ErpQualityStockAdapter.Plan p = ErpQualityStockAdapter.plan(ctx(inStock, action, "7"));
                for (ErpQualityStockAdapter.Intent i : p.intents()) {
                    if (i.qty() != null) {
                        assertTrue(i.qty().signum() > 0,
                                action + "(inStock=" + inStock + ") 的 qty 必须为正");
                    }
                }
            }
        }
    }

    /* ==================== 输入校验：宁可报错也不静默不动库存 ==================== */

    @Test
    @DisplayName("已入账却不知道货在哪个仓 → 报错，不能静默不动库存")
    void inStockWithoutWarehouseRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ErpQualityStockAdapter.plan(new ErpQualityStockAdapter.Ctx(
                        true, ErpQcDisposition.ACT_SCRAP, bd("2"), null, WH_BLOCKED, "BLP0001")));
        assertTrue(ex.getMessage().contains("所在仓库"), ex.getMessage());
    }

    @Test
    @DisplayName("让步没有待处理仓 → 报错并说清去哪建；返工不再需要待处理仓")
    void missingBlockedWarehouseRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ErpQualityStockAdapter.plan(new ErpQualityStockAdapter.Ctx(
                        false, ErpQcDisposition.ACT_CONCESSION, bd("2"), null, null, "BLP0001")));
        assertTrue(ex.getMessage().contains("待处理仓"), ex.getMessage());
        assertTrue(ex.getMessage().contains("禁发仓"), "要告诉人仓库类型设成什么：" + ex.getMessage());

        // 返工不碰待处理仓（系统不代做领料出库），所以没有待处理仓也能建工单
        ErpQualityStockAdapter.Plan p = ErpQualityStockAdapter.plan(
                new ErpQualityStockAdapter.Ctx(true, ErpQcDisposition.ACT_REWORK, bd("2"),
                        WH_STOCK, null, "BLP0001"));
        assertEquals(0, p.stockBillCount());
        assertEquals(1, p.reworkOrderCount());
    }

    @Test
    @DisplayName("件数 ≤ 0 或不支持的处置方式直接拒绝")
    void badInputsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ErpQualityStockAdapter.plan(ctx(false, ErpQcDisposition.ACT_SCRAP, "0")));
        assertThrows(IllegalArgumentException.class,
                () -> ErpQualityStockAdapter.plan(ctx(false, "CONCESSION_IN", "1")));
    }
}
