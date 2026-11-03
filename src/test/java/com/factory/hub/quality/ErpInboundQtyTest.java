package com.factory.hub.quality;

import com.factory.hub.erp.ErpInboundQty;
import com.factory.hub.model.DeviceEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产入库量口径换算测试（QC-SPEC §7 F1 / AC-08）。
 *
 * <p>核心断言只有一条：<b>产出 10 件、不良 2 件 → 入库必须是 8 件</b>。
 * 这条路径原来的实现是把 {@code BigDecimal.ZERO} 当不良数传下去、
 * 再把"总产出"当良品入库，结果是<b>不良品被当良品进了成品仓</b>，
 * 账面库存永远比现实多一截，而且一句报错都没有。
 *
 * <p>第二种口径（GOOD）同样要测：现场有设备的主计数器本来就只算良品，
 * 这时再减一次不良等于把不良算了两遍，库存会凭空少一截——
 * 两个方向都错得很安静，只能靠单测钉住。
 */
class ErpInboundQtyTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("TOTAL 口径：产出 10、不良 2 → 入库 8（AC-08）")
    void totalModeSubtractsDefect() {
        ErpInboundQty q = ErpInboundQty.resolve(DeviceEntity.COUNTER_TOTAL, bd("10"), bd("2"));
        assertEquals(0, q.good().compareTo(bd("8")), "入库量必须是 8，不是 10");
        assertEquals(0, q.defect().compareTo(bd("2")));
        assertEquals(0, q.total().compareTo(bd("10")));
        assertFalse(q.clamped());
        assertTrue(q.hasDefect());
    }

    @Test
    @DisplayName("GOOD 口径：产出 10（本就是良品）、不良 2 → 入库仍是 10，不能再减")
    void goodModeDoesNotSubtract() {
        ErpInboundQty q = ErpInboundQty.resolve(DeviceEntity.COUNTER_GOOD, bd("10"), bd("2"));
        assertEquals(0, q.good().compareTo(bd("10")),
                "主计数器已经是良品数，再减一次不良等于把不良算两遍，库存凭空少一截");
        assertEquals(0, q.defect().compareTo(bd("2")), "不良数仍要带出去记在工单上");
    }

    @Test
    @DisplayName("口径为空或未知值时按 TOTAL 处理（与全系统历史假设一致）")
    void unknownModeFallsBackToTotal() {
        assertEquals(0, ErpInboundQty.resolve(null, bd("10"), bd("2")).good().compareTo(bd("8")));
        assertEquals(0, ErpInboundQty.resolve("weird", bd("10"), bd("2")).good().compareTo(bd("8")));
        assertEquals(0, ErpInboundQty.resolve("good", bd("10"), bd("2")).good().compareTo(bd("10")),
                "小写的 good 也要认，配置是人工填的");
    }

    @Test
    @DisplayName("不良数为空按 0；负数不良按 0（脏数据不能把库存做成正数以外的怪值）")
    void nullAndNegativeDefect() {
        assertEquals(0, ErpInboundQty.resolve(DeviceEntity.COUNTER_TOTAL, bd("7"), null)
                .good().compareTo(bd("7")));
        assertEquals(0, ErpInboundQty.resolve(DeviceEntity.COUNTER_TOTAL, bd("7"), bd("-3"))
                .good().compareTo(bd("7")));
    }

    @Test
    @DisplayName("不良数大于产出（两个计数器采样错位）→ 入库夹到 0 并标记，不抛异常")
    void defectGreaterThanOutputClamps() {
        ErpInboundQty q = ErpInboundQty.resolve(DeviceEntity.COUNTER_TOTAL, bd("0"), bd("5"));
        assertEquals(0, q.good().signum(), "夹到 0，不能出现负入库");
        assertTrue(q.clamped(), "必须标记出来，好让调用方记一条告警");
        // 不良数本身照原样保留：它是真的，抹掉就再也不知道这一批出了多少废品
        assertEquals(0, q.defect().compareTo(bd("5")));
    }

    @Test
    @DisplayName("小数批量按 4 位精度保留，不丢精度")
    void decimalQuantities() {
        ErpInboundQty q = ErpInboundQty.resolve(DeviceEntity.COUNTER_TOTAL, bd("10.5"), bd("0.5"));
        assertEquals(0, q.good().compareTo(bd("10")));
    }
}
