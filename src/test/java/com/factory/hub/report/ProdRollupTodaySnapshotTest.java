package com.factory.hub.report;

import com.factory.hub.collect.SnapshotStore;
import com.factory.hub.erp.ErpProdInboundService;
import com.factory.hub.model.DeviceEntity;
import com.factory.hub.service.DeviceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「今日产量批量快照」路径的测试。
 *
 * <p><b>为什么单独一个类</b>：{@link ProdRollupTodayOutputTest} 钉的是<b>口径契约</b>
 * （改造前后必须一致），而它走的是「快照缺失 → 查库兜底」那条路径。
 * <b>改造的核心代码（读快照那条路径）在那儿零覆盖</b> ⇒ 单独钉住。
 *
 * <p><b>钉什么</b>：
 * <ol>
 *   <li>快照命中 ⇒ <b>一次库都不查</b>（这是整个改造的目的）</li>
 *   <li>快照命中 + 有内存实时值 ⇒ 结果 = 快照值 + 实时值（当前小时由内存说了算）</li>
 *   <li>快照命中 + 无内存实时值 ⇒ 结果 = 快照值 + 当前小时的 DB 值</li>
 *   <li>快照缺失 ⇒ 回落查库路径（不能返回 0）</li>
 *   <li>刷新快照时<b>按各设备自己的清零时刻裁剪</b>（prodResetHour 不同 ⇒ 起点不同）</li>
 *   <li>刷新快照时<b>当前小时不计入</b>（它由内存实时值决定）</li>
 * </ol>
 */
class ProdRollupTodaySnapshotTest {

    private static final long HOUR_MS = 3_600_000L;

    private static DeviceEntity device(String code, int prodResetHour) {
        DeviceEntity d = new DeviceEntity();
        d.setCode(code);
        d.setName(code);
        d.setProdResetHour(prodResetHour);
        d.setEnabled(true);
        return d;
    }

    private static ProdHourly row(String code, long hourStart, double output) {
        ProdHourly p = new ProdHourly(code, code, hourStart);
        p.setOutput(output);
        return p;
    }

    /** 用反射塞进 buckets（模拟「这一小时已有内存累计」） */
    private static void putLiveBucket(ProdRollup rollup, String code, long curHour, double output) {
        ProdHourly p = new ProdHourly(code, code, curHour);
        p.setOutput(output);
        try {
            java.lang.reflect.Field f = ProdRollup.class.getDeclaredField("buckets");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ProdHourly> buckets = (Map<String, ProdHourly>) f.get(rollup);
            buckets.put(code + "|" + curHour, p);
        } catch (Exception e) {
            throw new IllegalStateException("塞内存桶失败", e);
        }
    }

    /** 直接塞快照（模拟 scan() 已经刷新过） */
    @SuppressWarnings("unchecked")
    private static void putSnapshot(ProdRollup rollup, Map<String, double[]> snap) {
        try {
            java.lang.reflect.Field f = ProdRollup.class.getDeclaredField("todayFromDb");
            f.setAccessible(true);
            Map<String, Object> m = new java.util.HashMap<>();
            for (Map.Entry<String, double[]> e : snap.entrySet()) {
                double[] v = e.getValue();
                // [closedSum, curHourDb, hasCurHour]
                m.put(e.getKey(), newTodayOut(v[0], v[1], v[2] > 0d));
            }
            ((java.util.concurrent.atomic.AtomicReference<Map<String, Object>>) f.get(rollup))
                    .set(Map.copyOf(m));
            // 快照小时 = 当前小时（否则「当前小时那部分」会被判为跨小时而不用）
            setSnapshotHour(rollup, System.currentTimeMillis()
                    - (System.currentTimeMillis() % 3_600_000L));
        } catch (Exception e) {
            throw new IllegalStateException("塞快照失败", e);
        }
    }

    /** 用反射构造 ProdRollup 的私有 record（record 构造器是 private） */
    private static Object newTodayOut(double closedSum, double curHourDb, boolean hasCurHour) {
        try {
            Class<?> c = Class.forName("com.factory.hub.report.ProdRollup$TodayOut");
            var ctor = c.getDeclaredConstructor(double.class, double.class, boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance(closedSum, curHourDb, hasCurHour);
        } catch (Exception e) {
            throw new IllegalStateException("构造 TodayOut 失败", e);
        }
    }

    private static void setSnapshotHour(ProdRollup rollup, long hour) {
        try {
            java.lang.reflect.Field f = ProdRollup.class.getDeclaredField("todaySnapshotHour");
            f.setAccessible(true);
            f.setLong(rollup, hour);
        } catch (Exception e) {
            throw new IllegalStateException("设快照小时失败", e);
        }
    }

    /** 造一个 ProdRollup，并记录查库次数 */
    private static ProdRollup rollupWith(List<DeviceEntity> devices,
                                         Map<String, List<ProdHourly>> rowsByCode,
                                         AtomicInteger queryCount) {
        ProdHourlyRepository repo = mock(ProdHourlyRepository.class);
        when(repo.findByDeviceCodeAndHourStartBetween(anyString(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    queryCount.incrementAndGet();
                    String code = inv.getArgument(0);
                    long from = (Long) inv.getArgument(1);
                    long to = (Long) inv.getArgument(2);
                    List<ProdHourly> rows = rowsByCode.get(code);
                    if (rows == null) {
                        return List.of();
                    }
                    List<ProdHourly> in = new ArrayList<>();
                    for (ProdHourly p : rows) {
                        if (p.getHourStart() >= from && p.getHourStart() <= to) {
                            in.add(p);
                        }
                    }
                    return in;
                });
        when(repo.findByDeviceCodeInAndHourStartBetween(any(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    queryCount.incrementAndGet();
                    List<ProdHourly> all = new ArrayList<>();
                    for (Map.Entry<String, List<ProdHourly>> e : rowsByCode.entrySet()) {
                        long from = (Long) inv.getArgument(1);
                        long to = (Long) inv.getArgument(2);
                        for (ProdHourly p : e.getValue()) {
                            if (p.getHourStart() >= from && p.getHourStart() <= to) {
                                all.add(p);
                            }
                        }
                    }
                    return all;
                });

        DeviceService deviceService = mock(DeviceService.class);
        when(deviceService.all()).thenReturn(devices);
        when(deviceService.byCode(anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            for (DeviceEntity d : devices) {
                if (d.getCode().equals(code)) {
                    return d;
                }
            }
            return null;
        });

        return new ProdRollup(new SnapshotStore(), deviceService, repo,
                mock(ErpProdInboundService.class), mock(DevCounterStateRepository.class));
    }

    @Test
    @DisplayName("快照①：命中快照 ⇒ 一次库都不查（整个改造的目的）")
    void snapshotHitDoesNotQueryDb() {
        AtomicInteger q = new AtomicInteger();
        ProdRollup r = rollupWith(List.of(device("D1", 0)), new HashMap<>(), q);
        putSnapshot(r, Map.of("D1", new double[]{100d, 0d, 0d}));

        int before = q.get();
        double v = r.todayOutputOf("D1");

        assertEquals(100d, v, 1e-9, "快照命中时应直接用快照值");
        assertEquals(before, q.get(),
                "快照命中时**不该再查库** —— 查库正是这个改造要消掉的开销");
    }

    @Test
    @DisplayName("快照②：快照 + 内存实时值 ⇒ 快照值 + 实时值（当前小时由内存说了算）")
    void snapshotPlusLiveValue() {
        long now = System.currentTimeMillis();
        long curHour = now - (now % HOUR_MS);
        AtomicInteger q = new AtomicInteger();
        ProdRollup r = rollupWith(List.of(device("D1", 0)), new HashMap<>(), q);
        putSnapshot(r, Map.of("D1", new double[]{100d, 0d, 0d}));  // 已落库部分（不含当前小时）
        putLiveBucket(r, "D1", curHour, 8d);     // 当前小时的内存实时值

        assertEquals(108d, r.todayOutputOf("D1"), 1e-9,
                "当前小时由内存实时值决定 ⇒ 快照(100) + 实时(8)");
    }

    @Test
    @DisplayName("快照③：快照命中但无内存实时值 ⇒ 用当前小时的 DB 值兜底")
    void snapshotWithoutLiveUsesCurrentHourFromDb() {
        long now = System.currentTimeMillis();
        long curHour = now - (now % HOUR_MS);
        Map<String, List<ProdHourly>> rows = new HashMap<>();
        rows.put("D1", List.of(row("D1", curHour, 6d)));   // DB 里有当前小时行
        AtomicInteger q = new AtomicInteger();
        ProdRollup r = rollupWith(List.of(device("D1", 0)), rows, q);
        putSnapshot(r, Map.of("D1", new double[]{100d, 6d, 1d}));  // 快照：closed=100, 当前小时DB=6
        // 不塞内存桶 ⇒ liveOutput 返回 NaN

        assertEquals(106d, r.todayOutputOf("D1"), 1e-9,
                "没有内存实时值时，当前小时用 DB 里的值（快照 100 + DB 6）");
    }

    @Test
    @DisplayName("快照④：快照缺失 ⇒ 回落查库路径（不能返回 0）")
    void snapshotMissFallsBackToQuery() {
        long now = System.currentTimeMillis();
        long curHour = now - (now % HOUR_MS);
        Map<String, List<ProdHourly>> rows = new HashMap<>();
        rows.put("D1", List.of(row("D1", curHour - HOUR_MS, 12d)));
        AtomicInteger q = new AtomicInteger();
        ProdRollup r = rollupWith(List.of(device("D1", 0)), rows, q);

        assertEquals(12d, r.todayOutputOf("D1"), 1e-9,
                "快照里没有这台设备（新加的设备）⇒ 应回落查库，而不是返回 0");
        assertTrue(q.get() > 0, "回落路径应当真的查了库");
    }

    @Test
    @DisplayName("快照⑤：刷新时按各设备自己的清零时刻裁剪（prodResetHour 不同 ⇒ 起点不同）")
    void refreshRespectsPerDeviceResetHour() {
        long now = System.currentTimeMillis();
        long dayStart8 = com.factory.hub.erp.ProdDay.dayStart(now, 8);
        // D1 清零时刻 8 ⇒ dayStart8 之前那行不该算；D2 清零时刻 0 ⇒ 全部该算
        Map<String, List<ProdHourly>> rows = new HashMap<>();
        rows.put("D1", List.of(
                row("D1", dayStart8 - HOUR_MS, 100d),   // 8 点之前 ⇒ 不算
                row("D1", dayStart8, 3d)));              // 8 点之后 ⇒ 算
        rows.put("D2", List.of(
                row("D2", dayStart8 - HOUR_MS, 100d),   // D2 起点更早 ⇒ 算
                row("D2", dayStart8, 3d)));

        AtomicInteger q = new AtomicInteger();
        ProdRollup r = rollupWith(List.of(device("D1", 8), device("D2", 0)), rows, q);
        r.scan();

        // 读快照（D1 = 3，D2 = 103）
        Map<String, Double> snap = readSnapshot(r);
        assertEquals(3d, snap.get("D1"), 1e-9,
                "D1（清零 8 点）只该算 8 点之后的行");
        assertEquals(103d, snap.get("D2"), 1e-9,
                "D2（清零 0 点）该算全部两行");
    }

    @Test
    @DisplayName("快照⑥：刷新时当前小时不计入快照（它由内存实时值决定）")
    void refreshExcludesCurrentHour() {
        long now = System.currentTimeMillis();
        long curHour = now - (now % HOUR_MS);
        Map<String, List<ProdHourly>> rows = new HashMap<>();
        rows.put("D1", List.of(
                row("D1", curHour - HOUR_MS, 10d),   // 上一小时 ⇒ 该算
                row("D1", curHour, 99d)));             // 当前小时 ⇒ 不该算进快照

        AtomicInteger q = new AtomicInteger();
        ProdRollup r = rollupWith(List.of(device("D1", 0)), rows, q);
        r.scan();

        Map<String, Double> snap = readSnapshot(r);
        assertEquals(10d, snap.get("D1"), 1e-9,
                "当前小时不进快照（否则会与内存实时值重复计算）");
    }

    /** 读快照里每台设备的 closedSum（[0]） */
    @Test
    @DisplayName("快照⑦：写库后快照**不清空**（否则 flush 后 1 秒内全体退化成每台查库）")
    void invalidateDoesNotClearSnapshot() throws Exception {
        long now = System.currentTimeMillis();
        long curHour = now - (now % 3_600_000L);
        AtomicInteger q = new AtomicInteger();
        ProdRollup r = rollupWith(List.of(device("D1", 0)), new HashMap<>(), q);
        putSnapshot(r, Map.of("D1", new double[]{100d, 0d, 0d}));

        // 调 invalidate（写库后触发）
        java.lang.reflect.Method inv = ProdRollup.class.getDeclaredMethod("invalidateTodaySnapshot");
        inv.setAccessible(true);
        inv.invoke(r);

        Map<String, double[]> snap = readSnapshotRaw(r);
        assertTrue(snap.containsKey("D1"),
                "**写库后快照被清空了** ⇒ 下一次 scan() 之前所有 todayOutputOf 都会走"
                        + "「查库兜底」⇒ 1000 台瞬间回到「每台查 2 次库」的老形态 ⇒ 瓶颈原样复现。"
                        + "正确做法：只标 dirty（等下一拍重建），期间继续用旧快照"
                        + "（旧快照最多旧 1 秒，而 DB 数据本身 60 秒才变一次 ⇒ 不会比 DB 更旧）");
        assertEquals(100d, snap.get("D1")[0], 1e-9, "快照值本身不该被改");
    }

    @Test
    @DisplayName("快照⑧：删设备只摘掉**那一台**，其余设备不受影响")
    void purgeRemovesOnlyThatDevice() throws Exception {
        AtomicInteger q = new AtomicInteger();
        ProdRollup r = rollupWith(List.of(device("D1", 0), device("D2", 0)), new HashMap<>(), q);
        putSnapshot(r, Map.of("D1", new double[]{100d, 0d, 0d}, "D2", new double[]{200d, 0d, 0d}));

        r.purgeByCode("D1");

        Map<String, double[]> snap = readSnapshotRaw(r);
        assertFalse(snap.containsKey("D1"),
                "被删的设备不该还在快照里（否则会一直推一个已删设备的产量）");
        assertTrue(snap.containsKey("D2"),
                "**其余设备被连带清掉了** ⇒ 它们会在下次 scan() 前退化成查库路径 ⇒ 1000 台集体退化");
    }

    /** 读快照的原始内容（key → [closedSum, curHourDb, hasCurHour]） */
    @SuppressWarnings("unchecked")
    private static Map<String, double[]> readSnapshotRaw(ProdRollup rollup) {
        try {
            java.lang.reflect.Field f = ProdRollup.class.getDeclaredField("todayFromDb");
            f.setAccessible(true);
            Map<String, Object> raw = (Map<String, Object>) ((java.util.concurrent.atomic.AtomicReference<?>) f.get(rollup)).get();
            Map<String, double[]> out = new java.util.HashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                double[] v = new double[3];
                java.lang.reflect.Field cs = e.getValue().getClass().getDeclaredField("closedSum");
                cs.setAccessible(true);
                v[0] = (Double) cs.get(e.getValue());
                out.put(e.getKey(), v);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("读快照失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> readSnapshot(ProdRollup rollup) {
        try {
            java.lang.reflect.Field f = ProdRollup.class.getDeclaredField("todayFromDb");
            f.setAccessible(true);
            Map<String, Object> raw = (Map<String, Object>) ((java.util.concurrent.atomic.AtomicReference<?>) f.get(rollup)).get();
            Map<String, Double> out = new java.util.HashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                java.lang.reflect.Field cf = e.getValue().getClass().getDeclaredField("closedSum");
                cf.setAccessible(true);
                out.put(e.getKey(), (Double) cf.get(e.getValue()));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("读快照失败", e);
        }
    }
}
