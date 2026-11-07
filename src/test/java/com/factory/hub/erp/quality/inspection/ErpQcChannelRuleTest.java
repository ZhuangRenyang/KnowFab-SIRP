package com.factory.hub.erp.quality.inspection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.factory.hub.erp.quality.defect.ErpQcDefect;

/**
 * 有效通道口径的测试（QC-SPEC §2.5.1）。
 *
 * <p><b>这个类守的是"错了不报错"的那类 bug</b>：设备通道与人工通道描述同一批货，
 * 统计时相加就是重复计数。表现是数字偏大、页面正常、良率看着还挺合理——
 * <b>不报错、不白屏、不影响任何测试</b>，月底盘账才发现。
 *
 * <p>所以必须在这里钉死：口径规则一旦被改坏，测试要立刻红。
 */
class ErpQcChannelRuleTest {

    private static ErpQcDefect row(String source, String wo, String qty, String reconState) {
        ErpQcDefect d = new ErpQcDefect();
        d.setSource(source);
        d.setWorkOrderNo(wo);
        d.setQty(new BigDecimal(qty));
        d.setReconState(reconState);
        d.setStatus(ErpQcDefect.ST_PENDING);
        return d;
    }

    @Test
    @DisplayName("未定责时设备通道优先：人工补录不叠加（QA 静态验收 P0-2）")
    void deviceChannelWinsWhenNotDecided() {
        List<ErpQcDefect> rows = new ArrayList<>();
        rows.add(row(ErpQcDefect.SRC_DEVICE, "WO1", "3", ErpQcDefect.RECON_NONE));
        rows.add(row(ErpQcDefect.SRC_MANUAL, "WO1", "2", ErpQcDefect.RECON_NONE));
        // 相加 = 5，那是重复计数；正确答案是设备说的 3
        assertEquals(0, ErpQcChannelRule.effectiveQty(rows).compareTo(new BigDecimal("3")));
    }

    @Test
    @DisplayName("没有设备数据时，人工记录全算（不能因为「设备优先」就把人工的数丢了）")
    void manualOnlyIsCountedWhenNoDevice() {
        List<ErpQcDefect> rows = new ArrayList<>();
        rows.add(row(ErpQcDefect.SRC_MANUAL, "WO1", "2", ErpQcDefect.RECON_NONE));
        rows.add(row(ErpQcDefect.SRC_INSPECTION, "WO1", "1", ErpQcDefect.RECON_NONE));
        assertEquals(0, ErpQcChannelRule.effectiveQty(rows).compareTo(new BigDecimal("3")));
    }

    @Test
    @DisplayName("定责「以质检为准」→ 只算人工通道")
    void trustQcKeepsManualChannel() {
        List<ErpQcDefect> rows = new ArrayList<>();
        rows.add(row(ErpQcDefect.SRC_DEVICE, "WO1", "3", ErpQcDefect.RECON_DROP));
        rows.add(row(ErpQcDefect.SRC_MANUAL, "WO1", "2", ErpQcDefect.RECON_KEEP));
        assertEquals(0, ErpQcChannelRule.effectiveQty(rows).compareTo(new BigDecimal("2")));
    }

    @Test
    @DisplayName("定责「以设备为准」→ 只算设备通道")
    void trustDeviceKeepsDeviceChannel() {
        List<ErpQcDefect> rows = new ArrayList<>();
        rows.add(row(ErpQcDefect.SRC_DEVICE, "WO1", "3", ErpQcDefect.RECON_KEEP));
        rows.add(row(ErpQcDefect.SRC_MANUAL, "WO1", "2", ErpQcDefect.RECON_DROP));
        assertEquals(0, ErpQcChannelRule.effectiveQty(rows).compareTo(new BigDecimal("3")));
    }

    @Test
    @DisplayName("定责是标记不是改数：重复定责，合计数不变（不变式）")
    void repeatedDecisionDoesNotInflate() {
        // 设备 3 + 人工 2。旧实现（改 qty）会变成 5+2=7，再定一次 7+2=9。
        List<ErpQcDefect> rows = new ArrayList<>();
        rows.add(row(ErpQcDefect.SRC_DEVICE, "WO1", "3", ErpQcDefect.RECON_NONE));
        rows.add(row(ErpQcDefect.SRC_MANUAL, "WO1", "2", ErpQcDefect.RECON_NONE));

        BigDecimal before = ErpQcChannelRule.effectiveQty(rows);
        assertEquals(0, before.compareTo(new BigDecimal("3")));

        // 第一次定责：以质检为准 → 设备 DROP、人工 KEEP
        for (ErpQcDefect d : rows) {
            d.setReconState(ErpQcDefect.SRC_DEVICE.equals(d.getSource())
                    ? ErpQcDefect.RECON_DROP : ErpQcDefect.RECON_KEEP);
        }
        BigDecimal after1 = ErpQcChannelRule.effectiveQty(rows);
        assertEquals(0, after1.compareTo(new BigDecimal("2")));

        // 第二次定责：同样的选择，结果必须一样
        for (ErpQcDefect d : rows) {
            d.setReconState(ErpQcDefect.SRC_DEVICE.equals(d.getSource())
                    ? ErpQcDefect.RECON_DROP : ErpQcDefect.RECON_KEEP);
        }
        BigDecimal after2 = ErpQcChannelRule.effectiveQty(rows);
        assertEquals(0, after2.compareTo(after1), "重复定责必须幂等：合计不能变");
        assertTrue(after2.compareTo(new BigDecimal("9")) != 0,
                "越校越大是旧实现的 bug（3+2 → 7 → 9），现在不能出现");
    }

    @Test
    @DisplayName("不同工单各自算自己的（跨工单不混）")
    void differentWorkOrdersAreIndependent() {
        List<ErpQcDefect> rows = new ArrayList<>();
        rows.add(row(ErpQcDefect.SRC_DEVICE, "WO1", "3", ErpQcDefect.RECON_NONE));
        rows.add(row(ErpQcDefect.SRC_MANUAL, "WO1", "2", ErpQcDefect.RECON_NONE));
        rows.add(row(ErpQcDefect.SRC_MANUAL, "WO2", "7", ErpQcDefect.RECON_NONE));
        // WO1 = 3（设备优先），WO2 = 7（没设备数据，人工全算）
        assertEquals(0, ErpQcChannelRule.effectiveQty(rows).compareTo(new BigDecimal("10")));
    }

    @Test
    @DisplayName("作废的行一律不计入")
    void voidedRowsAreExcluded() {
        List<ErpQcDefect> rows = new ArrayList<>();
        ErpQcDefect d = row(ErpQcDefect.SRC_MANUAL, "WO1", "5", ErpQcDefect.RECON_NONE);
        d.setVoidedAt(System.currentTimeMillis());
        rows.add(d);
        assertEquals(0, ErpQcChannelRule.effectiveQty(rows).compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("被定责判为 DROP 的行不计入")
    void droppedRowsAreExcluded() {
        List<ErpQcDefect> rows = new ArrayList<>();
        rows.add(row(ErpQcDefect.SRC_DEVICE, "WO1", "3", ErpQcDefect.RECON_DROP));
        rows.add(row(ErpQcDefect.SRC_MANUAL, "WO1", "2", ErpQcDefect.RECON_NONE));
        // 设备被 DROP，且人工未定责（没 KEEP）→ 无设备通道 → 人工全算
        assertEquals(0, ErpQcChannelRule.effectiveQty(rows).compareTo(new BigDecimal("2")));
    }

    @Test
    @DisplayName("没有工单号的记录各自算一组（人工补录没填工单时不能互相抵消）")
    void rowsWithoutWorkOrderAreIndependent() {
        List<ErpQcDefect> rows = new ArrayList<>();
        rows.add(row(ErpQcDefect.SRC_MANUAL, null, "4", ErpQcDefect.RECON_NONE));
        rows.add(row(ErpQcDefect.SRC_MANUAL, null, "6", ErpQcDefect.RECON_NONE));
        assertEquals(0, ErpQcChannelRule.effectiveQty(rows).compareTo(new BigDecimal("10")));
    }
}
