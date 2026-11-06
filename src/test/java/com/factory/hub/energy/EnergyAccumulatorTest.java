package com.factory.hub.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EnergyAccumulator} 的增量与「断线补偿」判定。
 * <p>
 * <b>为什么这个测试重要</b>：需量摊平（DH-86）要靠 {@code Delta.fromTs} 知道
 * 「这段电量是从什么时候到什么时候的」。如果起点传错，摊平会把电量分到错误的窗口——
 * 而窗口错了就是峰值错了，峰值错了就是电费算错了，而且**不会报任何错**。
 * <p>
 * 所以这里钉死三件事：断线判定的阈值边界、跨度的起止时刻、以及清零/重复扫描不能
 * 被误判成断线补偿（否则会往需量里塞一段根本不存在的电量）。
 */
class EnergyAccumulatorTest {

    private static final long MIN = 60_000L;
    private static final long BASE = 1_700_000_000_000L;

    @Test
    @DisplayName("首次读数只立基线，不产电量（重启后同理）")
    void firstReadingOnlySetsBaseline() {
        EnergyAccumulator acc = new EnergyAccumulator();
        EnergyAccumulator.Delta d = acc.feed(BASE, 1000d, "kWh");

        assertEquals(0d, d.kwh(), 1e-9);
        assertFalse(d.counted());
        assertFalse(d.backfill());
    }

    @Test
    @DisplayName("正常采集（间隔 1 分钟）：增量正确，不算断线，起止时刻如实带出")
    void normalIntervalIsNotBackfill() {
        EnergyAccumulator acc = new EnergyAccumulator();
        acc.feed(BASE, 1000d, "kWh");
        EnergyAccumulator.Delta d = acc.feed(BASE + MIN, 1002.5d, "kWh");

        assertEquals(2.5d, d.kwh(), 1e-9);
        assertFalse(d.backfill(), "1 分钟是正常采集，不能标成断线补偿");
        assertEquals(BASE, d.fromTs(), "起点 = 上一次读数时刻");
        assertEquals(BASE + MIN, d.toTs());
    }

    @Test
    @DisplayName("断线 40 分钟：标 backfill，且带出真实跨度（摊平靠它）")
    void longGapIsBackfillWithSpan() {
        EnergyAccumulator acc = new EnergyAccumulator();
        acc.feed(BASE, 1000d, "kWh");
        EnergyAccumulator.Delta d = acc.feed(BASE + 40 * MIN, 1040d, "kWh");

        assertEquals(40d, d.kwh(), 1e-9);
        assertTrue(d.backfill(), "超过 5 分钟没读数 = 断线补偿");
        assertEquals(BASE, d.fromTs());
        assertEquals(BASE + 40 * MIN, d.toTs());
    }

    @Test
    @DisplayName("阈值边界：刚过 5 分钟算断线，正好 5 分钟不算")
    void backfillThresholdBoundary() {
        EnergyAccumulator acc = new EnergyAccumulator();
        acc.feed(BASE, 1000d, "kWh");
        assertFalse(acc.feed(BASE + 5 * MIN, 1001d, "kWh").backfill(),
                "gap == 5 分钟是边界，不算断线（阈值是 > 5 分钟）");

        EnergyAccumulator acc2 = new EnergyAccumulator();
        acc2.feed(BASE, 1000d, "kWh");
        assertTrue(acc2.feed(BASE + 5 * MIN + 1, 1002d, "kWh").backfill(),
                "5 分钟零 1 毫秒就该算断线");
    }

    @Test
    @DisplayName("读数变小（清零/换表）：记 0 且不产负数，不能当成断线补偿")
    void resetProducesZeroNotBackfill() {
        EnergyAccumulator acc = new EnergyAccumulator();
        acc.feed(BASE, 5000d, "kWh");
        EnergyAccumulator.Delta d = acc.feed(BASE + 40 * MIN, 10d, "kWh");

        assertEquals(0d, d.kwh(), "清零瞬间记 0，绝不能是负数");
        assertTrue(d.reset());
        assertFalse(d.backfill(), "清零不是断线补偿，别往需量里塞数据");
        assertEquals(1, acc.resets());
    }

    @Test
    @DisplayName("同一条读数被扫描多遍：只算一次（采集与汇总两套节拍）")
    void duplicateScanCountsOnce() {
        EnergyAccumulator acc = new EnergyAccumulator();
        acc.feed(BASE, 1000d, "kWh");
        assertEquals(2d, acc.feed(BASE + 10_000L, 1002d, "kWh").kwh(), 1e-9);

        // 汇总每秒扫一次，但设备 5 秒才更新一次 —— 同一条数据会被扫到多次
        EnergyAccumulator.Delta again = acc.feed(BASE + 10_000L, 1002d, "kWh");
        assertEquals(0d, again.kwh(), 1e-9);
        assertFalse(again.backfill());
    }

    @Test
    @DisplayName("点表单位配成 Wh：自动除以 1000（少算 1000 倍是现场常事）")
    void whUnitIsConverted() {
        EnergyAccumulator acc = new EnergyAccumulator();
        acc.feed(BASE, 1_000_000d, "Wh");
        EnergyAccumulator.Delta d = acc.feed(BASE + MIN, 1_005_000d, "Wh");

        assertEquals(5d, d.kwh(), 1e-9, "5000 Wh = 5 kWh");
    }
}
