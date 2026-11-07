package com.factory.hub.erp.stock;

import com.factory.hub.model.DeviceEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>DH-80：不良数（defect）与成品入库量的口径，以及 pending 机制本身是否漂移。</b>
 *
 * <p><b>为什么不良数最容易出错</b>：生产入库单上写的不是「总产出」，
 * 而是按设备主计数器口径换算出来的<b>良品入库量</b>
 * （{@code ErpInboundQty.resolve}，QC-SPEC §2.3 / §7 F1）：
 * <ul>
 *   <li><b>TOTAL</b>（主计数器含不良）：入库量 = 总产出 − 不良；产出 10、不良 2 ⇒ 入库 <b>8</b>；</li>
 *   <li><b>GOOD</b>（主计数器只算良品）：入库量 = 总产出 ⇒ 产出 10、不良 2 ⇒ 入库 <b>10</b>。</li>
 * </ul>
 * 两个方向都错得很安静：按总产出入库 ⇒ <b>不良品被当良品进了成品仓</b>（账面永远比现实多）；
 * 在 GOOD 口径下再减一次不良 ⇒ <b>不良算了两遍</b>（账面凭空少一截）。
 *
 * <p><b>本类同时独立复核 pending 机制本身是否漂移</b>（不假设它是对的）：
 * pending 加的是「总产出 10」，成单时扣的也必须是当初加的 10，
 * 而单据上写的是良品量 8。三条数对不上就会漂 —— 余额会变成 10 或 8+2。
 *
 * <p><b>本类现在是绿的</b>：这是「已实现且正确的行为」的护栏，
 * 收口方案（A/B）无论怎么选，这些数字都不该变。
 */
class ErpStockDefectQtyTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static final long TS = 1_700_000_000_000L;

    private ErpStockTestKit fx() {
        ErpStockTestKit kit = new ErpStockTestKit();
        kit.sku(1L, "SKU-A", "成品A", bd("100"), null);
        kit.wh(1L, "WH1", "主仓");
        return kit;
    }

    /** 跑一遍完整的「产出 → 不良 → 成单」，返回成单后的成品仓余额 */
    private BigDecimal runBatch(String counterMode, double output, double defect,
                                ErpStockTestKit kit, ErpStockService svc) {
        ErpProdInboundService prod =
                kit.prodInboundService("DEV1", 1L, 1L, counterMode, svc);
        prod.onProduced("DEV1", output, TS, false);
        if (defect > 0) {
            prod.onRejected("DEV1", defect, TS);
        }
        prod.flush();
        return kit.balanceQty(svc, 1L, 1L);
    }

    /* ================================================================
     * 1. 两种口径的良品入库量
     * ================================================================ */

    @Test
    @DisplayName("TOTAL 口径：产出 10、不良 2 ⇒ 成品仓入库 8（不良品不许进成品仓）")
    void totalModeSubtractsDefect() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        BigDecimal balance = runBatch(DeviceEntity.COUNTER_TOTAL, 10, 2, kit, svc);

        assertEquals(0, bd("8").compareTo(balance),
                "TOTAL 口径下成品仓必须是 8（10 产出 − 2 不良），实际是 " + balance.toPlainString()
                        + " —— 不良品进了成品仓，账面永远比现实多一截，而且一句错都不报");
        assertEquals(0, bd("8").compareTo(kit.ledgerSum(1L, 1L)),
                "单据上写的也必须是 8（不是 10）");
    }

    @Test
    @DisplayName("GOOD 口径：产出 10、不良 2 ⇒ 成品仓入库 10（不能再减一次不良）")
    void goodModeDoesNotSubtractDefect() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        BigDecimal balance = runBatch(DeviceEntity.COUNTER_GOOD, 10, 2, kit, svc);

        assertEquals(0, bd("10").compareTo(balance),
                "GOOD 口径下主计数器本来就是良品数，入库必须是 10，实际是 " + balance.toPlainString()
                        + " —— 不良被算了两遍，库存凭空少一截");
        assertEquals(0, bd("10").compareTo(kit.ledgerSum(1L, 1L)), "单据上写的也必须是 10");
    }

    @Test
    @DisplayName("有不良的余额永远不得大于没有不良的余额（不良品不占成品仓）")
    void balanceWithDefectIsNeverGreaterThanWithoutDefect() {
        // TOTAL 口径：有不良比没不良少
        ErpStockTestKit k1 = fx();
        ErpStockService s1 = k1.stockService();
        BigDecimal withDefect = runBatch(DeviceEntity.COUNTER_TOTAL, 10, 2, k1, s1);

        ErpStockTestKit k2 = fx();
        ErpStockService s2 = k2.stockService();
        BigDecimal withoutDefect = runBatch(DeviceEntity.COUNTER_TOTAL, 10, 0, k2, s2);

        assertTrue(withDefect.compareTo(withoutDefect) <= 0,
                "同样产出 10 件，有不良 2 件时余额 " + withDefect.toPlainString()
                        + " 不得大于无不良时的 " + withoutDefect.toPlainString()
                        + " —— 不良品不进成品仓这条铁律");
        assertEquals(0, bd("8").compareTo(withDefect));
        assertEquals(0, bd("10").compareTo(withoutDefect));

        // GOOD 口径：有不良与没不良必须相等（不良只是记录，不影响入库量）
        ErpStockTestKit k3 = fx();
        ErpStockService s3 = k3.stockService();
        BigDecimal goodWith = runBatch(DeviceEntity.COUNTER_GOOD, 10, 2, k3, s3);
        assertEquals(0, goodWith.compareTo(withoutDefect),
                "GOOD 口径下有不良时余额 " + goodWith.toPlainString()
                        + " 必须等于无不良时的 " + withoutDefect.toPlainString());
    }

    @Test
    @DisplayName("不良数大于产出（两个计数器采样错位）⇒ 夹到 0，库存不许变负")
    void defectGreaterThanOutputNeverMakesStockNegative() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();

        BigDecimal balance = runBatch(DeviceEntity.COUNTER_TOTAL, 2, 5, kit, svc);

        assertTrue(balance.compareTo(BigDecimal.ZERO) >= 0,
                "不良数大于产出时余额不能是负数，实际是 " + balance.toPlainString());
        assertEquals(0, BigDecimal.ZERO.compareTo(balance),
                "采样错位时按 0 入库（并记告警），下一批产出会补回差额");
    }

    /* ================================================================
     * 2. 独立复核：pending 机制本身会不会漂移
     * ================================================================ */

    @Test
    @DisplayName("DH-80 核心保证：成单全过程账面数单调不减，不得「掉不良数」（旧口径会从 10 掉到 8）")
    void pendingMechanismDoesNotDrift() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();
        ErpProdInboundService prod =
                kit.prodInboundService("DEV1", 1L, 1L, DeviceEntity.COUNTER_TOTAL, svc);

        // 产出 10：进 pending，账面不动（因为 pending 记的是原始总产出，还没换成良品量）
        prod.onProduced("DEV1", 10, TS, false);
        assertEquals(0, BigDecimal.ZERO.compareTo(kit.balanceQty(svc, 1L, 1L)),
                "未成单阶段：账面 = 单据 0（pending 不混进 qty）");
        assertEquals(0, bd("10").compareTo(kit.pendingQty(svc, 1L, 1L)),
                "未成单阶段：pendingQty = 10（「机器在产出」看得见）");

        // 不良 2：与产出分开两个入口累加
        prod.onRejected("DEV1", 2, TS);
        assertEquals(0, BigDecimal.ZERO.compareTo(kit.balanceQty(svc, 1L, 1L)),
                "不良数还没成单时，账面仍为 0");

        // 成单：单据写良品 8，pending 扣掉当初加的 10
        prod.flush();
        assertEquals(0, bd("8").compareTo(kit.balanceQty(svc, 1L, 1L)),
                "成单后：账面 = 单据 8（单调不减：0 → 8，全程没有回落）。"
                        + "旧口径这里是 10 → 8 —— 客户会看到库存「掉 2 件」，那正是 DH-80 要修的");
        assertEquals(0, bd("8").compareTo(kit.ledgerSum(1L, 1L)), "单据汇总 = 8");
    }

    @Test
    @DisplayName("pending 机制跨多批次不漂移（10+5 产出、2 不良 ⇒ 最终 13）")
    void pendingDoesNotDriftAcrossBatches() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();
        ErpProdInboundService prod =
                kit.prodInboundService("DEV1", 1L, 1L, DeviceEntity.COUNTER_TOTAL, svc);

        // 第一批：产出 10、不良 2 ⇒ 入库 8
        prod.onProduced("DEV1", 10, TS, false);
        prod.onRejected("DEV1", 2, TS);
        prod.flush();
        assertEquals(0, bd("8").compareTo(kit.balanceQty(svc, 1L, 1L)));

        // 第二批：产出 5、无不良 ⇒ 入库 5
        prod.onProduced("DEV1", 5, TS + 1000, false);
        prod.flush();
        assertEquals(0, bd("13").compareTo(kit.balanceQty(svc, 1L, 1L)),
                "两批之后余额必须是 8 + 5 = 13（每批各扣各的 pending，不多扣不少扣）");
        assertEquals(0, bd("13").compareTo(kit.ledgerSum(1L, 1L)), "单据汇总也是 13");
    }

    @Test
    @DisplayName("重复 flush 不会重复落单、不会重复扣 pending（幂等）")
    void flushIsIdempotent() {
        ErpStockTestKit kit = fx();
        ErpStockService svc = kit.stockService();
        ErpProdInboundService prod =
                kit.prodInboundService("DEV1", 1L, 1L, DeviceEntity.COUNTER_TOTAL, svc);

        prod.onProduced("DEV1", 10, TS, false);
        prod.onRejected("DEV1", 2, TS);
        prod.flush();
        int rowsAfterFirst = kit.ledgerRowCount();
        prod.flush();   // 再来一次：缓冲已清，不应再落单

        assertEquals(rowsAfterFirst, kit.ledgerRowCount(), "重复 flush 不能多落一行单");
        assertEquals(0, bd("8").compareTo(kit.balanceQty(svc, 1L, 1L)), "余额仍是 8");
    }
}
