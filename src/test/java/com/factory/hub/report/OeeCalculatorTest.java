package com.factory.hub.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OEE 计算测试（DH-15）。
 *
 * <p><b>最要紧的一条：算不出来的时候必须给 null，不能给个假的数。</b>
 * 缺理想节拍、缺不良数数据 → 对应因子 null、OEE null。
 * 要是这里图省事按 0 或按 100% 处理，客户拿到的就是一个看着正常、其实是编的 OEE，
 * 拿它去做改善决策比没有这个数更糟。
 */
class OeeCalculatorTest {

    private final OeeCalculator calc = new OeeCalculator();

    /** 三样都齐的输入：跑 4 小时（14400 秒），计划 8 小时（28800 秒），节拍 30 秒/件，产出 400 件，不良 10 件 */
    private OeeCalculator.Input complete() {
        return OeeCalculator.forDevice(14400, 400, 10, true, 30d, 28800, 14400);
    }

    /* ==================== 三因子齐全 ==================== */

    @Test
    @DisplayName("三因子齐全：可用率 50%、性能 83.33%、良率 97.5%，OEE 是三者相乘")
    void completeCase() {
        OeeCalculator.Result r = calc.compute(complete());
        assertEquals(50d, r.availability(), 0.01);          // 14400 / 28800
        assertEquals(83.33d, r.performance(), 0.01);          // 400 / (14400/30 = 480)
        assertEquals(97.5d, r.quality(), 0.01);               // (400-10)/400
        assertNotNull(r.oee());
        // 50% × 83.33% × 97.5% = 40.62%（三个因子都是百分数，要除两次 100；
        // 早先这里写的是 /100，等于把同一个错公式钉死在测试里，OEE 一路大了 100 倍）
        assertEquals(50 * 83.33 * 97.5 / 10000d, r.oee(), 0.05);
        assertTrue(r.oee() >= 0d && r.oee() <= 100d,
                "OEE 必须是 0~100 的百分数，实际算出来 " + r.oee());
    }

    @Test
    @DisplayName("理论产量 = 运行时间 ÷ 理想节拍")
    void theoreticalOutput() {
        OeeCalculator.Result r = calc.compute(complete());
        assertEquals(480d, r.theoreticalOutput(), 0.1);
    }

    /* ==================== 缺理想节拍 ==================== */

    @Test
    @DisplayName("没配理想节拍：性能 null、OEE null，并且说清缺什么")
    void missingCycle() {
        OeeCalculator.Result r = calc.compute(
                OeeCalculator.forDevice(14400, 400, 10, true, null, 28800, 14400));
        assertNull(r.performance(), "缺理想节拍时性能不能瞎给");
        assertNull(r.oee(), "缺一个因子 OEE 就不该有值");
        assertEquals(50d, r.availability(), 0.01, "可用率不受影响");
        assertEquals(97.5d, r.quality(), 0.01, "良率不受影响");
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("理想节拍")),
                "得说清缺的是理想节拍：" + r.notes());
    }

    @Test
    @DisplayName("理想节拍配成 0：按没配处理，不能除零")
    void zeroCycleTreatedAsMissing() {
        OeeCalculator.Result r = calc.compute(
                OeeCalculator.forDevice(14400, 400, 10, true, 0d, 28800, 14400));
        assertNull(r.performance());
        assertNull(r.oee());
    }

    /* ==================== 缺不良数数据 ==================== */

    @Test
    @DisplayName("没有不良数数据：良率 null、OEE null（不能当成零不良）")
    void missingDefectData() {
        OeeCalculator.Result r = calc.compute(
                OeeCalculator.forDevice(14400, 400, 0, false, 30d, 28800, 14400));
        assertNull(r.quality(), "没数据不等于零不良");
        assertNull(r.oee());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("不良数")),
                "得说清缺的是不良数：" + r.notes());
    }

    @Test
    @DisplayName("配了不良品计数器但本期真的是零不良：良率 100%，OEE 有值")
    void zeroDefectWithData() {
        OeeCalculator.Result r = calc.compute(
                OeeCalculator.forDevice(14400, 400, 0, true, 30d, 28800, 14400));
        assertEquals(100d, r.quality(), 0.01);
        assertNotNull(r.oee(), "已知是零不良，OEE 该算得出来");
    }

    /* ==================== 边界 ==================== */

    @Test
    @DisplayName("设备整天没开工：OEE 是 0（这是已知结果，不是数据缺失）")
    void neverRan() {
        OeeCalculator.Result r = calc.compute(
                OeeCalculator.forDevice(0, 0, 0, true, 30d, 28800, 0));
        assertEquals(0d, r.availability(), 0.01);
        assertEquals(0d, r.performance(), 0.01);
        assertNotNull(r.oee());
        assertEquals(0d, r.oee(), 0.01);
    }

    @Test
    @DisplayName("跑了但一件没做：性能 0，OEE 是 0")
    void ranButNoOutput() {
        OeeCalculator.Result r = calc.compute(
                OeeCalculator.forDevice(14400, 0, 0, true, 30d, 28800, 14400));
        assertEquals(0d, r.performance(), 0.01, "理论产量 480 件，实际 0 件");
        assertNotNull(r.oee());
        assertEquals(0d, r.oee(), 0.01);
    }

    @Test
    @DisplayName("没配班次计划：可用率退回按已记录时长算，并说明口径不同")
    void noShiftPlan() {
        OeeCalculator.Result r = calc.compute(
                OeeCalculator.forDevice(14400, 400, 10, true, 30d, 0, 28800));
        assertEquals(50d, r.availability(), 0.01);
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("班次")),
                "得说明用的是另一个口径：" + r.notes());
    }

    @Test
    @DisplayName("性能率超过 100%：如实显示并提示节拍可能配小了（不截断）")
    void performanceOver100() {
        // 节拍 10 秒 → 理论产量 1440，实际 400 → 性能 27.8%
        // 反过来：节拍 100 秒 → 理论 144，实际 400 → 性能 277.8%
        OeeCalculator.Result r = calc.compute(
                OeeCalculator.forDevice(14400, 400, 0, true, 100d, 28800, 14400));
        assertEquals(277.78d, r.performance(), 0.01, "不能截断成 100%，那会把节拍配错藏起来");
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("100%")),
                "得提示节拍可能配小了：" + r.notes());
    }

    @Test
    @DisplayName("不良数比产量还多：良率按 0 算并提示数据可能录错")
    void defectMoreThanOutput() {
        OeeCalculator.Result r = calc.compute(
                OeeCalculator.forDevice(14400, 100, 150, true, 30d, 28800, 14400));
        assertEquals(0d, r.quality(), 0.01);
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("录错")),
                "得提示可能录错了：" + r.notes());
    }

    @Test
    @DisplayName("没有任何数据：可用率也算不出来")
    void noDataAtAll() {
        OeeCalculator.Result r = calc.compute(
                OeeCalculator.forDevice(0, 0, 0, false, null, 0, 0));
        assertNull(r.availability());
        assertNull(r.oee());
    }

    /* ==================== 合计 ==================== */

    @Test
    @DisplayName("合计：理论产量是各设备加起来的（不能拿一个节拍算全厂）")
    void aggregate() {
        // 两台设备：A 节拍 30 秒跑了 14400 秒 → 理论 480；B 节拍 60 秒跑了 14400 秒 → 理论 240
        // 合计理论 = 720，实际产出 400+240 = 640 → 性能 88.89%
        OeeCalculator.Result r = calc.compute(OeeCalculator.forAggregate(
                28800, 640, 20, true, 720d, 28800 * 2, 28800, null));
        assertEquals(50d, r.availability(), 0.01);
        assertEquals(88.89d, r.performance(), 0.01);
        assertEquals(96.88d, r.quality(), 0.01);
        assertNotNull(r.oee());
    }

    @Test
    @DisplayName("合计里有设备缺理想节拍：合计性能 null，但说清缺几台")
    void aggregateMissingCycle() {
        OeeCalculator.Result r = calc.compute(OeeCalculator.forAggregate(
                28800, 640, 20, true, null, 28800 * 2, 28800,
                "有 1 台设备没配「理想节拍（秒/件）」，合计性能率算不出来"));
        assertNull(r.performance());
        assertNull(r.oee());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("1 台设备")),
                "得说清缺几台：" + r.notes());
    }
}
